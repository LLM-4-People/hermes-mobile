"""Interact with the real apps and the Android notification shade through accessibility."""

from collections import Counter
from contextlib import contextmanager
import io
import json
import re
import secrets
import subprocess
import sys
from xml.etree import ElementTree

from verification.device.app_strings import AppStrings
from .shade import bounds, current_notice, show_notice
from .stack import eventually

APP = "org.llm4people.hermes"
NTFY = "io.heckel.ntfy"


def focused_window(device):
    windows = device.shell("dumpsys", "window")
    match = re.search(r"\bmCurrentFocus=(Window\{[^}\n]+\})", windows)
    return match.group(1) if match else None


class Screen:
    def __init__(self, device, controls, *, lang=None, strings=None):
        self.device = device
        self.controls = controls
        self._strings = strings if strings is not None else (AppStrings.for_lang(lang) if lang is not None else None)

    @property
    def strings(self):
        resolved = getattr(self, "_strings", None)
        if resolved is None:
            resolved = AppStrings.for_device(getattr(self, "device", None))
            self._strings = resolved
        return resolved

    def snapshot(self):
        tree = self.controls.send("tree")
        return ElementTree.fromstring(tree) if tree else None

    def hierarchy(self):
        def capture():
            root = self.snapshot()
            if root is None:
                return None
            # First-boot SystemUI can cover the freshly launched distributor.
            waiting = self.node("android:id/aerr_wait", root)
            if waiting is not None and any("System UI" in node.get("text", "") for node in root.iter("node")):
                self.tap_node(waiting)
                return None
            return (root,)

        return eventually("Android accessibility hierarchy", capture, timeout=45)[0]

    def node(self, label, root=None):
        if root is None:
            root = self.hierarchy()
        for node in root.iter("node"):
            attrs = node.attrib
            if any(
                attrs.get(key, "").casefold().split() == label.casefold().split()
                for key in ("text", "content-desc", "resource-id")
            ):
                return node
        return None

    def wait(self, label, *, scroll=False, notification_permission=False):
        def find():
            root = self.hierarchy()
            node = self.node(label, root)
            if node is None and notification_permission:
                self.allow_if_prompted(root)
            if node is None and scroll:
                self.scroll(root)
            return node if node is None else (node,)

        return eventually("visible control " + label, find)[0]

    def tap_node(self, node):
        box = bounds(node)
        if len(box) != 4:
            raise ValueError("Control has malformed bounds")
        x1, y1, x2, y2 = box
        if x1 >= x2 or y1 >= y2 or x2 <= 0 or y2 <= 0:
            raise ValueError("Control bounds are empty or off-screen")
        x = (max(0, x1) + x2) // 2
        y = (max(0, y1) + y2) // 2
        self.device.shell("input", "tap", str(x), str(y))

    def tap(self, label, *, scroll=False, notification_permission=False):
        # Labels are static test controls. Never log field contents.
        print("Tap " + label, file=sys.stderr, flush=True)
        self.tap_node(self.wait(label, scroll=scroll, notification_permission=notification_permission))

    def tap_until(
        self,
        label,
        ready,
        *,
        scroll=False,
        notification_permission=False,
        description=None,
        max_taps=None,
    ):
        # Labels are static test controls. Never log field contents.
        print("Tap " + label, file=sys.stderr, flush=True)
        taps = 0

        def attempt():
            nonlocal taps
            root = self.hierarchy()
            result = ready(root)
            if result:
                return (result,)
            node = self.node(label, root)
            if node is None:
                if notification_permission:
                    self.allow_if_prompted(root)
                if scroll:
                    self.scroll(root)
                return None
            if max_taps is None or taps < max_taps:
                self.tap_node(node)
                taps += 1
            return None

        return eventually(description or ("state after tapping " + label), attempt)[0]

    def scroll(self, root):
        for item in root.iter("node"):
            if item.get("scrollable") == "true":
                box = bounds(item)
                if len(box) != 4:
                    continue
                x1, y1, x2, y2 = max(0, box[0]), max(0, box[1]), box[2], box[3]
                if x1 >= x2 or y1 >= y2:
                    continue
                x, h = (x1 + x2) // 2, y2 - y1
                self.device.shell(
                    "input",
                    "swipe",
                    str(x),
                    str(y1 + h * 4 // 5),
                    str(x),
                    str(y1 + h // 3),
                    "260",
                )
                return

    def field(self, label, value):
        print("Fill " + label, file=sys.stderr, flush=True)

        def find():
            root = self.hierarchy()
            for parent in root.iter("node"):
                children = list(parent)
                for index, node in enumerate(children[:-1]):
                    following = children[index + 1]
                    if node.get("text") == label and following.get("class") == "android.widget.EditText":
                        return (following,)
            self.scroll(root)
            return None

        eventually("editor for " + label, find)
        self.type(label, value)
        if self.device.ime_shown():
            self.device.shell("input", "keyevent", "BACK")

    def type(self, label, value):
        if not re.fullmatch(r"[A-Za-z0-9_:/.-]+", value):
            raise ValueError("Unexpected characters in synthetic test input")
        def enter():
            self.hierarchy()
            return self.controls.send("text", label=label, value=value) == b"applied"

        eventually("the named Android editor to accept text", enter)

    def allow_if_prompted(self, root=None):
        if root is None:
            root = self.hierarchy()
        node = self.node("com.android.permissioncontroller:id/permission_allow_button", root)
        if node is None:
            node = self.node(
                "com.google.android.permissioncontroller:id/permission_allow_button",
                root,
            )
        if node is not None:
            self.tap_node(node)


def launch(device, package):
    component = device.shell("cmd", "package", "resolve-activity", "--brief", package).splitlines()[-1]
    if not component.startswith(package + "/"):
        raise RuntimeError("Missing launcher for " + package)
    device.shell("am", "start", "-W", "-n", component)


@contextmanager
def distributor(ui, apk, endpoint):
    d = ui.device
    if d.shell("pm", "list", "packages", NTFY).strip() == "package:" + NTFY:
        raise ValueError("Use a disposable emulator without an existing ntfy installation")
    d.run("install", str(apk))
    try:
        launch(d, NTFY)
        url_field = NTFY + ":id/default_server_dialog_url"

        def open_editor():
            root = ui.hierarchy()
            if ui.node(url_field, root) is not None:
                return True
            preference = ui.node("Default server", root)
            if preference is not None:
                ui.tap_node(preference)
                return False
            settings = ui.node("Settings", root)
            if settings is not None:
                if ui.node("Read the docs", root) is not None:
                    ui.tap_node(settings)
                else:
                    # On compact phones the General preferences start below the visible notification rows.
                    ui.scroll(root)
                return False
            menu = ui.node("More options", root)
            if menu is not None:
                ui.tap_node(menu)
            else:
                ui.allow_if_prompted(root)
            return False

        eventually("ntfy default server editor", open_editor)
        ui.type(url_field, endpoint)
        if d.ime_shown():
            d.shell("input", "keyevent", "BACK")

        def saved():
            root = ui.hierarchy()
            field = ui.node(url_field, root)
            if field is None and ui.node(endpoint, root) is not None:
                return True
            button = ui.node("Save", root)
            if button is not None:
                ui.tap_node(button)
            return False

        eventually("ntfy settings showing the saved loopback server", saved)
        d.shell("input", "keyevent", "BACK")
        ui.wait("Subscribed topics")
        yield
    finally:
        d.run("uninstall", NTFY)


def start_account(ui, label, endpoint, bearer, phrase):
    s = ui.strings
    connect_title = s["profile_connect_title"]
    add_action = s["profile_add_action"]
    choose_other = s["profile_choose_other"]
    profile_lock_btn = s["top_profile_lock"]
    lock_now = s["security_lock_now"]
    close_labels = tuple(dict.fromkeys(
        candidate for candidate in (s.get("ws_close"), "Fermer", "Close") if candidate
    ))

    def form():
        root = ui.hierarchy()
        if ui.node(connect_title, root) is not None:
            return True
        add = ui.node(add_action, root)
        if add is not None:
            ui.tap_node(add)
            return False
        choose = ui.node(choose_other, root)
        if choose is not None:
            ui.tap_node(choose)
            return False
        lock_action = ui.node(lock_now, root)
        if lock_action is not None:
            ui.tap_node(lock_action)
            return False
        lock_btn = ui.node(profile_lock_btn, root)
        if lock_btn is not None:
            ui.tap_node(lock_btn)
            return False
        for close_label in close_labels:
            dismiss = ui.node(close_label, root)
            if dismiss is not None:
                ui.tap_node(dismiss)
                return False
        return False

    eventually("real account creation form", form)
    for name, value in [
        (s["profile_name_label"], label),
        (s["profile_server_label"], endpoint),
        (s["profile_key_label"], bearer),
        (s["profile_passphrase_label"], phrase),
        (s["profile_passphrase_confirm_label"], phrase),
    ]:
        ui.field(name, value)
    ui.tap(s["profile_save_action"], scroll=True)
    ui.wait(s["thread_new_conversation"])


def choose_ntfy(ui):
    s = ui.strings
    transport_title = s.get("push_transport_title", "Service de notification")
    distributor_title = s.get("push_distributor_title", "Choisir un distributeur")
    unified_label = s.get("push_enable_unifiedpush", "Activer avec UnifiedPush")
    no_transport = s.get("push_no_transport", "Aucun service de notification disponible pour ce profil.")
    no_distributor = s.get(
        "push_no_distributor",
        "Installez ou configurez un distributeur UnifiedPush, par exemple ntfy, puis réessayez.",
    )
    attempts = 0
    flags = {}

    def choose():
        nonlocal attempts
        root = ui.hierarchy()
        transport = ui.node(transport_title, root) is not None
        distributor = ui.node(distributor_title, root) is not None
        unified = ui.node(unified_label, root)
        ntfy = ui.node("ntfy\n" + NTFY, root)
        unavailable = (
            transport and ui.node(no_transport, root) is not None
            or distributor and ui.node(no_distributor, root) is not None
        )
        flags.update(transport_picker=transport, distributor_picker=distributor, unifiedpush_option=unified is not None,
                     ntfy_option=ntfy is not None, unavailable=unavailable, transport_taps=attempts)
        if unavailable:
            raise AssertionError("The application reported no available transport or distributor")
        if distributor:
            return (ntfy,) if ntfy is not None else None
        if transport and unified is not None and attempts < 3:
            ui.tap_node(unified)
            attempts += 1
            flags["transport_taps"] = attempts
        elif not transport:
            ui.allow_if_prompted(root)
        return None

    try:
        target = eventually("the enrolled ntfy package in the distributor chooser", choose)[0]
    except (AssertionError, TimeoutError, RuntimeError):
        print("Android enrollment " + json.dumps(flags, sort_keys=True), file=sys.stderr, flush=True)
        raise
    ui.tap_node(target)


def enroll(ui):
    s = ui.strings
    ui.tap(s["nav_atelier"])
    ui.tap(s["ws_notifications"], scroll=True)
    ui.tap(s.get("push_enable_action", "Activer"))
    ui.allow_if_prompted()
    choose_ntfy(ui)


def process_absent(ui):
    device = ui.device
    device.shell("input", "keyevent", "HOME")
    # ActivityManager's documented Task Manager stop removes the process and task while retaining delivery.
    # Check the actual package state and PID immediately before triggering the server event.
    device.shell("cmd", "activity", "stop-app", APP)

    def state():
        package = device.shell("dumpsys", "package", APP)
        if re.search(r"User 0:.*?\bstopped=false\b", package) is None:
            raise AssertionError("Closed-app check requires the installed package to remain runnable")
        proc = subprocess.run(device.command + ["shell", "pidof", APP], capture_output=True)
        if proc.returncode not in {0, 1}:
            raise RuntimeError("PID verification failed")
        return proc.returncode == 1 and not proc.stdout.strip()

    eventually("Hermes process to exit", state)


def thread_screen(ui, root, title, reply):
    send = ui.strings["thread_send"]
    return all(ui.node(label, root) is not None for label in (title, reply, send))


def masked_notice(frame, notice):
    from PIL import Image

    with Image.open(io.BytesIO(frame)) as image:
        source = image.convert("RGB")
        redacted = Image.new("RGB", source.size, "black")
        rects = [notice.heading] + ([notice.body] if notice.body else [])
        for box in rects:
            if box[2] > source.width or box[3] > source.height:
                raise AssertionError("Native notification label lies outside the captured screen")
            redacted.paste(source.crop(box), box)
        left = min(box[0] for box in rects)
        top = min(box[1] for box in rects)
        right = max(box[2] for box in rects)
        bottom = max(box[3] for box in rects)
        return redacted, redacted.crop((left, top, right, bottom))


def open_notice(ui, title, directory=None):
    show_notice(ui, title)
    attempts = Counter()

    def stable(before, stage):
        observed = current_notice(ui, title, attempts)
        if observed == before:
            return True
        if observed is None:
            reason = "unavailable"
        elif observed.focused_window != before.focused_window or observed.accessibility_window != before.accessibility_window:
            reason = "window_changed"
        else:
            reason = "label_moved"
        attempts[stage + "_" + reason] += 1
        return False

    def attempt():
        attempts["attempts"] += 1
        view = current_notice(ui, title, attempts)
        if view is None:
            return False
        if directory:
            frame = ui.device.run("exec-out", "screencap", "-p")
            # Discard frames if focus, accessibility window or either label moved.
            if not stable(view, "after_capture"):
                return False
            image, labels = masked_notice(frame, view)
            if not stable(view, "after_mask"):
                return False
        # Resolve the current SystemUI title again on the phone before injecting a normal touch.
        result = ui.controls.send("tap-notice", title=title, window=view.accessibility_window,
                                  focus=view.focused_window, bounds=view.heading)
        if result != b"tapped":
            # Only bounded, fixed observer statuses may leave the private shell session.
            allowed = {b"focus", b"window_report", b"root", b"package", b"window_id", b"no_title",
                       b"multiple_titles", b"title_window", b"bounds", b"touch"}
            attempts["native_" + (result.decode("ascii") if result in allowed else "unexpected")] += 1
            return False
        if directory:
            with (directory / "native-notification-shade.png").open("xb") as output:
                image.save(output, "PNG")
            with (directory / "native-notification-labels.png").open("xb") as output:
                labels.save(output, "PNG")
        return True

    try:
        eventually("a stable focused notification and a physical tap on its current title", attempt)
    except (AssertionError, TimeoutError, RuntimeError):
        print("Android notification tap " + json.dumps(attempts, sort_keys=True), file=sys.stderr, flush=True)
        raise


def tap_notice(ui, notice_text, owner, phrase, title, reply, evidence=None):
    s = ui.strings
    send = s["thread_send"]
    open_notice(ui, notice_text, evidence)
    # The OS just transferred focus from SystemUI to a new process for the same app.
    # Reacquire Android's accessibility connection to its newly created secure window.
    ui.controls.reconnect()
    profile_label = ui.wait(owner).get("text")
    ui.field(s["profile_passphrase_label"], phrase)
    ui.tap(s["profile_unlock_action"])

    def opened():
        root = ui.hierarchy()
        if not thread_screen(ui, root, title, reply):
            return False
        if thread_screen(ui, root, title, "Absent-" + secrets.token_hex(16)):
            raise AssertionError("A different thread reply matched the opened transcript")
        if evidence:
            visible = [ui.node(label, root).get("text") for label in (title, reply, send)]
            with (evidence / "secure-thread-visible-controls.json").open("x") as output:
                json.dump({"unlocked_profile": profile_label, "visible_thread_controls": visible}, output, ensure_ascii=False, indent=2)
                output.write("\n")
        return True

    eventually("unique title and reply alongside the thread composer", opened)
