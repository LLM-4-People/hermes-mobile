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
import subprocess
import urllib.parse
import urllib.request

APP = "org.llm4people.hermes"
DRIVER = f"{APP}.test/{APP}.journey.DeviceJourney"
DEVICE_DIR = "cache/device-journey"


class Emulator:
    def __init__(self, serial, adb, port):
        self.command = [adb, "-P", str(port), "-s", serial]

    def run(self, *args, data=None, timeout=120):
        completed = subprocess.run(self.command + list(args), input=data, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, timeout=timeout, check=False)
        if completed.returncode:
            raise RuntimeError(completed.stderr.decode(errors="replace") or completed.stdout.decode(errors="replace"))
        return completed.stdout

    def shell(self, *args):
        return self.run("shell", *args).decode().strip()

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
            self.shell("cmd", "uimode", "night", old_night)
            self.setting("font_scale", old_font)
            if old_keyboard == "null":
                self.shell("settings", "delete", "secure", "show_ime_with_hard_keyboard")
            else:
                self.shell("settings", "put", "secure", "show_ime_with_hard_keyboard", old_keyboard)

    @contextlib.contextmanager
    def reverse(self, endpoint):
        target = urllib.parse.urlsplit(endpoint)
        if target.hostname != "127.0.0.1" or target.scheme != "http" or not target.port or target.path:
            raise ValueError("Expected a fresh loopback fixture endpoint")
        local = self.run("reverse", "tcp:0", f"tcp:{target.port}").decode().strip()
        if not local.isdigit():
            raise RuntimeError("ADB did not allocate an isolated reverse port")
        try:
            yield f"http://127.0.0.1:{local}"
        finally:
            self.run("reverse", "--remove", f"tcp:{local}")

    def phase(self, name):
        report = self.run("shell", "am", "instrument", "-w", "-r", "-e", "phase", name, DRIVER,
                          timeout=1000).decode(errors="replace")
        if f"journey={name} passed" not in report or "INSTRUMENTATION_CODE: -1" not in report:
            raise AssertionError(report)
        print(name + " passed", flush=True)


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
        finally:
            device.shell("run-as", APP, "rm", "-f", DEVICE_DIR + "/fixture.json")
            captures = {}
            for filename in device.shell("run-as", APP, "ls", "-1", capture_dir).splitlines():
                if not re.fullmatch(r"[0-9]{2}-[a-z-]+\.png", filename):
                    continue
                image = device.run("exec-out", "run-as", APP, "cat", capture_dir + "/" + filename)
                if image[:8] != b"\x89PNG\r\n\x1a\n":
                    raise ValueError("Unexpected app capture format")
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
