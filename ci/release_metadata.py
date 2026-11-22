"""Describe the signed bytes, embedded Android version and reviewed commit."""

import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys


def digest(path):
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def version_code(value):
    if type(value) is not int or not 1 <= value <= 2_100_000_000:
        raise ValueError("Invalid Android versionCode")
    return value


def apk_certificate(tools, apk):
    result = subprocess.check_output(
        [str(Path(tools) / "apksigner"), "verify", "--verbose", "--print-certs", apk], text=True
    )
    fingerprints = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", result, re.MULTILINE)
    if len(fingerprints) != 1:
        raise ValueError("Expected one verified APK signer")
    return fingerprints[0].lower()


def describe(badging, unsigned, signed, certificate, commit):
    pattern = r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'"
    package = re.search(pattern, badging, re.MULTILINE)
    if not package:
        raise ValueError("The APK has no readable package and version.")
    package_name, code, version = package.groups()
    # Tag names come only from a release build's application version.
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?", version):
        raise ValueError("Use a semantic release version for the Android versionName.")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("The source revision must be a full Git commit SHA.")
    return {
        "applicationId": package_name,
        "versionName": version,
        "versionCode": version_code(int(code)),
        "tag": f"v{version}",
        "sourceCommit": commit,
        "certificateSha256": certificate.lower(),
        "unsignedSha256": digest(unsigned),
        "signedSha256": digest(signed),
    }


if __name__ == "__main__":
    manifest, unsigned_apk, signed_apk, expected, sha, tools = sys.argv[1:]
    signer = apk_certificate(tools, signed_apk)
    if signer != expected.replace(":", "").lower():
        sys.exit("The APK signing certificate differs from the configured SHA-256 fingerprint.")
    print(json.dumps(describe(Path(manifest).read_text(), Path(unsigned_apk),
                              Path(signed_apk), signer, sha), indent=2))
