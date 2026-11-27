"""Locate and hash the single APK that Android installed for a package."""

import hashlib
import re


def installed_apk_sha256(device, package):
    listing = device.shell("pm", "path", package).strip()
    entry = re.fullmatch(r"package:(/data/app/[A-Za-z0-9~=+./_-]+/base\.apk)", listing)
    if not entry:
        raise ValueError("Android did not report a unique base APK for this package")
    path = entry[1]
    output = device.shell("sha256sum", path).split()
    if len(output) != 2 or output[1] != path or not re.fullmatch(r"[a-f0-9]{64}", output[0]):
        raise ValueError("Android did not return a SHA-256 for the installed base APK")
    return output[0]


def verify_disposable_emulator_apks(device, driver, app, verify_apks=None):
    """Ensure the target is a disposable emulator with matching app and androidTest APKs."""
    if device.shell("getprop", "ro.kernel.qemu") != "1" or driver not in device.shell("pm", "list", "instrumentation"):
        raise ValueError("Install the current app and androidTest APK on a disposable emulator")
    hashes = [installed_apk_sha256(device, pkg) for pkg in (app, app + ".test")]
    if verify_apks:
        for path, actual in zip(verify_apks, hashes, strict=True):
            with path.open("rb") as apk:
                if hashlib.file_digest(apk, "sha256").hexdigest() != actual:
                    raise ValueError("Installed artifact differs from " + path.name)
    return hashes
