"""Interact with the real apps and the Android notification shade through accessibility."""

from contextlib import contextmanager
import re
import secrets
import subprocess
import sys
from xml.etree import ElementTree

from .stack import eventually

APP = "org.llm4people.hermes"
NTFY = "io.heckel.ntfy"


class Screen:
    def __init__(self, device, editor_jar):
        self.device = device
        self.editor_jar = editor_jar
        self.tmp = "/data/local/tmp/hm-push-" + secrets.token_hex(8) + ".xml"

    def hierarchy(self):
        def capture():
            self.device.shell("uiautomator", "dump", self.tmp)
            if self.device.shell("sh", "-c", f"'test -s {self.tmp} && echo ready || true'") != "ready":
                return None
            try:
                root = ElementTree.fromstring(self.device.run("exec-out", "cat", self.tmp))
                # Freshly rebooted, busy AOSP images can cover the first permission dialog with a SystemUI ANR.
                waiting = self.node("android:id/aerr_wait", root)
                if waiting is not None and any("System UI" in node.get("text", "") for node in root.iter("node")):
                    self.tap_node(waiting)
                    return None
                return (root,)
            finally:
                self.device.shell("rm", "-f", self.tmp)

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
        path = self.editor_jar.removesuffix(".jar") + ".txt"
        try:
            self.device.run("shell", "sh", "-c", f"'umask 077 && cat > {path}'", data=(label + "\n" + value).encode())
            result = self.device.shell(
                "uiautomator",
                "runtest",
                self.editor_jar,
                "-c",
                "org.llm4people.hermes.verification.FocusedText",
                "-e",
                "valueFile",
                path,
                "-s",
            )
            if "OK (1 test)" not in result:
                raise AssertionError("Android accessibility did not set the focused editor: " + result[:1600])
        finally:
            self.device.shell("rm", "-f", path)

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
        ui.allow_if_prompted()
        ui.tap("More options", notification_permission=True)
        ui.tap("Settings")
        ui.tap("Default server")
        url_field = NTFY + ":id/default_server_dialog_url"
        ui.wait(url_field)
        ui.type(url_field, endpoint)
        if "mInputShown=true" in d.shell("dumpsys", "input_method"):
            d.shell("input", "keyevent", "BACK")
        ui.tap("Save")
        ui.wait(endpoint)
        yield
    finally:
        d.run("uninstall", NTFY)


def start_account(ui, label, endpoint, bearer, phrase):
    root = ui.hierarchy()
    add = ui.node("Ajouter un profil", root)
    if add is not None:
        ui.tap_node(add)
    ui.wait("Connecter Hermes")
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


def enroll(ui):
    ui.tap("Atelier")
    ui.tap("Notifications", scroll=True)
    ui.tap("Activer")
    ui.allow_if_prompted()
    ui.tap("Activer avec UnifiedPush", notification_permission=True)
    ui.wait("Choisir un distributeur")
    ui.tap("ntfy\n" + NTFY)


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


def tap_notice(ui, notice_text, owner, phrase, title, reply):
    device = ui.device
    device.shell("cmd", "statusbar", "expand-notifications")
    ui.tap(notice_text)
    ui.wait(owner)
    ui.field("Phrase secrète du profil", phrase)
    ui.tap("Déverrouiller")

    def opened():
        root = ui.hierarchy()
        if not thread_screen(ui, root, title, reply):
            return False
        if thread_screen(ui, root, title, "Absent-" + secrets.token_hex(16)):
            raise AssertionError("A different thread reply matched the opened transcript")
        return True

    eventually("unique title and reply alongside the thread composer", opened)
