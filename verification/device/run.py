"""Exercise the installed debug APK on one dedicated emulator using live_api.py's two test runtimes."""

import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import struct
import subprocess
import sys
import urllib.parse
import urllib.request
import zlib

APP = "org.llm4people.hermes"
DRIVER = f"{APP}.test/{APP}.journey.DeviceJourney"
DEVICE_DIR = "cache/device-journey"


def cleanup_all(*actions):
    """Attempt each restoration and preserve the check that initiated cleanup."""
    primary = sys.exc_info()[1]
    failures = []
    for action in actions:
        try:
            action()
        except Exception as error:
            failures.append(error)
    if failures:
        if primary is None:
            raise ExceptionGroup("Device cleanup failed", failures)
        for error in failures:
            primary.add_note("Device cleanup also failed: " + str(error))


class Emulator:
    def __init__(self, serial, adb, port):
        self.command = [adb, "-P", str(port), "-s", serial]

    def run(self, *args, data=None, timeout=120):
        # A shell argument can be a whole command containing credentials or draft text.
        name = args[0] if args and args[0] in {"shell", "exec-out", "install", "uninstall", "reverse",
                                               "push", "pull", "forward", "get-state", "wait-for-device"} else "command"
        if name == "shell" and len(args) > 1 and args[1] in {
                "am", "cmd", "content", "dumpsys", "getprop", "id", "input", "pm", "run-as", "settings", "wm"}:
            name += " " + args[1]
        try:
            completed = subprocess.run(self.command + list(args), input=data, stdout=subprocess.PIPE,
                                       stderr=subprocess.PIPE, timeout=timeout, check=False)
        except subprocess.TimeoutExpired:
            raise RuntimeError(f"adb {name} exceeded {timeout}s") from None
        if completed.returncode:
            # A failed dumpsys can return thousands of lines of partial private UI state.
            stdout, stderr = completed.stdout, completed.stderr
            detail = f"{len(stdout)} stdout bytes, {len(stderr)} stderr bytes" if stdout or stderr else "no output"
            if len(stderr) <= 96:
                diagnostic = stderr.strip().removeprefix(b"adb: ").removeprefix(b"error: ")
                if diagnostic in {b"device offline", b"device unauthorized", b"no devices/emulators found"}:
                    detail = diagnostic.decode("ascii")
            raise RuntimeError(f"adb {name} exited with status {completed.returncode}: {detail}")
        return completed.stdout

    def shell(self, *args):
        return self.run("shell", *args).decode().strip()

    def ime_shown(self):
        # Keep window and editor diagnostics inside Android. Read to EOF and preserve
        # the status of dumpsys, even when it fails after printing a matching field.
        command = ("set -o pipefail; dumpsys input_method | "
                   "grep -oE '(^|[[:space:]])mInputShown=(true|false)([[:space:]]|$)' | "
                   "sed -E 's/.*mInputShown=(true|false).*/\\1/' | sort -u")
        state = self.shell(command)
        if state not in ("true", "false"):
            raise RuntimeError("Android did not report an unambiguous IME visibility")
        return state == "true"

    def setting(self, key, value):
        if value == "null":
            self.shell("settings", "delete", "system", key)
        else:
            self.shell("settings", "put", "system", key, value)

    @contextlib.contextmanager
    def appearance(self):
        old_night = self.shell("cmd", "uimode", "night").split(":")[-1].strip()
        if old_night not in {"no", "yes", "auto", "custom_schedule", "custom_bedtime"}:
            raise RuntimeError("Unrecognized current night mode: " + old_night)
        old_font = self.shell("settings", "get", "system", "font_scale")
        old_keyboard = self.shell("settings", "get", "secure", "show_ime_with_hard_keyboard")
        try:
            self.shell("cmd", "uimode", "night", "no")
            self.setting("font_scale", "1.0")
            self.shell("settings", "put", "secure", "show_ime_with_hard_keyboard", "1")
            yield
        finally:
            keyboard_args = (("delete", "secure", "show_ime_with_hard_keyboard") if old_keyboard == "null"
                             else ("put", "secure", "show_ime_with_hard_keyboard", old_keyboard))
            cleanup_all(lambda: self.shell("cmd", "uimode", "night", old_night),
                        lambda: self.setting("font_scale", old_font),
                        lambda: self.shell("settings", *keyboard_args))

    @contextlib.contextmanager
    def reverse(self, endpoint):
        target = urllib.parse.urlsplit(endpoint)
        if target.hostname != "127.0.0.1" or target.scheme != "http" or not target.port or target.path:
            raise ValueError("Expected a fresh loopback fixture endpoint")
        # Keep an explicit device registration per fixture. ADB's host allowlist keys
        # registrations by their requested device-side socket.
        socket = f"tcp:{target.port}"
        self.run("reverse", "--no-rebind", socket, socket)
        try:
            yield endpoint
        finally:
            cleanup_all(lambda: self.run("reverse", "--remove", socket))

    def phase(self, name):
        report = self.run("shell", "am", "instrument", "-w", "-r", "-e", "phase", name, DRIVER,
                          timeout=1000).decode(errors="replace")
        if f"journey={name} passed" not in report or "INSTRUMENTATION_CODE: -1" not in report:
            raise AssertionError(report)
        print(name + " passed", flush=True)
        return report


