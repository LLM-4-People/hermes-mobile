"""Mandatory Android restaurant journey using the packaged APK, real Hermes, and synthetic Overpass records."""

import argparse
from contextlib import ExitStack
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import re
import secrets
import shutil
import sys
import tempfile

from verification.device.installed import installed_apk_sha256
from verification.device.run import Emulator
from verification.gateway_fixture import Gateway, PINNED_REVISION, check_source
from verification.nearby_capture import captures
from verification.nearby_fixture import overpass, PLACES
from verification.nearby_journey import (FAILED, PROMPT, STAGED, USE_LOCATION, enter_message,
                                          lock, open_consent, stage_public_location, unlock, verify_guide, visible_app_node)
from verification.nearby_location import COARSE, answer_android_location, location_access, public_city_fix
from verification.phone_actions import MAPS, admissions, assert_offline, focused, visible
from verification.unifiedpush.editor import editor
from verification.unifiedpush.stack import eventually
from verification.unifiedpush.ui import APP, Screen, launch, start_account


SECOND = "Quel jour vient après lundi ?"
REVOKED = "Comment dit-on merci en italien ?"
OTHER = "Quelle couleur obtient-on en mélangeant le bleu et le jaune ?"
PLAIN = "Voici la réponse témoin du vérificateur."
REPLY = "Voici le guide à consulter.\n\n```hermes-workspace\n" + json.dumps({
    "version": 1, "surface": "page/restaurants", "mode": "replace", "title": "Restaurants",
    "blocks": [{"type": "nearby_places", "category": "restaurant"}]
}, ensure_ascii=False) + "\n```"


def model_reply(record):
    from tests.fakes.fake_llm_provider import Text
    user = next(m["content"] for m in reversed(record["body"]["messages"]) if m["role"] == "user")
    return Text(REPLY if user == PROMPT else PLAIN)


def location_in(messages):
    observations = []
    for message in messages:
        text = message.get("content")
        if isinstance(text, str):
            for marker in re.finditer(r'(?m)^nearby_context=(?=\{)', text):
                area, _ = json.JSONDecoder().raw_decode(text[marker.end():])
                if message["role"] == "user":
                    raise AssertionError("The location was appended to the human message")
                observations.append(area)
    return observations


def verified_turn(gateway, provider, question, previous, *, location):
    def done():
        rows = admissions(gateway)
        if len(rows) > previous + 1:
            raise AssertionError("Android submitted more than one run for one press")
        if len(rows) != previous + 1:
            return None
        run, session = rows[-1]
        state = gateway.request("/v1/runs/" + run)
        if state["status"] in ("failed", "cancelled", "interrupted"):
            raise AssertionError("The authenticated phone run did not complete")
        if state["status"] == "completed":
            return session, state["admission_id"]
        return None

    session, admission_id = eventually("one completed admitted phone run", done)
    records = provider.main_requests()
    if len(records) != previous + 1:
        raise AssertionError("Unexpected number of requests to the controlled model")
    messages = records[-1]["messages"]
    human = next(m["content"] for m in reversed(messages) if m["role"] == "user")
    if human != question:
        raise AssertionError("The original text changed before it reached the provider")
    areas = location_in(messages)
    if len(areas) != int(location):
        raise AssertionError("Unexpected number of current-run location contexts in the model request")
    area = areas[0] if areas else None
    if area and not (set(area) == {"source", "latitude", "longitude", "accuracy_m", "age_s"}
                     and area["source"] == "device" and 3000 <= area["accuracy_m"] <= 8000
                     and area["accuracy_m"] % 1000 == 0 and 0 <= area["age_s"] <= 300):
        raise AssertionError("The provider did not receive a bounded, explicitly approximate location")

    def original_text_stored():
        data = gateway.request(f"/api/sessions/{session}/messages?order=latest&limit=10&visible_only=true")["data"]
        for row in data:
            if row["role"] == "user" and row.get("api_admission_id") == admission_id:
                if row["content"] != question:
                    raise AssertionError("The canonical human message included content that the user did not type")
                return True
        return False
    eventually("canonical human message for this admission", original_text_stored)
    return area


def enter_discussion(ui):
    def opened():
        tree = ui.hierarchy()
        if ui.node("Message à Hermes", tree) is not None:
            return True
        create = ui.node("Nouvelle conversation", tree)
        if create is not None and create.get("clickable") == "true":
            ui.tap_node(create)
        return False
    eventually("the unlocked profile's editable conversation", opened)


