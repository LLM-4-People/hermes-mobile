"""Find the posted notification in the real Android 13 notification stack."""

import json
import re
import sys
import time

from .stack import eventually


SYSTEM_UI = "com.android.systemui"
STACK = SYSTEM_UI + ":id/notification_stack_scroller"
ROW = SYSTEM_UI + ":id/expandableNotificationRow"
APP_LABEL = "Hermes Mobile"
BODY = "Ouvrez votre profil pour voir les détails."


def bounds(node):
    return tuple(map(int, re.findall(r"-?\d+", node.get("bounds", ""))))


def own_group_expander(ui, root):
    for row in root.iter("node"):
        if row.get("resource-id") != ROW or ui.node(APP_LABEL, row) is None:
            continue
        for resource in ("android:id/expand_button", "android:id/expand_button_container"):
            control = ui.node(resource, row)
            if (control is not None and "collapse" not in control.get("content-desc", "").casefold()
                    and len(bounds(control)) == 4):
                return control
    return None


def presentation(ui, root, title):
    nodes = list(root.iter("node"))
    return {
        "system_ui": any(n.get("package") == SYSTEM_UI for n in nodes),
        "notification_stack": ui.node(STACK, root) is not None,
        "notification_rows": sum(n.get("resource-id") == ROW for n in nodes),
        "scrollable": any(n.get("scrollable") == "true" for n in nodes),
        "empty_shade": ui.node("No notifications", root) is not None,
        "app_label": ui.node(APP_LABEL, root) is not None,
        "event_title": ui.node(title, root) is not None,
        "generic_body": ui.node(BODY, root) is not None,
    }


def shade_focused(device):
    windows = device.shell("dumpsys", "window")
    # Android 13 names its TYPE_NOTIFICATION_SHADE window "NotificationShade".
    # The same SystemUI stack can also render heads-up rows while the launcher owns focus.
    return re.search(r"\bmCurrentFocus=Window\{[^}\n]*\bu\d+\s+"
                     r"(?:com\.android\.systemui/)?NotificationShade\}", windows) is not None


def show_notice(ui, title):
    device = ui.device
    state = {}
    forward = True
    expansions = 0
    scrolls = 0
    reopens = 0
    gestures = 0
    last_gesture = None

    def find():
        nonlocal forward, expansions, scrolls, reopens, gestures, last_gesture
        root = ui.hierarchy()
        state.update(presentation(ui, root, title))
        target = ui.node(title, root)
        state["shade_focused"] = state["system_ui"] and shade_focused(device)
        if not state["shade_focused"]:
            if reopens % 2:
                ui.controls.send("open-shade")
            else:
                device.shell("cmd", "statusbar", "expand-notifications")
            reopens += 1
            return None

        if target is not None:
            return (target,)
        expander = own_group_expander(ui, root)
        if expander is not None and expansions == 0:
            ui.tap_node(expander)
            expansions += 1
            return None

        if ui.controls.send("scroll-shade", forward=forward) == b"scrolled":
            scrolls += 1
            time.sleep(0.3)
            return None

        # AOSP can expose visible rows without advertising an accessibility scroll action.
        # Move only inside the notification list. Reaching the same viewport in a direction reverses it.
        stack = ui.node(STACK, root)
        if stack is None:
            stack = next((n for n in root.iter("node") if n.get("scrollable") == "true"
                          and any(child.get("resource-id") == ROW for child in n.iter("node"))), None)
        if stack is not None and len(bounds(stack)) == 4:
            left, top, right, bottom = bounds(stack)
            viewport = tuple(bounds(n) for n in root.iter("node") if n.get("resource-id") == ROW)
            if (forward, viewport) == last_gesture:
                forward = not forward
            last_gesture = (forward, viewport)
            if bottom - top > 80 and right > left:
                x = (left + right) // 2
                high, low = top + (bottom - top) // 3, top + 3 * (bottom - top) // 4
                y1, y2 = (low, high) if forward else (high, low)
                device.shell("input", "swipe", str(x), str(y1), str(x), str(y2), "340")
                gestures += 1
                return None
        forward = not forward
        return None

    try:
        device.shell("input", "keyevent", "KEYCODE_WAKEUP")
        device.shell("wm", "dismiss-keyguard")
        device.shell("cmd", "statusbar", "expand-notifications")
        ui.controls.reconnect()
        return eventually("the posted event title inside Android's notification shade", find)[0]
    except (AssertionError, TimeoutError, RuntimeError):
        # Emit only known status flags and counts. Platform windows, labels and notification keys are private.
        policy = device.shell("dumpsys", "window", "policy")
        power = device.shell("dumpsys", "power")
        state.update(
            awake="mWakefulness=Awake" in power,
            keyguard_showing=bool(re.search(r"(?:mKeyguardShowing|showing)=(?:true|1)\b", policy)),
            shade_focused=shade_focused(device),
            hermes_active_notifications=sum(
                len(parts) >= 5 and parts[1] == "org.llm4people.hermes"
                for parts in (line.split("|") for line in device.shell("cmd", "notification", "list").splitlines())
            ),
            panel_open_attempts=reopens + 1, native_scrolls=scrolls, list_swipes=gestures, group_expansions=expansions,
        )
        print("Android notification shade " + json.dumps(state, sort_keys=True), file=sys.stderr, flush=True)
        raise
