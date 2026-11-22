"""Interact with the real apps and the Android notification shade through accessibility."""

from contextlib import contextmanager
import re
import secrets
import subprocess
from xml.etree import ElementTree

from .stack import eventually

APP = "org.llm4people.hermes"
NTFY = "io.heckel.ntfy"


class Screen:
    def __init__(self, device):
        self.device = device
        self.tmp = "/data/local/tmp/hm-push-" + secrets.token_hex(8) + ".xml"

    def hierarchy(self):
        self.device.shell("uiautomator", "dump", self.tmp)
        try:
            return ElementTree.fromstring(self.device.run("exec-out", "cat", self.tmp))
        finally:
            self.device.shell("rm", "-f", self.tmp)

    def node(self, label, root=None):
        if root is None:
            root = self.hierarchy()
        for node in root.iter("node"):
            attrs = node.attrib
            if any(
                attrs.get(key, "").casefold() == label.casefold()
                for key in ("text", "content-desc", "resource-id")
            ):
                return node
        return None

    def wait(self, label, *, scroll=False):
        def find():
            root = self.hierarchy()
            node = self.node(label, root)
            if node is None and scroll:
                self.scroll(root)
            return node if node is None else (node,)

        return eventually("visible control " + label, find)[0]

    def tap_node(self, node):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        self.device.shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def tap(self, label, *, scroll=False):
        self.tap_node(self.wait(label, scroll=scroll))

    def scroll(self, root):
        for item in root.iter("node"):
            if item.get("scrollable") == "true":
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", item.get("bounds")))
                x, h = (x1 + x2) // 2, y2 - y1
                self.device.shell("input", "swipe", str(x), str(y1 + h * 4 // 5), str(x), str(y1 + h // 3), "260")
                return

    def field(self, label, value):
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

        self.tap_node(eventually("editor for " + label, find)[0])
        self.type(value)
        if "mInputShown=true" in self.device.shell("dumpsys", "input_method"):
            self.device.shell("input", "keyevent", "BACK")

    def type(self, value):
        if not re.fullmatch(r"[A-Za-z0-9_:/.-]+", value):
            raise ValueError("Unexpected characters in synthetic test input")
        self.device.shell("input", "text", value)

    def allow_if_prompted(self):
        root = self.hierarchy()
        node = self.node("com.android.permissioncontroller:id/permission_allow_button", root)
        if node is None:
            node = self.node("com.google.android.permissioncontroller:id/permission_allow_button", root)
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
        ui.tap("More options")
        ui.tap("Settings")
        ui.tap("Default server")
        ui.tap(NTFY + ":id/default_server_dialog_url")
        ui.type(endpoint)
        ui.tap(NTFY + ":id/default_server_dialog_action_save")
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
    ui.tap("Activer avec UnifiedPush")
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


def tap_notice(ui, owner, phrase, title):
    device = ui.device
    device.shell("cmd", "statusbar", "expand-notifications")
    ui.tap("Hermès a terminé")
    ui.wait(owner)
    ui.field("Phrase secrète du profil", phrase)
    ui.tap("Déverrouiller")
    ui.wait(title)
