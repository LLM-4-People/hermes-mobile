"""Locate and hash the single APK that Android installed for a package."""

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
