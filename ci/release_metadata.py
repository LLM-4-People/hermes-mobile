"""Describe the signed bytes, embedded Android version and reviewed commit."""

import hashlib
import json
from pathlib import Path
import re
import sys


def digest(path):
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def describe(badging, unsigned, signed, certificate, commit):
    pattern = r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'"
    package = re.search(pattern, badging, re.MULTILINE)
    if not package:
        raise ValueError("The APK has no readable package and version.")
    package_name, version_code, version = package.groups()
    # Tag names come only from a release build's application version.
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?", version):
        raise ValueError("Use a semantic release version for the Android versionName.")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("The source revision must be a full Git commit SHA.")
    return {
        "applicationId": package_name,
        "versionName": version,
        "versionCode": int(version_code),
        "tag": f"v{version}",
        "sourceCommit": commit,
        "certificateSha256": certificate.lower(),
        "unsignedSha256": digest(unsigned),
        "signedSha256": digest(signed),
    }


if __name__ == "__main__":
    manifest, unsigned_apk, signed_apk, signer, sha = sys.argv[1:]
    print(json.dumps(describe(Path(manifest).read_text(), Path(unsigned_apk),
                              Path(signed_apk), signer, sha), indent=2))
