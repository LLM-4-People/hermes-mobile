"""Verify phone, tablet, rotation and large-text adaptive continuity on an Android emulator.

Install matching debug and androidTest APKs on a disposable Google APIs emulator, then run:
  python verification/adaptive_devices.py --source /path/to/pinned/hermes --serial emulator-5554 \
      --output /tmp/adaptive-devices --verify-apks /path/to/app.apk /path/to/test.apk
"""

import argparse
from contextlib import ExitStack
import hashlib
import json
from pathlib import Path
import re
import secrets
import sys
import tempfile

from device.installed import installed_apk_sha256
from device.run import APP, DEVICE_DIR, DRIVER, Emulator, android_png_size, cleanup_all, collect_captures
from device.viewport import await_viewport, restore_display, set_viewport
from gateway_fixture import Gateway, PINNED_REVISION, check_source

CAPTURES = {
    "30-adaptatif-initial.png": "La discussion et le compositeur s’adaptent à la fenêtre initiale.",
    "31-adaptatif-rotation.png": "La rotation conserve l’activité, le brouillon et la sélection en place.",
    "32-adaptatif-grands-caracteres.png": "À 150 %, le compositeur et les formulaires restent lisibles sans coupure.",
}
MATRIX_LINE = re.compile(r"^INSTRUMENTATION_RESULT:\s+matrix=(\{.*\})\s*$", re.MULTILINE)


def parse_matrix(report):
    """Extract and validate the structured JSON matrix from instrumentation output."""
    if not isinstance(report, str) or not report.strip():
        raise ValueError("Empty instrumentation report")
    matches = MATRIX_LINE.findall(report)
    if len(matches) != 1:
        raise ValueError(f"Expected exactly one matrix result line, found {len(matches)}")
    payload = json.loads(matches[0])
    if not isinstance(payload, dict):
        raise ValueError("Matrix payload must be a JSON object")
    return payload


def validate_matrices(resize_matrix, large_text_matrix):
    """Ensure rotation preserved both MainActivity and the mounted composer and large text rendered."""
    if resize_matrix.get("sameActivity") is not True:
        raise AssertionError("Rotation recreated MainActivity instead of updating in place")
    if resize_matrix.get("sameComposer") is not True:
        raise AssertionError("Rotation did not keep the same mounted composer view")
    for key in ("initial", "rotated"):
        entry = resize_matrix.get(key)
        if not isinstance(entry, dict) or entry.get("widthPx", 0) <= 0 or entry.get("heightPx", 0) <= 0:
            raise AssertionError(f"Missing or invalid {key} viewport metrics")
    large = large_text_matrix.get("largeText")
    if not isinstance(large, dict) or large.get("fontScale", 0) < 1.3:
        raise AssertionError("Large-text phase did not run with scaled typography")


def set_tablet_viewport(device, width=1200, height=800, density=160, timeout=20.0):
    set_viewport(
        device,
        width,
        height,
        density=density,
        rotation="1",
        timeout=timeout,
        description=f"{width}x{height}@{density} landscape viewport",
    )


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
    spec = {
        "run": token,
        "phrase": secrets.token_hex(16),
        "endpoint": gateway.endpoint,
        "key": gateway.key,
        "draft": "Brouillon adaptatif " + token,
    }
    with device.reverse(gateway.endpoint), device.appearance(), restore_display(device):
        set_tablet_viewport(device, width=args.width, height=args.height)
        device.shell("run-as", APP, "mkdir", "-p", folder)
        device.run(
            "shell", "run-as", APP, "sh", "-c", f"'umask 077 && cat > {DEVICE_DIR}/fixture.json'",
            data=json.dumps(spec, ensure_ascii=False).encode(),
        )
        try:
            resize_report = device.phase("adaptive")
            device.shell("am", "force-stop", APP)
            device.setting("font_scale", "1.5")
            large_report = device.phase("adaptive-large-text")
        finally:
            cleanup_all(
                lambda: device.shell("run-as", APP, "rm", "-f", DEVICE_DIR + "/fixture.json"),
                lambda: collect_captures(device, folder, args.output),
            )
    resize_matrix = parse_matrix(resize_report)
    large_matrix = parse_matrix(large_report)
    validate_matrices(resize_matrix, large_matrix)
    missing = CAPTURES.keys() - {p.name for p in args.output.glob("*.png")}
    if missing:
        raise AssertionError("Missing adaptive captures: " + ", ".join(sorted(missing)))
    captures = {}
    for name in sorted(CAPTURES):
        image = (args.output / name).read_bytes()
        width, height = android_png_size(image, name)
        captures[name] = {
            "caption": CAPTURES[name],
            "width": width,
            "height": height,
            "sha256": hashlib.sha256(image).hexdigest(),
        }
    return {
        "apk_sha256": hashes[0],
        "test_apk_sha256": hashes[1],
        "resize": resize_matrix,
        "large_text": large_matrix,
        "captures": captures,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--revision", default=PINNED_REVISION)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--adb-port", type=int, default=5037)
    parser.add_argument("--width", type=int, default=1200)
    parser.add_argument("--height", type=int, default=800)
    parser.add_argument("--verify-apks", nargs=2, type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    source = args.source.resolve()
    check_source(source, args.revision)
    sys.path.insert(0, str(source))
    from tests.fakes.fake_llm_provider import FakeLLMServer, Text

    args.output.mkdir(parents=True, exist_ok=False)
    with ExitStack() as stack:
        root = stack.enter_context(tempfile.TemporaryDirectory(prefix="hm-adaptive-"))
        provider = stack.enter_context(FakeLLMServer(lambda _record: Text("Réponse adaptative prête.")))
        gateway = stack.enter_context(Gateway(Path(root) / "runtime", source, provider.base_url))
        report = journey(args, gateway)
    report["gateway_revision"] = args.revision
    receipt = args.output / "adaptive-devices.json"
    temporary = receipt.with_suffix(".tmp")
    temporary.write_text(json.dumps(report, indent=2) + "\n")
    temporary.replace(receipt)
    print(json.dumps(report), flush=True)


if __name__ == "__main__":
    main()
