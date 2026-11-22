"""Public Android controls for one French, user-approved nearby restaurant conversation."""

import re

from verification.nearby_location import answer_android_location
from verification.unifiedpush.stack import eventually
from verification.unifiedpush.ui import APP


PROMPT = "Quels restaurants se trouvent près de moi ?"
CONSENT = "Ajouter une zone à ce message ?"
USE_LOCATION = "Utiliser ma position approximative"
STAGED = "Position approximative jointe"
FAILED = "Position indisponible · choisir un quartier ou continuer sans position"
HEADING = "Restaurants dans la zone"


def enter_message(ui, prompt=PROMPT):
    ui.wait("Message à Hermes")
    if ui.controls.send("text", label="Message à Hermes", value=prompt) != b"applied":
        raise AssertionError("The public Android composer did not accept the French request")
    if "mInputShown=true" in ui.device.shell("dumpsys", "input_method"):
        ui.device.shell("input", "keyevent", "BACK")


def open_consent(ui):
    ui.tap("Envoyer")
    ui.wait(CONSENT)
    tree = ui.hierarchy()
    disclosed = [node.get("text", "") for node in tree.iter("node") if node.get("package") == APP]
    if not any("Hermes" in text and "OpenStreetMap" in text for text in disclosed):
        raise AssertionError("The message did not disclose both location recipients before consent")
    return tree


def stage_public_location(ui, fix, *, system_prompt=True):
    """Stage the approximate fix. The caller must explicitly tap Envoyer a second time."""
    open_consent(ui)
    fix.publish()
    ui.tap(USE_LOCATION, scroll=True)
    if system_prompt:
        answer_android_location(ui, allow=True)
    ui.wait(STAGED)


def unlock(ui, label, phrase):
    ui.tap(label)
    ui.field("Phrase secrète du profil", phrase)
    ui.tap("Déverrouiller")


def lock(ui):
    ui.tap("Autres actions")
    ui.tap("Verrouiller le profil")
    ui.wait("Choisir un profil")


def visible_app_node(ui, fragment, *, attribute=None):
    """Find a rendered, on-screen bit of guide data, revealing it by normal vertical scrolling."""
    def find():
        root = ui.hierarchy()
        for node in root.iter("node"):
            if node.get("package") != APP:
                continue
            labels = (node.get(attribute, ""),) if attribute else (node.get("text", ""), node.get("content-desc", ""))
            if any(fragment.casefold() in label.casefold() for label in labels):
                box = [int(value) for value in re.findall(r'-?\d+', node.get("bounds", ""))]
                if len(box) == 4 and box[2] > box[0] and box[3] > box[1]:
                    return (node,)
        ui.scroll(root)
        return False

    return eventually("visible restaurant guide element " + fragment, find, timeout=45)[0]


def verify_guide(ui, names, *, capture=None):
    """Select observed source records and compare the rendered venue and map/source controls."""
    if len(names) < 2:
        raise ValueError("The guide demonstration needs two source records")
    visible_app_node(ui, HEADING)
    compass = visible_app_node(ui, "Repères des restaurants, nord en haut", attribute="content-desc")
    if names[0] not in compass.get("content-desc", ""):
        raise AssertionError("The compass does not point to the nearest returned restaurant")
    if capture:
        capture("guide")
    second = visible_app_node(ui, names[1] + ", environ ", attribute="content-desc")
    if "Afficher les détails" not in second.get("content-desc", ""):
        raise AssertionError("The second source row has no active detail action")
    ui.tap_node(second)
    source = visible_app_node(ui, "Fiche OpenStreetMap de " + names[1], attribute="content-desc")
    if capture:
        capture("detail")
    return {"nearest": names[0], "selected": names[1], "source_control": source.get("content-desc"),
            "attribution": visible_app_node(ui, "© OpenStreetMap contributors").get("text")}
