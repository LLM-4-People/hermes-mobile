"""Run the packaged discussion journeys against a disposable account and retain only their captures."""
from contextlib import contextmanager
import hashlib
import json
import secrets

from .installed import installed_apk_sha256
from .run import APP, DEVICE_DIR, DRIVER, Emulator, cleanup_all, collect_captures


@contextmanager
def discussion_device(args, gateway, **fields):
    device = Emulator(args.serial, args.adb, args.adb_port)
    if device.shell("getprop", "ro.kernel.qemu") != "1" or DRIVER not in device.shell("pm", "list", "instrumentation"):
        raise ValueError("Install the debug app and test APK on a disposable emulator")
    installed = [installed_apk_sha256(device, pkg) for pkg in (APP, APP + ".test")]
    if args.verify_apks:
        for path, digest in zip(args.verify_apks, installed, strict=True):
            with path.open("rb") as apk:
                if hashlib.file_digest(apk, "sha256").hexdigest() != digest:
                    raise ValueError("Installed artifact differs from " + path.name)
    token = secrets.token_hex(5)
    folder = f"{DEVICE_DIR}/{token}"
    with device.reverse(gateway.endpoint) as endpoint, device.appearance():
        fixture = dict(fields, run=token, endpoint=endpoint, key=gateway.key, phrase=secrets.token_hex(16))
        device.shell("run-as", APP, "mkdir", "-p", folder)
        try:
            device.run("shell", "run-as", APP, "sh", "-c", f"'umask 077 && cat > {DEVICE_DIR}/fixture.json'",
                       data=json.dumps(fixture, ensure_ascii=False).encode())
            yield device, {"apk_sha256": installed[0], "test_apk_sha256": installed[1]}
        finally:
            cleanup_all(lambda: device.shell("run-as", APP, "rm", "-f", DEVICE_DIR + "/fixture.json"),
                        lambda: collect_captures(device, folder, args.output))


def require_captures(output, names):
    captures = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in output.glob("*.png")}
    if set(captures) != set(names) or len(set(captures.values())) != len(names):
        raise AssertionError("Expected distinct native captures: " + ", ".join(sorted(names)))
    return captures