def consent_failures(ui, fix, second, names, phrases, check_idle):
    """Start without cached fixes and cross a real app background/profile switch before delivering one."""
    enter_discussion(ui)
    enter_message(ui)
    open_consent(ui)
    ui.tap(USE_LOCATION, scroll=True)
    controller = answer_android_location(ui, allow=False)
    ui.wait(FAILED)
    check_idle()

    # Grant while the system-wide service is off. Permission alone cannot stage or send a coordinate.
    fix.enabled(False)
    open_consent(ui)
    ui.tap(USE_LOCATION, scroll=True)
    if answer_android_location(ui, allow=True) != controller:
        raise AssertionError("Different Android components handled the paired permission decisions")
    ui.wait(FAILED)
    check_idle()

    fix.enabled(True)
    open_consent(ui)
    ui.tap(USE_LOCATION, scroll=True)
    ui.wait("Recherche de la position…")
    ui.device.shell("input", "keyevent", "HOME")
    background = eventually("Android launcher foreground before late fix",
                            lambda: (active if (active := focused(ui.device)) and
                                     not active.startswith(APP + "/") else None))
    fix.publish()
    launch(ui.device, APP)
    ui.wait("Choisir un profil")
    start_account(ui, names[1], second.endpoint, second.key, phrases[1])
    enter_discussion(ui)
    if ui.node(STAGED) is not None:
        raise AssertionError("A late fix appeared in a different profile")
    check_idle()
    enter_message(ui, OTHER)
    ui.tap("Envoyer")
    return controller, background


def map_selected(ui, title, point):
    coordinate = ",".join(format(Decimal(str(point[key])).normalize(), "f") for key in ("lat", "lon"))
    ui.tap_node(visible_app_node(ui, "Voir sur la carte"))
    ui.wait("Ouvrir la carte")
    if not visible(ui, [title, "Coordonnées du lieu · " + coordinate]) or not focused(ui.device).startswith(APP + "/"):
        raise AssertionError("The app did not review the source venue and coordinates before handoff")
    ui.tap("Ouvrir la carte")

    def received():
        component = focused(ui.device)
        if not component.startswith(MAPS + "/"):
            return None
        tree = ui.hierarchy()
        for node in tree.iter("node"):
            if node.get("package") == MAPS:
                for key in ("text", "content-desc"):
                    if coordinate in "".join(node.get(key, "").split()):
                        return component
        for label in ("Skip", "Continue without an account"):
            option = ui.node(label, tree)
            if option is not None and option.get("package") == MAPS:
                ui.tap_node(option)
                break
        return None

    component = eventually("public venue coordinates visible in the actual Maps app", received, timeout=150)
    return {"source_venue": title, "source_coordinates": coordinate, "receiver": component}


def active_gateway(stack, root, source, provider, interpreter):
    from ruamel.yaml import YAML
    gateway = Gateway(root, source, provider.base_url)
    home = Path(gateway.env["HERMES_HOME"])
    shutil.copytree(Path(__file__).resolve().parents[1] / "server/nearby", home / "plugins/mobile_nearby")
    yaml = YAML()
    config = home / "config.yaml"
    data = yaml.load(config.read_text())
    enabled = data.setdefault("plugins", {}).setdefault("enabled", [])
    if "mobile_nearby" not in enabled:
        enabled.append("mobile_nearby")
    with config.open("w") as stream:
        yaml.dump(data, stream)
    gateway.env["HERMES_MOBILE_OVERPASS_URL"] = interpreter
    return stack.enter_context(gateway)


