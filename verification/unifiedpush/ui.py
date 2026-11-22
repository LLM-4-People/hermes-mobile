"""Interact with the real apps and the Android notification shade through accessibility."""

from contextlib import contextmanager
import io
import json
import re
import secrets
import subprocess
import sys
from xml.etree import ElementTree

from .shade import show_notice
from .stack import eventually

APP = "org.llm4people.hermes"
NTFY = "io.heckel.ntfy"


class Screen:
    def __init__(self, device, controls):
        self.device = device
        self.controls = controls

    def hierarchy(self):
        def capture():
            tree = self.controls.send("tree")
            if not tree:
                return None
            root = ElementTree.fromstring(tree)
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
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        self.device.shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def tap(self, label, *, scroll=False, notification_permission=False):
        # Labels are static test controls. Never log field contents.
        print("Tap " + label, file=sys.stderr, flush=True)
        self.tap_node(self.wait(label, scroll=scroll, notification_permission=notification_permission))

    def scroll(self, root):
        for item in root.iter("node"):
            if item.get("scrollable") == "true":
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", item.get("bounds")))
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
        if "mInputShown=true" in self.device.shell("dumpsys", "input_method"):
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
        if "mInputShown=true" in d.shell("dumpsys", "input_method"):
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
    def form():
        root = ui.hierarchy()
        if ui.node("Connecter Hermes", root) is not None:
            return True
        add = ui.node("Ajouter un profil", root)
        if add is not None:
            ui.tap_node(add)
        else:
            choose = ui.node("Choisir un autre profil", root)
            if choose is not None:
                ui.tap_node(choose)
        return False

    eventually("real account creation form", form)
    for name, value in [
        ("Nom du profil", label),
        ("Adresse du serveur", endpoint),
        ("Clé d’accès Hermes", bearer),
        ("Phrase secrète du profil", phrase),
        ("Confirmer la phrase secrète", phrase),
    ]:
        ui.field(name, value)
    ui.tap("Enregistrer le profil", scroll=True)
    ui.wait("Nouvelle conversation")


def choose_ntfy(ui):
    attempts = 0
    flags = {}

    def choose():
        nonlocal attempts
        root = ui.hierarchy()
        transport = ui.node("Service de notification", root) is not None
        distributor = ui.node("Choisir un distributeur", root) is not None
        unified = ui.node("Activer avec UnifiedPush", root)
        ntfy = ui.node("ntfy\n" + NTFY, root)
        unavailable = (
            transport and ui.node("Aucun service de notification disponible pour ce profil.", root) is not None
            or distributor and ui.node(
                "Installez ou configurez un distributeur UnifiedPush, par exemple ntfy, puis réessayez.", root
            ) is not None
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
    ui.tap("Atelier")
    ui.tap("Notifications", scroll=True)
    ui.tap("Activer")
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
    return all(ui.node(label, root) is not None for label in (title, reply, "Envoyer"))


def capture_notice(ui, title, directory):
    from PIL import Image

    root = ui.hierarchy()
    heading = ui.node(title, root)
    if heading is None:
        raise AssertionError("The expected notification left the shade before capture")
    controls = [heading]
    body = ui.node("Ouvrez votre profil pour voir les détails.", root)
    if body is not None:
        controls.append(body)
    # Only retain pixels within these two generic native label bounds. Other notifications
    # and the status bar are never written to disk.
    with Image.open(io.BytesIO(ui.device.run("exec-out", "screencap", "-p"))) as image:
        source = image.convert("RGB")
        redacted = Image.new("RGB", source.size, "black")
        rects = [tuple(map(int, re.findall(r"-?\d+", control.get("bounds")))) for control in controls]
        for box in rects:
            redacted.paste(source.crop(box), box)
        left = min(box[0] for box in rects)
        top = min(box[1] for box in rects)
        right = max(box[2] for box in rects)
        bottom = max(box[3] for box in rects)
        with (directory / "native-notification-shade.png").open("xb") as output:
            redacted.save(output, "PNG")
        with (directory / "native-notification-labels.png").open("xb") as output:
            redacted.crop((left, top, right, bottom)).save(output, "PNG")


def tap_notice(ui, notice_text, owner, phrase, title, reply, evidence=None):
    target = show_notice(ui, notice_text)
    if evidence:
        capture_notice(ui, notice_text, evidence)
    ui.tap_node(target)
    # The OS just transferred focus from SystemUI to a new process for the same app.
    # Reacquire Android's accessibility connection to its newly created secure window.
    ui.controls.reconnect()
    profile_label = ui.wait(owner).get("text")
    ui.field("Phrase secrète du profil", phrase)
    ui.tap("Déverrouiller")

    def opened():
        root = ui.hierarchy()
        if not thread_screen(ui, root, title, reply):
            return False
        if thread_screen(ui, root, title, "Absent-" + secrets.token_hex(16)):
            raise AssertionError("A different thread reply matched the opened transcript")
        if evidence:
            visible = [ui.node(label, root).get("text") for label in (title, reply, "Envoyer")]
            with (evidence / "secure-thread-visible-controls.json").open("x") as output:
                json.dump({"unlocked_profile": profile_label, "visible_thread_controls": visible}, output, ensure_ascii=False, indent=2)
                output.write("\n")
        return True

    eventually("unique title and reply alongside the thread composer", opened)
