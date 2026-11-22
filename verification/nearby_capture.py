"""Capture the real foreground Hermes hierarchy through the packaged, test-only Android runner."""

from contextlib import contextmanager
import hashlib
import json
import secrets
import subprocess
from pathlib import Path

from verification.device.run import APP, DEVICE_DIR, DRIVER, android_png_size
from verification.unifiedpush.stack import eventually


@contextmanager
def captures(device, output):
    run = "nearby-" + secrets.token_hex(8)
    folder = DEVICE_DIR + "/" + run
    fixture = DEVICE_DIR + "/fixture.json"
    device.shell("run-as", APP, "mkdir", "-p", folder)
    device.run("shell", "run-as", APP, "sh", "-c", f"'umask 077 && cat > {fixture}'",
               data=json.dumps({"run": run}).encode())
    proc = None
    try:
        proc = subprocess.Popen(device.command + ["shell", "am", "instrument", "-w", "-r", "-e", "phase",
                                                  "nearby-capture", DRIVER], stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True)

        def wait_file(name):
            def present():
                if proc.poll() is not None:
                    raise AssertionError("Packaged nearby capture runner stopped: " + proc.stdout.read()[-1800:])
                return name in device.shell("run-as", APP, "ls", "-1", folder).splitlines()
            eventually("native test runner " + name, present, timeout=60)

        wait_file("ready")
        images = {}

        def capture(name):
            if name not in ("guide", "detail") or name in images:
                raise ValueError("Unexpected or duplicate nearby capture")
            device.shell("run-as", APP, "touch", folder + "/capture-" + name)
            wait_file(name + ".done")
            image = device.run("exec-out", "run-as", APP, "cat", folder + "/" + name + ".png")
            width, height = android_png_size(image, name)
            with (Path(output) / (name + ".png")).open("xb") as stream:
                stream.write(image)
            images[name] = {"width": width, "height": height, "sha256": hashlib.sha256(image).hexdigest()}

        yield capture, images
        device.shell("run-as", APP, "touch", folder + "/finish")
        report, _ = proc.communicate(timeout=25)
        if proc.returncode or "journey=nearby-capture passed" not in report:
            raise AssertionError("Native guide capture did not finish: " + report[-1800:])
    finally:
        try:
            if proc is not None and proc.poll() is None:
                device.shell("run-as", APP, "touch", folder + "/finish")
                try:
                    proc.communicate(timeout=5)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.communicate()
        finally:
            device.shell("run-as", APP, "rm", "-rf", folder, fixture)
