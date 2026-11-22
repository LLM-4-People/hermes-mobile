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
from conversation_cards import admission
from device.discussion import discussion_device, require_captures
from gateway_fixture import Gateway, PINNED_REVISION, check_source
from live_api import await_completion

QUESTION = "Quelles couleurs voyez-vous sur ma capture ?"
ANSWER = "Rectangle jaune sur fond bleu."
CAPTURES = ("01-photo-brouillon.png", "02-photo-reponse.png")


def pixels():
    image = Image.new("RGB", (96, 64), (38, 75, 123))
    image.paste((247, 195, 63), (25, 16, 73, 48))
    return image


def decoded_image(content):
    images = [part for part in content if part.get("type") == "image_url"]
    if len(images) != 1:
        raise AssertionError("Expected exactly one pixel attachment on the main provider")
    value = images[0]["image_url"]["url"]
    prefix = "data:image/png;base64,"
    if not value.startswith(prefix):
        raise AssertionError("Expected a PNG data URL")
    encoded = value[len(prefix):]
    with Image.open(io.BytesIO(base64.b64decode(encoded, validate=True))) as received:
        original = pixels()
        if (received.format != "PNG" or received.size != original.size
                or received.convert("RGBA").tobytes() != original.convert("RGBA").tobytes()):
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
    verify_transcript(gateway, sid)
    return run, sid


def verify_transcript(gateway, sid):
    page = gateway.request(f"/api/sessions/{sid}/messages?order=latest&visible_only=true&limit=10")
    users = [row["content"] for row in page["data"] if row["role"] == "user"]
    if (len(users) != 1 or QUESTION not in users[0] or "[Image attached at:" not in users[0]
            or "data:image/" in users[0]):
        raise AssertionError("Unexpected persisted photo transcript")



def phone_proof(args, gateway):
    with discussion_device(args, gateway) as (device, report):
        device.phase("photo")
    report["captures_sha256"] = require_captures(args.output, CAPTURES)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--serial", help="Dedicated Android 13+ test device with both debug APKs installed")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--adb-port", type=int, default=5037)
    parser.add_argument("--verify-apks", type=Path, nargs=2, metavar=("APP_APK", "TEST_APK"))
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.verify_apks and not args.serial:
        parser.error("--verify-apks requires --serial")
    source, output = args.source.resolve(), args.output.resolve()
    check_source(source, PINNED_REVISION)
    sys.path.insert(0, str(source))
    from tests.fakes.fake_llm_provider import FakeLLMServer
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("Use an empty output directory for this run")
    with tempfile.TemporaryDirectory() as tmp, ExitStack() as stack:
        provider = stack.enter_context(FakeLLMServer(provider_answer))
        gateway = stack.enter_context(VisionGateway(Path(tmp), source, provider.base_url))
        http_run, http_sid = http_proof(gateway)
        phone = phone_proof(args, gateway) if args.serial else {}
        if args.serial:
            native = admission(gateway, 1)
            if (admission(gateway, 0) != (http_run, http_sid) or native is None
                    or native[1] == http_sid or admission(gateway, 2) is not None):
                raise AssertionError("Expected exactly one native photo run in its own discussion")
            if await_completion(gateway, native[0]).get("output") != ANSWER:
                raise AssertionError("The canonical phone reply does not match the pixel-verified model reply")
            verify_transcript(gateway, native[1])
        expected = 2 if args.serial else 1
        if len(provider.main_requests()) != expected:
            raise AssertionError("Picker preview, removal, or replay duplicated a submission")
        receipt = {**phone, "fork": PINNED_REVISION, "model_received_exact_pixels": True, "idempotent": True,
                   "main_provider_requests": expected, "system_picker_verified": bool(args.serial)}
        (output / "vision-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
        print(json.dumps(receipt))


if __name__ == "__main__":
    main()
