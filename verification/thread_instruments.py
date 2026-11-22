"""Verify real fork-generated instrument revisions, native trials and thread-local pins on Android.

Install matching debug and androidTest APKs on a disposable Google APIs emulator, then run
python verification/thread_instruments.py --source /path/to/pinned/hermes --serial emulator-5554
    --output /tmp/thread-instrument --verify-apks /path/to/app.apk /path/to/test.apk
from the fork's Python environment. The host restores the previous display geometry and settings.
"""

import argparse
from contextlib import ExitStack, contextmanager
import hashlib
import json
from pathlib import Path
import re
import secrets
import struct
import sys
import tempfile
import time

from conversation_cards import admission
from device.installed import installed_apk_sha256
from device.run import APP, DEVICE_DIR, DRIVER, Emulator, cleanup_all, collect_captures
from gateway_fixture import Gateway, PINNED_REVISION, check_source
from live_api import transcript
from instrument_walkthrough import write_walkthrough


FIRST = "Relis le calcul dans facture.py."
CREATE = "Crée un outil que je pourrai réutiliser dans cette discussion à partir de notre échange."
GOAL = "Je veux une fiche pour relire facture.py."
FEEDBACK = "Vérifiez le calcul du total avant l’arrondi."
REVISE = "Ajoute une priorité à mon outil de revue."
OTHER = "Prépare ma réunion du lundi."
TITLE = "Relecture de code"
FORM = "Vérifier la modification"
CAPTURES = {
    "18-creer-outil.png": "Le menu du fil permet de demander un outil à partir de l’échange.",
    "19-demande-outil.png": "La demande de création est modifiable avant son envoi.",
    "20-essai-discussion.png": "Le formulaire proposé est ouvert en essai dans la discussion.",
    "21-brouillon-outil.png": "Le formulaire prépare un brouillon que la personne peut modifier.",
    "22-comparaison-outil.png": "La comparaison décrit l’ajout du champ Priorité.",
    "22-version-epinglee.png": "La version épinglée conserve son formulaire à un champ.",
    "22-version-proposee.png": "La version proposée peut être essayée avant son adoption.",
    "23-outil-adopte.png": "La version adoptée présente le nouveau champ éditable.",
    "24-outil-annule.png": "L’annulation rétablit la première version épinglée.",
    "25-outil-grand-ecran.png": "La discussion et le formulaire éditable occupent deux panneaux.",
}


def proposal(revised):
    fields = [{"id": "fichier", "label": "Fichier", "kind": "text", "required": True}]
    if revised:
        fields.append({"id": "priorite", "label": "Priorité", "kind": "text"})
    form = {"type": "form", "title": FORM, "fields": fields,
            "prompt": "Relire ce changement par priorité." if revised else "Relire ce changement."}
    layout = {"version": 1, "surface": "page/revue-code", "mode": "replace",
              "title": TITLE, "blocks": [form]}
    lead = "La priorité est prête à comparer." if revised else "Voici votre outil de relecture."
    return lead + "\n\n```hermes-workspace\n" + json.dumps(layout, ensure_ascii=False) + "\n```"


def response(record):
    from tests.fakes.fake_llm_provider import Text
    messages = record["body"].get("messages", ())
    prompt = next((row.get("content") for row in reversed(messages) if row.get("role") == "user"), "")
    if prompt == CREATE + "\n\n" + GOAL:
        return Text(proposal(False))
    replies = {FIRST: FEEDBACK, REVISE: proposal(True), OTHER: "La réunion est prête."}
    return Text(replies.get(prompt, "Aucune proposition demandée."))


def verify_history(gateway, provider):
    history = {}
    for session in gateway.request("/api/sessions?source=api_server")["data"]:
        turns = [(role, text) for role, text in transcript(gateway, session["id"], limit=100)
                 if role in {"user", "assistant"}]
        if turns:
            history[session["id"]] = turns
    if len(history) != 2:
        raise AssertionError("The review and unrelated conversation did not have separate histories")
    owner = next(sid for sid, turns in history.items() if turns[0] == ("user", FIRST))
    other = next(sid for sid in history if sid != owner)
    review = history[owner]
    request = CREATE + "\n\n" + GOAL
    if len(review) != 6 or review[2] != ("user", request):
        raise AssertionError("The native menu request or its appended goal was lost")
    expected = [(owner, FIRST, FEEDBACK), (owner, request, proposal(False)),
                (owner, REVISE, proposal(True)), (other, OTHER, "La réunion est prête.")]
    receipts = []
    for index, (sid, prompt, reply) in enumerate(expected):
        ledger = admission(gateway, index)
        if ledger is None or ledger[1] != sid:
            raise AssertionError("Run admitted against the wrong canonical conversation")
        run_id = ledger[0]
        run = gateway.request("/v1/runs/" + run_id)
        if run["status"] != "completed" or run["output"] != reply:
            raise AssertionError("The canonical model reply does not match the rendered scenario")
        receipts.append({"conversation": sid, "run_id": run_id, "status": run["status"],
                         "user": prompt, "assistant": reply})
    for sid, turns in history.items():
        expected_rows = [(role, value) for thread, prompt, reply in expected if thread == sid
                         for role, value in (("user", prompt), ("assistant", reply))]
        if turns != expected_rows:
            raise AssertionError("Unexpected user or assistant message in the canonical transcript")
    if admission(gateway, len(expected)) is not None or len(provider.main_requests()) != len(expected):
        raise AssertionError("A local trial, pin, draft or revision decision triggered an extra model request")
    for body in provider.main_requests():
        wire = json.dumps(body, ensure_ascii=False)
        if any(value in wire for value in ("facture/revue-proposee.py", "facture/revue-epinglee.py")):
            raise AssertionError("An unsent comparison field reached the model")
    return receipts