def provider_labels(endpoint, key, model_routes):
    query = urllib.request.Request(endpoint + "/api/model/options?include_unconfigured=false",
                                   headers={"Authorization": "Bearer " + key})
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with client.open(query, timeout=15) as response:
        catalog = json.load(response)
    labels = {}
    for alias, (slug, model) in model_routes.items():
        matches = [row for row in catalog["providers"] if row.get("authenticated", True)
                   and model in row["models"] and (slug is None or row["slug"] == slug)]
        if len(matches) != 1:
            raise RuntimeError(f"The loopback runtime has an ambiguous or missing {alias} model route")
        row = matches[0]
        name = row.get("name") or row["slug"]
        labels[alias] = name if name == row["slug"] else f"{name} · {row['slug']}"
    return labels


def run(device, output):
    if device.shell("getprop", "ro.kernel.qemu") != "1":
        raise ValueError("Select a disposable emulator for the verification accounts")
    if int(device.shell("getprop", "ro.build.version.sdk")) < 30:
        raise ValueError("The capture driver requires API 30 or later")
    if DRIVER not in device.shell("pm", "list", "instrumentation"):
        raise RuntimeError("Install the current debug APK and androidTest APK first")
    primary = os.environ["HERMES_TEST_ENDPOINT"]
    secondary = os.environ["HERMES_TEST_SECOND_ENDPOINT"]
    key = os.environ["HERMES_TEST_BEARER"]
    with device.reverse(primary) as endpoint, device.reverse(secondary) as second, device.appearance():
        number = secrets.token_hex(4)
        alt_model = os.environ["HERMES_TEST_ALT_MODEL"]
        labels = provider_labels(primary, key, {"primary": ("custom", "fake-model"),
                               "alternate": (os.environ["HERMES_TEST_ALT_PROVIDER"], alt_model)})
        fixture = {"endpoint": endpoint, "key": key, "secondEndpoint": second,
                   "secondKey": os.environ["HERMES_TEST_SECOND_BEARER"],
                   "run": number, "phrase": secrets.token_hex(16), "secondPhrase": secrets.token_hex(16),
                   "draft": "Brouillon privé " + number, "provider": labels["primary"],
                   "alternateProvider": labels["alternate"], "alternateModel": alt_model}
        capture_dir = DEVICE_DIR + "/" + number
        device.shell("run-as", APP, "mkdir", "-p", capture_dir)
        output = output / number
        output.mkdir()
        device.run("shell", "run-as", APP, "sh", "-c", f"'umask 077 && cat > {DEVICE_DIR}/fixture.json'",
                   data=json.dumps(fixture).encode())
        try:
            device.phase("work")
            device.shell("am", "force-stop", APP)
            device.shell("cmd", "uimode", "night", "yes")
            device.setting("font_scale", "1.5")
            device.phase("restart")
            device.shell("cmd", "uimode", "night", "no")
            device.setting("font_scale", "1.0")
            device.phase("pages")
        finally:
            cleanup_all(lambda: device.shell("run-as", APP, "rm", "-f", DEVICE_DIR + "/fixture.json"),
                        lambda: collect_captures(device, capture_dir, output))



PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def android_png_size(data, name):
    """Validate the complete 8-bit RGB/RGBA PNG written by Android Bitmap.compress."""
    def invalid(reason):
        return ValueError(f"{name}: invalid Android PNG ({reason})")

    if not data.startswith(PNG_SIGNATURE):
        raise invalid("signature")
    offset = len(PNG_SIGNATURE)
    dimensions = None
    seen = set()
    compressed = bytearray()
    while offset + 12 <= len(data):
        length, kind = struct.unpack_from(">I4s", data, offset)
        start = offset + 8
        end = start + length
        if end + 4 > len(data):
            raise invalid("truncated chunk")
        checksum = struct.unpack_from(">I", data, end)[0]
        if zlib.crc32(data[offset + 4:end]) != checksum:
            raise invalid("chunk checksum")
        payload = data[start:end]
        offset = end + 4
        if kind == b"IHDR":
            if seen or length != 13:
                raise invalid("header")
            width, height, depth, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", payload)
            if not (0 < width <= 4096 and 0 < height <= 4096 and depth == 8 and color in (2, 6)
                    and compression == filtering == interlace == 0):
                raise invalid("unsupported bitmap dimensions or format")
            dimensions = width, height
            channels = 3 if color == 2 else 4
        elif dimensions is None:
            raise invalid("missing header")
        elif kind == b"IDAT":
            compressed.extend(payload)
        elif kind == b"IEND":
            if length or offset != len(data):
                raise invalid("trailing data")
            seen.add(kind)
            break
        else:
            # Canvas screenshots contain pixels and display metadata. Never export text chunks.
            sizes = {b"sBIT": channels, b"sRGB": 1, b"gAMA": 4, b"cHRM": 32, b"pHYs": 9}
            if sizes.get(kind) != length or kind in seen or b"IDAT" in seen:
                raise invalid("unexpected image metadata")
        seen.add(kind)
    if b"IEND" not in seen or not compressed:
        raise invalid("incomplete image")

    # Each non-interlaced scanline starts with its PNG filter byte. Bound expansion by IHDR.
    stride = 1 + width * channels
    expected = height * stride
    decoder = zlib.decompressobj()
    try:
        pixels = decoder.decompress(compressed, expected + 1)
    except zlib.error as exc:
        raise invalid("compressed pixels") from exc
    if len(pixels) != expected or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
        raise invalid("incomplete or excess pixel data")
    if any(filter_type > 4 for filter_type in pixels[::stride]):
        raise invalid("scanline filter")
    return dimensions


def collect_captures(device, capture_dir, output):
    captures = {}
    for filename in device.shell("run-as", APP, "ls", "-1", capture_dir).splitlines():
        if not re.fullmatch(r"[0-9]{2}-[a-z-]+\.png", filename):
            continue
        image = device.run("exec-out", "run-as", APP, "cat", capture_dir + "/" + filename)
        android_png_size(image, filename)
        (output / filename).write_bytes(image)
        captures[filename] = hashlib.sha256(image).hexdigest()
        device.shell("run-as", APP, "rm", "-f", capture_dir + "/" + filename)
    device.shell("run-as", APP, "rmdir", capture_dir)
    print(json.dumps({"output": str(output), "captures": captures}, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default=shutil.which("adb") or "adb")
    parser.add_argument("--adb-port", type=int, default=5037)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    run(Emulator(args.serial, args.adb, args.adb_port), args.output)


if __name__ == "__main__":
    main()
