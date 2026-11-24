"""Public Android controls for one French, user-approved nearby restaurant conversation."""

import json
import re
import sys

from verification.nearby_location import answer_android_location, location_access
from verification.phone_actions import focused
from verification.unifiedpush.stack import eventually
from verification.unifiedpush.ui import APP


PROMPT = "Quels restaurants se trouvent près de moi ?"
GMS_PACKAGE = "com.google.android.gms"


def dismiss_ime(ui):
    if ui.device.ime_shown():
        ui.device.shell("input", "keyevent", "BACK")
        eventually("soft keyboard to hide", lambda: not ui.device.ime_shown(), timeout=15)


def enter_message(ui, prompt=PROMPT):
    s = ui.strings
    send_btn = s["thread_send"]
    composer_hint = s["thread_message_hint"]

    def composer():
        tree = ui.hierarchy()
        if ui.node(send_btn, tree) is None:
            return None
        for node in tree.iter("node"):
            if node.get("package") == APP and node.get("class") == "android.widget.EditText":
                return (node,)
        return None

    field = eventually("visible composer editor", composer)[0]
    current = field.get("text", "")
    if current != prompt:
        label = current or composer_hint
        if ui.controls.send("text", label=label, value=prompt) != b"applied":
            raise AssertionError("The public Android composer did not accept the request")
    dismiss_ime(ui)


def open_consent(ui):
    s = ui.strings
    send_btn = s["thread_send"]
    consent_title = s["nearby_add_area_title"]
    failed_status = s["nearby_unavailable_hint"]
    searching_status = s["nearby_locating"]
    choose_profile = s["profile_choose_title"]
    composer_hint = s["thread_message_hint"]
    dismiss_ime(ui)
    taps = 0
    ime_dismissals = 0
    gms_dismissals = 0
    last_send_bounds = None

    def opened():
        nonlocal taps, ime_dismissals, gms_dismissals, last_send_bounds
        root = ui.hierarchy()
        if ui.node(consent_title, root) is not None:
            return (root,)
        packages = {node.get("package", "") for node in root.iter("node") if node.get("package")}
        if APP not in packages and GMS_PACKAGE in packages:
            ui.device.shell("input", "keyevent", "BACK")
            gms_dismissals += 1
            return None
        if ui.device.ime_shown():
            ui.device.shell("input", "keyevent", "BACK")
            ime_dismissals += 1
            return None
        send = ui.node(send_btn, root)
        if send is not None:
            last_send_bounds = send.get("bounds")
            ui.tap_node(send)
            taps += 1
        return None

    print("Tap " + send_btn, file=sys.stderr, flush=True)
    try:
        tree = eventually("visible control " + consent_title, opened)[0]
    except AssertionError:
        root = ui.hierarchy()
        packages = sorted({node.get("package", "") for node in root.iter("node") if node.get("package")})
        print("Android open_consent " + json.dumps({
            "focused": focused(ui.device),
            "packages": packages,
            "ime_shown": ui.device.ime_shown(),
            "taps": taps,
            "ime_dismissals": ime_dismissals,
            "gms_dismissals": gms_dismissals,
            "send_bounds": last_send_bounds,
            "has_send": ui.node(send_btn, root) is not None,
            "has_failed": ui.node(failed_status, root) is not None,
            "has_searching": ui.node(searching_status, root) is not None,
            "has_lock_screen": ui.node(choose_profile, root) is not None,
            "has_composer": ui.node(composer_hint, root) is not None,
            "location_grant": location_access(ui.device),
        }, sort_keys=True), file=sys.stderr, flush=True)
        raise
    disclosed = [node.get("text", "") for node in tree.iter("node") if node.get("package") == APP]
    if not any("Hermes" in text and "OpenStreetMap" in text for text in disclosed):
        raise AssertionError("The message did not disclose both location recipients before consent")
    return tree


def choose_consent(ui, label):
    consent_title = ui.strings["nearby_add_area_title"]
    ui.tap_until(
        label,
        lambda root: ui.node(consent_title, root) is None,
        scroll=True,
        description="dismissed location consent dialog after choosing " + label,
    )


