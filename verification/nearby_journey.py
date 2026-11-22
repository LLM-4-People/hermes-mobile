"""Public Android controls for one French, user-approved nearby restaurant conversation."""

import json
import re
import sys

from verification.nearby_location import answer_android_location, location_access
from verification.phone_actions import focused
from verification.unifiedpush.stack import eventually
from verification.unifiedpush.ui import APP


PROMPT = "Quels restaurants se trouvent près de moi ?"
CONSENT = "Ajouter une zone à ce message ?"
USE_LOCATION = "Utiliser ma position approximative"
STAGED = "Position approximative jointe"
FAILED = "Position indisponible · choisir un quartier ou continuer sans position"
HEADING = "Restaurants dans la zone"


def dismiss_ime(ui):
    if ui.device.ime_shown():
        ui.device.shell("input", "keyevent", "BACK")
        eventually("soft keyboard to hide", lambda: not ui.device.ime_shown(), timeout=15)


def enter_message(ui, prompt=PROMPT):
    ui.wait("Message à Hermes")
    if ui.controls.send("text", label="Message à Hermes", value=prompt) != b"applied":
        raise AssertionError("The public Android composer did not accept the French request")
    dismiss_ime(ui)


def open_consent(ui):
    dismiss_ime(ui)
    taps = 0
    ime_dismissals = 0
    last_send_bounds = None

    def opened():
        nonlocal taps, ime_dismissals, last_send_bounds
        root = ui.hierarchy()
        if ui.node(CONSENT, root) is not None:
            return (root,)
        if ui.device.ime_shown():
            ui.device.shell("input", "keyevent", "BACK")
            ime_dismissals += 1
            return None
        send = ui.node("Envoyer", root)
        if send is not None:
            last_send_bounds = send.get("bounds")
            ui.tap_node(send)
            taps += 1
        return None

    print("Tap Envoyer", file=sys.stderr, flush=True)
    try:
        tree = eventually("visible control " + CONSENT, opened)[0]
    except AssertionError:
        root = ui.hierarchy()
        packages = sorted({node.get("package", "") for node in root.iter("node") if node.get("package")})
        print("Android open_consent " + json.dumps({
            "focused": focused(ui.device),
            "packages": packages,
            "ime_shown": ui.device.ime_shown(),
            "taps": taps,
            "ime_dismissals": ime_dismissals,
            "send_bounds": last_send_bounds,
            "has_send": ui.node("Envoyer", root) is not None,
            "has_failed": ui.node(FAILED, root) is not None,
            "has_searching": ui.node("Recherche de la position…", root) is not None,
            "has_lock_screen": ui.node("Choisir un profil", root) is not None,
            "has_composer": ui.node("Message à Hermes", root) is not None,
            "location_grant": location_access(ui.device),
        }, sort_keys=True), file=sys.stderr, flush=True)
        raise
    disclosed = [node.get("text", "") for node in tree.iter("node") if node.get("package") == APP]
    if not any("Hermes" in text and "OpenStreetMap" in text for text in disclosed):
        raise AssertionError("The message did not disclose both location recipients before consent")
    return tree


def choose_consent(ui, label):
    ui.tap_until(
        label,
        lambda root: ui.node(CONSENT, root) is None,
        scroll=True,
        description="dismissed location consent dialog after choosing " + label,
    )


def stage_public_location(ui, fix, *, system_prompt=True, remember=False):
    """Stage the approximate fix. The caller must explicitly tap Envoyer a second time."""
    open_consent(ui)
    if remember:
        ui.tap("Utiliser ma position pour mes prochains messages sur ce profil", scroll=True)
    fix.publish()
    choose_consent(ui, USE_LOCATION)
    if system_prompt:
        answer_android_location(ui, allow=True)
    ui.wait(STAGED)


def unlock(ui, label, phrase):
    ui.tap_until(
        label,
        lambda root: ui.node("Phrase secrète du profil", root) is not None,
        scroll=True,
        description="profile unlock form for " + label,
    )
    ui.field("Phrase secrète du profil", phrase)
    ui.tap_until(
        "Déverrouiller",
        lambda root: ui.node("Déverrouiller", root) is None,
        scroll=True,
        description="the profile to finish unlocking",
    )


def lock(ui):
    ui.tap_until(
        "Autres actions",
        lambda root: ui.node("Autres actions", root) is None or ui.node("Verrouiller le profil", root) is not None,
        description="overflow menu opened from Autres actions",
    )
    ui.tap_until(
        "Verrouiller le profil",
        lambda root: ui.node("Choisir un profil", root) is not None,
        scroll=True,
        description="visible control Choisir un profil",
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
    visible_app_node(ui, HEADING)
    compass = visible_app_node(ui, "Repères des restaurants, nord en haut", attribute="content-desc")
    if names[0] not in compass.get("content-desc", ""):
        raise AssertionError("The compass does not point to the nearest returned restaurant")
    if capture:
        capture("guide")
    if distances is not None:
        if len(distances) != len(names):
            raise ValueError("A distance is required for each source record")
        for name, distance in zip(names[:2], distances[:2], strict=True):
            visible_app_node(ui, f"{name}, environ {distance} m du centre", attribute="content-desc")
    # Compare without re-fetching. Both facts must be observed as rendered text.
    compare_title = "Comparer " + names[0] + " avec…"
    tap_visible_app_until(
        ui,
        "Comparer deux restaurants",
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
    difference = visible_app_node(ui, "Écart des distances au centre", attribute="text").get("text")
    if distances is not None:
        wanted = str(abs(distances[0] - distances[1])) + " m"
        if difference != "Écart des distances au centre : " + wanted:
            raise AssertionError("Comparison does not show the source distance difference")
    if capture:
        capture("comparison")
    if filter_cuisine:
        tap_visible_app_until(
            ui,
            "Filtrer par cuisine",
            lambda root: ui.node("Choisir une cuisine", root) is not None,
            attribute="content-desc",
            description="visible cuisine filter dialog",
        )
        ui.tap_until(
            filter_cuisine,
            lambda root: ui.node("Choisir une cuisine", root) is None,
            scroll=True,
            description="dismissed cuisine filter dialog for " + filter_cuisine,
        )
        visible_app_node(ui, "1 adresse sur " + str(len(names)), attribute="text")
        tap_visible_app_until(
            ui,
            "Filtrer par cuisine",
            lambda root: ui.node("Choisir une cuisine", root) is not None,
            attribute="content-desc",
            description="visible cuisine filter dialog to reset",
        )
        ui.tap_until(
            "Toutes les cuisines",
            lambda root: ui.node("Choisir une cuisine", root) is None,
            scroll=True,
            description="dismissed cuisine filter dialog after reset",
        )
    second = visible_app_node(ui, names[1] + ", environ ", attribute="content-desc")
    if "Afficher les détails" in second.get("content-desc", ""):
        tap_visible_app_until(
            ui,
            names[1] + ", environ ",
            lambda root: not any(
                node.get("package") == APP
                and names[1] in node.get("content-desc", "")
                and "Afficher les détails" in node.get("content-desc", "")
                for node in root.iter("node")
            ),
            attribute="content-desc",
            description="expanded restaurant details for " + names[1],
        )
    source = visible_app_node(ui, "Fiche OpenStreetMap de " + names[1], attribute="content-desc")
    if capture:
        capture("detail")
    return {"nearest": names[0], "selected": names[1], "source_control": source.get("content-desc"),
            "comparison": [primary, paired, difference], "cuisine_filter": filter_cuisine,
            "attribution": visible_app_node(ui, "© OpenStreetMap contributors").get("text")}