def execute(args):
    from tests.fakes.fake_llm_provider import FakeLLMServer
    device = Emulator(args.serial, args.adb, args.adb_port)
    hashes = []
    for package, apk in zip((APP, APP + ".test"), args.verify_apks, strict=True):
        with apk.open("rb") as stream:
            actual = installed_apk_sha256(device, package)
            if actual != hashlib.file_digest(stream, "sha256").hexdigest():
                raise AssertionError("Different packaged APK installed for " + package)
        hashes.append(actual)
    assert_offline(device)
    if location_access(device, wait_for_install=True):
        raise ValueError("Use a fresh synthetic Hermes app profile without a prior location grant")
    output = args.output
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("Use a fresh evidence directory")
    with tempfile.TemporaryDirectory(prefix="hermes-nearby-") as directory, ExitStack() as stack:
        root = Path(directory)
        interpreter, queries, errors = stack.enter_context(overpass())
        a_model = stack.enter_context(FakeLLMServer(model_reply))
        b_model = stack.enter_context(FakeLLMServer(model_reply))
        first = active_gateway(stack, root / "first", args.source, a_model, interpreter)
        second = active_gateway(stack, root / "second", args.source, b_model, interpreter)
        if first.request("/v1/capabilities")["features"].get("run_scoped_instructions") is not True:
            raise AssertionError("The gateway does not advertise one-run instructions")
        stack.enter_context(device.reverse(first.endpoint))
        stack.enter_context(device.reverse(second.endpoint))
        fix = stack.enter_context(public_city_fix(device))
        # start_account uses ASCII profile labels for its external field editor.
        names = ["Guide-" + secrets.token_hex(3), "Autre-" + secrets.token_hex(3)]
        phrases = [secrets.token_hex(14), secrets.token_hex(14)]
        capture_stack = stack.enter_context(ExitStack())
        capture, images = capture_stack.enter_context(captures(device, output))
        ui = Screen(device, stack.enter_context(editor(device, root)))
        start_account(ui, names[0], first.endpoint, first.key, phrases[0])

        def idle():
            if any((admissions(first), admissions(second), a_model.main_requests(), b_model.main_requests(),
                    queries, errors)):
                raise AssertionError("A failed or stale location started a send or source lookup")
        controller, foreground = consent_failures(ui, fix, second, names, phrases, idle)
        verified_turn(second, b_model, OTHER, 0, location=False)
        if queries:
            raise AssertionError("An unrelated profile sent a restaurant lookup")
        lock(ui)
        unlock(ui, names[0], phrases[0])
        enter_discussion(ui)
        enter_message(ui)
        stage_public_location(ui, fix, system_prompt=False, remember=True)
        if admissions(first) or a_model.main_requests() or queries:
            raise AssertionError("Staging the first fix submitted or queried without Send")
        ui.tap("Envoyer")
        area = verified_turn(first, a_model, PROMPT, 0, location=True)
        eventually("one lookup through the authenticated real nearby plugin", lambda: len(queries) == 1)
        if queries[0][0] != (area["latitude"], area["longitude"]) or errors:
            raise AssertionError("The place source used a different area from the area shared on this turn")
        guide = verify_guide(ui, [p[2] for p in PLACES], capture=capture,
                             distances=[111, 445, 1001], filter_cuisine="Italienne")
        picked = next(p for p in queries[0][1]["elements"] if p["tags"]["name"] == guide["selected"])
        source_url = "https://www.openstreetmap.org/" + picked["type"] + "/" + str(picked["id"])
        ui.tap_node(visible_app_node(ui, guide["source_control"], attribute="content-desc"))
        ui.wait("Ouvrir OpenStreetMap")
        if not visible(ui, [guide["selected"], source_url]) or not focused(device).startswith(APP + "/"):
            raise AssertionError("The review did not show the selected source venue and canonical OSM URL")
        ui.tap("Annuler")
        guide["reviewed_source"] = source_url
        maps = map_selected(ui, guide["selected"], picked["center"])
        launch(device, APP)
        unlock(ui, names[0], phrases[0])
        enter_discussion(ui)

        # Remembered profile consent lets the next explicit Send complete without a second tap.
        enter_message(ui, SECOND)
        fix.publish()
        ui.tap("Envoyer")
        verified_turn(first, a_model, SECOND, 1, location=True)
        if len(queries) != 1 or len(admissions(second)) != 1 or errors:
            raise AssertionError("An ordinary question started another lookup or crossed profiles")
        # Revoking a runtime grant may kill the app. Finish the observer first,
        # then reopen the protected profile just as a returning user would.
        capture_stack.close()
        device.shell("pm", "revoke", "--user", "0", APP, COARSE)
        eventually("Android has removed the coarse grant", lambda: not location_access(device))
        launch(device, APP)
        unlock(ui, names[0], phrases[0])
        enter_discussion(ui)
        enter_message(ui, REVOKED)
        open_consent(ui)
        if len(admissions(first)) != 2 or len(queries) != 1:
            raise AssertionError("Revocation reused a cached coordinate or submitted the waiting draft")
        ui.tap("Envoyer sans position", scroll=True)
        verified_turn(first, a_model, REVOKED, 2, location=False)
        if len(queries) != 1 or errors or location_access(device):
            raise AssertionError("Explicit send without location leaked or re-acquired location")
        report = {"gateway_revision": PINNED_REVISION, "apk_sha256": hashes[0], "test_apk_sha256": hashes[1],
                  "source_data": "synthetic loopback Overpass", "system_permission_controller": controller,
                  "public_test_fix": "Place des Arts, Montréal", "android_mock_providers": ["gps", "fused"],
                  "denial": True, "location_off": True, "late_fix_foreground_activity": foreground, "runtime_revoked": True,
                  "other_profile_received_context": False, "lookups": len(queries), "approved_area": area,
                  "deliberate_first_profile_sends": 3, "deliberate_second_profile_sends": 1,
                  "guide": guide, "map_handoff": maps, "captures": images}
    if set(report["captures"]) != {"guide", "comparison", "detail"}:
        raise AssertionError("Native restaurant evidence was incomplete")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--adb-port", type=int, default=5037)
    parser.add_argument("--verify-apks", type=Path, nargs=2, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.source = args.source.resolve()
    check_source(args.source, PINNED_REVISION)
    sys.path.insert(0, str(args.source))
    report = execute(args)
    with (args.output / "nearby.json").open("x") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