def stage_public_location(ui, fix, *, system_prompt=True, remember=False):
    """Stage the approximate fix. The caller must explicitly tap Send a second time."""
    s = ui.strings
    open_consent(ui)
    if remember:
        remember_label = s["nearby_always_profile"]
        ui.tap_until(
            remember_label,
            lambda root: (node := ui.node(remember_label, root)) is not None and node.get("checked") == "true",
            scroll=True,
            description="remember location preference checked",
        )
    fix.publish()
    choose_consent(ui, s["nearby_use_approx"])
    if system_prompt:
        answer_android_location(ui, allow=True)
    ui.wait(s["nearby_approx_attached"])


def unlock(ui, label, phrase):
    s = ui.strings
    passphrase_label = s["profile_passphrase_label"]
    unlock_btn = s["profile_unlock_action"]
    ui.tap_until(
        label,
        lambda root: ui.node(passphrase_label, root) is not None,
        scroll=True,
        description="profile unlock form for " + label,
    )
    ui.field(passphrase_label, phrase)
    ui.tap_until(
        unlock_btn,
        lambda root: ui.node(unlock_btn, root) is None,
        scroll=True,
        description="the profile to finish unlocking",
    )


def ensure_unlocked(ui, label, phrase):
    """Unlock the named profile if returning to the lock screen, or continue if auto-lock grace kept it open."""
    s = ui.strings
    choose_profile = s["profile_choose_title"]
    profile_lock_btn = s["top_profile_lock"]

    def ready():
        root = ui.hierarchy()
        if ui.node(choose_profile, root) is not None:
            return ("locked",)
        if ui.node(profile_lock_btn, root) is not None:
            return ("open",)
        return None

    state = eventually("Hermes workspace or lock screen", ready)[0]
    if state == "locked":
        unlock(ui, label, phrase)


def lock(ui):
    s = ui.strings
    profile_lock_btn = s["top_profile_lock"]
    lock_now = s["security_lock_now"]
    choose_profile = s["profile_choose_title"]
    ui.tap_until(
        profile_lock_btn,
        lambda root: ui.node(profile_lock_btn, root) is None or ui.node(lock_now, root) is not None,
        description="security dialog opened from " + profile_lock_btn,
    )
    ui.tap_until(
        lock_now,
        lambda root: ui.node(choose_profile, root) is not None,
        scroll=True,
        description="visible control " + choose_profile,
    )


