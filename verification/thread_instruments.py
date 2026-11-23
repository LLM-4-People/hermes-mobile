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
import sys
import tempfile
import time

from conversation_cards import admission
from device.app_strings import AppStrings
from device.installed import installed_apk_sha256
from device.run import APP, DEVICE_DIR, DRIVER, Emulator, android_png_size, cleanup_all, collect_captures
from gateway_fixture import Gateway, PINNED_REVISION, check_source
from live_api import transcript
from instrument_walkthrough import write_walkthrough


FIRST = "Relis le calcul dans facture.py."
CREATE = AppStrings.for_lang("fr")["instrument_create_request"]
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


def last_user_message(body):
    return next((row.get("content") for row in reversed(body["messages"]) if row.get("role") == "user"), "")


def response(record, create_request=CREATE):
    from tests.fakes.fake_llm_provider import Text
    prompt = last_user_message(record["body"])
    if prompt == create_request + "\n\n" + GOAL:
        return Text(proposal(False))
    replies = {FIRST: FEEDBACK, REVISE: proposal(True), OTHER: "La réunion est prête."}
    return Text(replies.get(prompt, "Aucune proposition demandée."))


def provider_instructions(body):
    parts = []
    for message in body["messages"]:
        if message.get("role") not in {"system", "developer"}:
            continue
        content = message.get("content")
        if isinstance(content, str):
            parts.append(content)
        elif isinstance(content, list):
            parts.extend(part["text"] for part in content if isinstance(part, dict)
                         and part.get("type") in {"text", "input_text"} and isinstance(part.get("text"), str))
    return "\n".join(parts)


def verify_history(gateway, provider, create_request=CREATE):
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
    request = create_request + "\n\n" + GOAL
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
    for receipt, body in zip(receipts, provider.main_requests(), strict=True):
        if last_user_message(body) != receipt["user"]:
            raise AssertionError("Provider request does not match the canonical turn")
        instructions = provider_instructions(body)
        examples = [json.loads(raw) for raw in re.findall(
            r"^```hermes-workspace\n(.*?)\n```$", instructions, re.MULTILINE | re.DOTALL)]
        named = [page["blocks"] for page in examples
                 if page.get("surface", "").startswith("page/") and page.get("mode") == "replace"]
        if not any(block.get("type") == "form" and block.get("fields") for blocks in named for block in blocks):
            raise AssertionError("The provider did not receive a complete named-page form example")
        shapes = {"calendar_event": {"kind", "title", "start", "end", "location"},
                  "share_text": {"kind", "text"}, "map_search": {"kind", "query"}}
        if not any(blocks and all(block.get("type") == "device" for block in blocks)
                   and {block["request"]["kind"]: set(block["request"]) for block in blocks} == shapes
                   for blocks in named):
            raise AssertionError("The provider did not receive a complete page with the three Android actions")
        if [{"type": "nearby_places", "category": "restaurant"}] not in named:
            raise AssertionError("The provider did not receive the source-backed restaurant guide example")
        fallback = {"type": "device", "request": {"kind": "map_search", "query": "restaurants in Rosemont, Montreal"}}
        if [fallback] not in named:
            raise AssertionError("The provider did not receive the named-neighborhood map-search example")
        surfaces = sorted(set(re.findall(r"\bsurface=(page/[a-z0-9_-]+)", instructions)))
        expected_surfaces = ["page/revue-code"] if receipt["user"] == REVISE else []
        if surfaces != expected_surfaces:
            raise AssertionError("Provider instructions contain an unexpected thread-local tool reference")
        receipt["provider_schema_verified"] = True
        receipt["provider_pinned_surfaces"] = surfaces
        wire = json.dumps(body, ensure_ascii=False)
        if any(value in wire for value in ("facture/revue-proposee.py", "facture/revue-epinglee.py")):
            raise AssertionError("An unsent comparison field reached the model")
    return receipts


def await_viewport(device, *, expected_rotation, required_tokens, label, timeout=20):
    deadline = time.monotonic() + timeout
    last_config = ""
    last_rotation = ""
    while time.monotonic() < deadline:
        last_config = device.shell("am", "get-config")
        last_rotation = device.shell("wm", "user-rotation")
        if last_rotation == expected_rotation and all(token in last_config for token in required_tokens):
            return
        time.sleep(0.05)
    raise AssertionError(
        f"The emulator did not apply {label} (expected rotation={expected_rotation!r}, "
        f"tokens={list(required_tokens)!r}; saw rotation={last_rotation!r}, config={last_config!r})"
    )


