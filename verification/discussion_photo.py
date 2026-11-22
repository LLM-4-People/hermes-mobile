"""Prove the pinned gateway's vision run with synthetic pixels, optionally through the Android system picker."""
import argparse
import base64
from contextlib import ExitStack
import io
import json
from pathlib import Path
import secrets
import sys
import tempfile

from PIL import Image
from device.run import APP, DEVICE_DIR, Emulator, collect_captures
from gateway_fixture import Gateway, PINNED_REVISION, check_source
from live_api import await_completion

QUESTION = "Quelles couleurs voyez-vous sur ma capture ?"
ANSWER = "Rectangle jaune sur fond bleu."


def pixels():
    image = Image.new("RGB", (96, 64), (38, 75, 123))
    image.paste((247, 195, 63), (25, 16, 73, 48))
    return image


def decoded_image(content):
    images = [part for part in content if part.get("type") == "image_url"]
    if len(images) != 1:
        raise AssertionError("Expected exactly one pixel attachment on the main provider")
    value = images[0]["image_url"]["url"]
    encoded = value.removeprefix("data:image/png;base64,")
    with Image.open(io.BytesIO(base64.b64decode(encoded, validate=True))) as received:
        original = pixels()
        if received.size != original.size or received.convert("RGB").tobytes() != original.tobytes():
            raise AssertionError("The main provider received different pixels")


def provider_answer(record):
    from tests.fakes.fake_llm_provider import Text
    content = next((m["content"] for m in reversed(record["body"]["messages"]) if m["role"] == "user"), "")
    if not isinstance(content, list) or not any(QUESTION in part.get("text", "") for part in content):
        return Text("Aucune capture correspondante.")
    decoded_image(content)
    return Text(ANSWER)


class VisionGateway(Gateway):
    def __init__(self, root, source, provider):
        super().__init__(root, source, provider)
        from ruamel.yaml import YAML
        yaml = YAML()
        config = Path(self.env["HERMES_HOME"]) / "config.yaml"
        parsed = yaml.load(config.read_text())
        parsed["model"]["supports_vision"] = True
        with config.open("w", encoding="utf-8") as stream:
            yaml.dump(parsed, stream)


def http_proof(gateway):
    encoded = io.BytesIO()
    pixels().save(encoded, format="PNG")
    url = "data:image/png;base64," + base64.b64encode(encoded.getvalue()).decode()
    sid = gateway.request("/api/sessions", method="POST", body={}, expected=201)["session"]["id"]
    parts = [{"type": "text", "text": QUESTION}, {"type": "image_url", "image_url": {"url": url}}]
    request = {"session_id": sid, "input": [{"role": "user", "content": parts}]}
    headers = {"Idempotency-Key": secrets.token_hex(16)}
    run = gateway.request("/v1/runs", method="POST", body=request, headers=headers, expected=202)["run_id"]
    if await_completion(gateway, run).get("output") != ANSWER:
        raise AssertionError("Vision provider did not receive the selected image")
    again = gateway.request("/v1/runs", method="POST", body=request, headers=headers, expected=202)
    if again.get("run_id") != run:
        raise AssertionError("Image retry duplicated a run")
    page = gateway.request(f"/api/sessions/{sid}/messages?order=latest&visible_only=true&limit=10")
    users = [row["content"] for row in page["data"] if row["role"] == "user"]
    if len(users) != 1 or QUESTION not in users[0] or "[Image attached at:" not in users[0] or url in users[0]:
        raise AssertionError("Unexpected persisted photo transcript")


def phone_proof(device, gateway, output):
    run = secrets.token_hex(4)
    capture_dir = f"{DEVICE_DIR}/{run}"
    fixture = {"run": run, "endpoint": gateway.endpoint, "key": gateway.key, "phrase": secrets.token_hex(16)}
    with device.reverse(gateway.endpoint), device.appearance():
        device.shell("run-as", APP, "mkdir", "-p", capture_dir)
        device.run("shell", "run-as", APP, "sh", "-c", f"'umask 077 && cat > {DEVICE_DIR}/fixture.json'",
                   data=json.dumps(fixture).encode())
        try:
            device.phase("photo")
        finally:
            device.shell("run-as", APP, "rm", "-f", DEVICE_DIR + "/fixture.json")
            collect_captures(device, capture_dir, output)
    if not all((output / name).exists() for name in ("01-photo-brouillon.png", "02-photo-reponse.png")):
        raise AssertionError("Missing picker proof captures")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--serial", help="Dedicated Android 13+ test device with both debug APKs installed")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--adb-port", type=int, default=5037)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    source, output = args.source.resolve(), args.output.resolve()
    check_source(source, PINNED_REVISION)
    sys.path.insert(0, str(source))
    from tests.fakes.fake_llm_provider import FakeLLMServer
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp, ExitStack() as stack:
        provider = stack.enter_context(FakeLLMServer(provider_answer))
        gateway = stack.enter_context(VisionGateway(Path(tmp), source, provider.base_url))
        http_proof(gateway)
        if args.serial:
            phone_proof(Emulator(args.serial, args.adb, args.adb_port), gateway, output)
        expected = 2 if args.serial else 1
        if len(provider.main_requests()) != expected:
            raise AssertionError("Picker preview, removal, or replay duplicated a submission")
        receipt = {"fork": PINNED_REVISION, "model_received_exact_pixels": True, "idempotent": True,
                   "main_provider_requests": expected, "system_picker_verified": bool(args.serial)}
        (output / "vision-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
        print(json.dumps(receipt))


if __name__ == "__main__":
    main()