def visible_app_node(ui, fragment, *, attribute=None):
    """Find a rendered, on-screen bit of guide data, revealing it by normal vertical scrolling."""
    last = None
    upward = False

    def find():
        nonlocal last, upward
        root = ui.hierarchy()
        for node in root.iter("node"):
            if node.get("package") != APP:
                continue
            labels = (node.get(attribute, ""),) if attribute else (node.get("text", ""), node.get("content-desc", ""))
            if any(fragment.casefold() in label.casefold() for label in labels):
                box = [int(value) for value in re.findall(r'-?\d+', node.get("bounds", ""))]
                if len(box) == 4 and box[2] > box[0] and box[3] > box[1]:
                    return (node,)
        areas = []
        for node in root.iter("node"):
            if node.get("package") == APP and node.get("scrollable") == "true":
                bounds = tuple(int(v) for v in re.findall(r'-?\d+', node.get("bounds", "")))
                if len(bounds) == 4:
                    areas.append(bounds)
        if areas:
            x1, y1, x2, y2 = max(areas, key=lambda b: (b[2] - b[0]) * (b[3] - b[1]))
            view = tuple((node.get("text"), node.get("content-desc"), node.get("bounds"))
                         for node in root.iter("node") if node.get("package") == APP)
            if view == last:
                upward = not upward
            last = view
            low, high = y1 + (y2 - y1) // 3, y1 + 3 * (y2 - y1) // 4
            start, end = (low, high) if upward else (high, low)
            x = str((x1 + x2) // 2)
            ui.device.shell("input", "swipe", x, str(start), x, str(end), "240")
        return False

    return eventually("visible restaurant guide element " + fragment, find, timeout=45)[0]


def tap_visible_app_until(ui, fragment, ready, *, attribute=None, description=None):
    """Reveal a rendered app control and tap it until the expected UI transition settles."""
    def attempt():
        root = ui.hierarchy()
        result = ready(root)
        if result:
            return (result,)
        ui.tap_node(visible_app_node(ui, fragment, attribute=attribute))
        return None

    return eventually(description or ("state after tapping " + fragment), attempt)[0]


def verify_guide(ui, names, *, capture=None, distances=None, filter_cuisine=None):
    """Select observed source records and compare the rendered venue and map/source controls."""
    if len(names) < 2:
        raise ValueError("The guide demonstration needs two source records")
    s = ui.strings
    visible_app_node(ui, s["places_cat_restaurant_heading"])
    compass = visible_app_node(ui, s["places_cat_restaurant_compass"], attribute="content-desc")
    if names[0] not in compass.get("content-desc", ""):
        raise AssertionError("The compass does not point to the nearest returned restaurant")
    if capture:
        capture("guide")
    if distances is not None:
        if len(distances) != len(names):
            raise ValueError("A distance is required for each source record")
        for idx, (name, distance) in enumerate(zip(names[:2], distances[:2], strict=True), start=1):
            dist_label = s.fmt("places_distance_meters", distance)
            row_desc = s.fmt("places_guide_place_row_desc", idx, name, dist_label, "").rstrip()
            visible_app_node(ui, row_desc, attribute="content-desc")
    # Compare without re-fetching. Both facts must be observed as rendered text.
    compare_title = s.fmt("places_compare_with", names[0])
    tap_visible_app_until(
        ui,
        s["places_guide_compare_desc"],
        lambda root: ui.node(compare_title, root) is not None,
        attribute="content-desc",
        description="visible control " + compare_title,
    )
    tap_visible_app_until(
        ui,
        names[1] + " · ",
        lambda root: ui.node(compare_title, root) is None,
        attribute="text",
        description="dismissed comparison picker after selecting " + names[1],
    )
    primary = visible_app_node(ui, names[0] + " · ", attribute="text").get("text")
    paired = visible_app_node(ui, names[1] + " · ", attribute="text").get("text")
    gap_prefix = s.fmt("places_guide_distance_gap", "").rstrip(": ")
    difference = visible_app_node(ui, gap_prefix, attribute="text").get("text")
    if distances is not None:
        wanted = s.fmt("places_distance_meters", abs(distances[0] - distances[1]))
        if difference != s.fmt("places_guide_distance_gap", wanted):
            raise AssertionError("Comparison does not show the source distance difference")
    if capture:
        capture("comparison")
    if filter_cuisine:
        cuisine_desc = s.fmt("places_guide_cuisine_desc", "").rstrip(", ")
        choose_cuisine = s["places_choose_cuisine"]
        tap_visible_app_until(
            ui,
            cuisine_desc,
            lambda root: ui.node(choose_cuisine, root) is not None,
            attribute="content-desc",
            description="visible cuisine filter dialog",
        )
        ui.tap_until(
            filter_cuisine,
            lambda root: ui.node(choose_cuisine, root) is None,
            scroll=True,
            description="dismissed cuisine filter dialog for " + filter_cuisine,
        )
        one_of_all = s.fmt("places_guide_count_of", s.plural("places_guide_count", 1, 1), len(names))
        visible_app_node(ui, one_of_all, attribute="text")
        tap_visible_app_until(
            ui,
            cuisine_desc,
            lambda root: ui.node(choose_cuisine, root) is not None,
            attribute="content-desc",
            description="visible cuisine filter dialog to reset",
        )
        ui.tap_until(
            s["places_all_cuisines"],
            lambda root: ui.node(choose_cuisine, root) is None,
            scroll=True,
            description="dismissed cuisine filter dialog after reset",
        )
    second_prefix = s.fmt("places_guide_place_row_desc", 2, names[1], "\x00", "").split("\x00", 1)[0]
    expand_details = s["places_guide_expand_details"]
    second = visible_app_node(ui, second_prefix, attribute="content-desc")
    if expand_details in second.get("content-desc", ""):
        tap_visible_app_until(
            ui,
            second_prefix,
            lambda root: not any(
                node.get("package") == APP
                and names[1] in node.get("content-desc", "")
                and expand_details in node.get("content-desc", "")
                for node in root.iter("node")
            ),
            attribute="content-desc",
            description="expanded restaurant details for " + names[1],
        )
    source = visible_app_node(ui, s.fmt("places_guide_osm_card_desc", names[1]), attribute="content-desc")
    if capture:
        capture("detail")
    return {"nearest": names[0], "selected": names[1], "source_control": source.get("content-desc"),
            "comparison": [primary, paired, difference], "cuisine_filter": filter_cuisine,
            "attribution": visible_app_node(ui, "© OpenStreetMap contributors").get("text")}