def set_viewport(device, *, wide):
    rotation = "1" if wide else "0"
    width, height = (1200, 720) if wide else (320, 640)
    device.shell("wm", "size", f"{min(width, height)}x{max(width, height)}")
    device.shell("wm", "density", "160")
    device.shell("settings", "put", "system", "user_rotation", rotation)
    device.shell("wm", "user-rotation", "lock", rotation)
    await_viewport(device, expected_rotation="lock " + rotation,
                   required_tokens=(f"-w{width}dp-h{height}dp-",),
                   label=f"the requested {width}×{height} viewport")


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
    saved_config = device.shell("am", "get-config")
    saved_orientation = next((token for token in ("-port-", "-land-") if token in saved_config), None)
    size_match = re.search(r"-w\d+dp-h\d+dp-", saved_config)
    expected_tokens = tuple(token for token in (size_match.group(0) if size_match else None, saved_orientation) if token)
    if rotation[0] == "lock":
        target_rotation = rotation[1]
    elif saved_rotation in {"0", "1", "2", "3"}:
        target_rotation = saved_rotation
    else:
        target_rotation = "1" if saved_orientation == "-land-" else "0"

    def restore_orientation():
        if rotation == ["free"]:
            # Returning directly from `lock 1` to `free` can leave a headless emulator in landscape
            # with user_rotation=1; drive rotation back explicitly before releasing the lock.
            device.shell("wm", "user-rotation", "lock", target_rotation)
            await_viewport(device, expected_rotation="lock " + target_rotation,
                           required_tokens=expected_tokens, label="the restored display orientation")

    try:
        set_viewport(device, wide=False)
        yield
    finally:
        cleanup_all(lambda: device.shell("wm", "size", overrides["size"]),
                    lambda: device.shell("wm", "density", overrides["density"]),
                    restore_orientation,
                    lambda: device.setting("user_rotation", saved_rotation),
                    lambda: device.shell("wm", "user-rotation", *rotation),
                    lambda: await_viewport(device, expected_rotation=" ".join(rotation),
                                           required_tokens=expected_tokens, label="the original display viewport"))


def journey(args, gateway, *, device=None, create_request=CREATE):
    if device is None:
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
            "key": gateway.key, "first": FIRST, "template": create_request, "goal": GOAL, "revision": REVISE, "other": OTHER,
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
        image = (args.output / name).read_bytes()
        size = android_png_size(image, name)
        expected = (1200, 720) if name == "25-outil-grand-ecran.png" else (320, 640)
        if size != expected:
            raise AssertionError(f"{name} rendered at {size}, expected {expected}")
        captures[name] = {"caption": CAPTURES[name], "width": size[0], "height": size[1],
                          "sha256": hashlib.sha256(image).hexdigest()}
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
    device = Emulator(args.serial, args.adb, args.adb_port)
    create_request = AppStrings.for_device(device)["instrument_create_request"]
    with ExitStack() as stack:
        root = stack.enter_context(tempfile.TemporaryDirectory(prefix="hm-thread-instrument-"))
        provider = stack.enter_context(FakeLLMServer(lambda rec: response(rec, create_request=create_request)))
        gateway = stack.enter_context(Gateway(Path(root) / "runtime", source, provider.base_url))
        gateway.request("/api/sessions", bearer=False, expected=401)
        report = journey(args, gateway, device=device, create_request=create_request)
        report["canonical_receipts"] = verify_history(gateway, provider, create_request=create_request)
    report.update({"gateway_revision": args.revision, "canonical_review_turns": 3,
                   "unrelated_thread_turns": 1, "automatically_submitted_turns": 0})
    write_walkthrough(args.output, CAPTURES, report)
    receipt = args.output / "thread-instrument.json"
    temporary = receipt.with_suffix(".tmp")
    temporary.write_text(json.dumps(report, indent=2) + "\n")
    temporary.replace(receipt)
    print(json.dumps(report), flush=True)


if __name__ == "__main__":
    main()