def set_viewport(device, *, wide):
    rotation = "1" if wide else "0"
    width, height = (1200, 720) if wide else (320, 640)
    device.shell("wm", "size", f"{min(width, height)}x{max(width, height)}")
    device.shell("wm", "density", "160")
    device.shell("settings", "put", "system", "user_rotation", rotation)
    device.shell("wm", "user-rotation", "lock", rotation)
    target = f"-w{width}dp-h{height}dp-"
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if target in device.shell("am", "get-config") and device.shell("wm", "user-rotation") == "lock " + rotation:
            return
        time.sleep(0.2)
    raise AssertionError(f"The emulator did not apply the requested {width}×{height} viewport")


@contextmanager
def viewport(device):
    overrides = {}
    for setting in ("size", "density"):
        old = device.shell("wm", setting)
        override = re.search(r"^Override (?:size|density): ([\dx]+)$", old, re.MULTILINE)
        overrides[setting] = override.group(1) if override else "reset"
    rotation = device.shell("wm", "user-rotation").split()
    saved_rotation = device.shell("settings", "get", "system", "user_rotation")
    if not re.fullmatch(r"free|lock [0-3]", " ".join(rotation)):
        raise ValueError("Unrecognized current Android display rotation")
    try:
        set_viewport(device, wide=False)
        yield
    finally:
        cleanup_all(lambda: device.shell("wm", "size", overrides["size"]),
                    lambda: device.shell("wm", "density", overrides["density"]),
                    lambda: device.setting("user_rotation", saved_rotation),
                    lambda: device.shell("wm", "user-rotation", *rotation))


def journey(args, gateway):
    device = Emulator(args.serial, args.adb, args.adb_port)
    if device.shell("getprop", "ro.kernel.qemu") != "1" or DRIVER not in device.shell("pm", "list", "instrumentation"):
        raise ValueError("Install the current app and androidTest APK on a disposable emulator")
    hashes = [installed_apk_sha256(device, pkg) for pkg in (APP, APP + ".test")]
    if args.verify_apks:
        for path, actual in zip(args.verify_apks, hashes, strict=True):
            with path.open("rb") as apk:
                if hashlib.file_digest(apk, "sha256").hexdigest() != actual:
                    raise ValueError("Installed artifact differs from " + path.name)
    token = secrets.token_hex(5)
    folder = DEVICE_DIR + "/" + token
    spec = {"run": token, "phrase": secrets.token_hex(16), "endpoint": gateway.endpoint,
            "key": gateway.key, "first": FIRST, "template": CREATE, "goal": GOAL, "revision": REVISE, "other": OTHER,
            "instrumentTitle": TITLE, "form": FORM}
    with device.reverse(gateway.endpoint), device.appearance(), viewport(device):
        device.shell("run-as", APP, "mkdir", "-p", folder)
        device.run("shell", "run-as", APP, "sh", "-c", f"'umask 077 && cat > {DEVICE_DIR}/fixture.json'",
                   data=json.dumps(spec, ensure_ascii=False).encode())
        try:
            device.phase("instrument")
            device.shell("am", "force-stop", APP)
            set_viewport(device, wide=True)
            device.phase("instrument-wide")
        finally:
            cleanup_all(lambda: device.shell("run-as", APP, "rm", "-f", DEVICE_DIR + "/fixture.json"),
                        lambda: collect_captures(device, folder, args.output))
    missing = CAPTURES.keys() - {p.name for p in args.output.glob("*.png")}
    if missing:
        raise AssertionError("Missing native captures: " + ", ".join(sorted(missing)))
    captures = {}
    for name in sorted(CAPTURES):
        with (args.output / name).open("rb") as capture:
            header = capture.read(24)
            size = struct.unpack(">II", header[16:24])
            expected = (1200, 720) if name == "25-outil-grand-ecran.png" else (320, 640)
            if size != expected:
                raise AssertionError(f"{name} rendered at {size}, expected {expected}")
            capture.seek(0)
            captures[name] = {"caption": CAPTURES[name], "width": size[0], "height": size[1],
                              "sha256": hashlib.file_digest(capture, "sha256").hexdigest()}
    return {"apk_sha256": hashes[0], "test_apk_sha256": hashes[1], "captures": captures}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--revision", default=PINNED_REVISION)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--adb-port", type=int, default=5037)
    parser.add_argument("--verify-apks", nargs=2, type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    source = args.source.resolve()
    check_source(source, args.revision)
    sys.path.insert(0, str(source))
    from tests.fakes.fake_llm_provider import FakeLLMServer

    args.output.mkdir(parents=True, exist_ok=False)
    with ExitStack() as stack:
        root = stack.enter_context(tempfile.TemporaryDirectory(prefix="hm-thread-instrument-"))
        provider = stack.enter_context(FakeLLMServer(response))
        gateway = stack.enter_context(Gateway(Path(root) / "runtime", source, provider.base_url))
        gateway.request("/api/sessions", bearer=False, expected=401)
        report = journey(args, gateway)
        report["canonical_receipts"] = verify_history(gateway, provider)
    report.update({"gateway_revision": args.revision, "canonical_review_turns": 3,
                   "unrelated_thread_turns": 1, "automatically_submitted_turns": 0})
    (args.output / "thread-instrument.json").write_text(json.dumps(report, indent=2) + "\n")
    write_walkthrough(args.output, CAPTURES)
    print(json.dumps(report), flush=True)


if __name__ == "__main__":
    main()
