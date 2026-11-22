#!/usr/bin/env python3
"""Exercise release signing on a real, unsigned APK using a disposable key."""

import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


def verify_signing(tools, unsigned):
    script = Path(__file__).with_name("sign-apk.sh").resolve()
    with tempfile.TemporaryDirectory() as folder:
        scratch = Path(folder)
        key = scratch / "local.p12"
        alias = "disposable-ci-key"
        environment = dict(os.environ, ANDROID_BUILD_TOOLS=tools, ANDROID_SIGNING_ALIAS=alias,
                           ANDROID_SIGNING_STORE_PASSWORD="disposable-password",
                           ANDROID_SIGNING_KEY_PASSWORD="disposable-password")
        keytool_args = ["-keystore", str(key), "-alias", alias,
                        "-storepass:env", "ANDROID_SIGNING_STORE_PASSWORD"]
        subprocess.run(["keytool", "-genkeypair", *keytool_args, "-keyalg", "RSA",
                        "-keysize", "2048", "-storetype", "PKCS12", "-validity", "1",
                        "-dname", "CN=Temporary CI key"], check=True, env=environment)
        certificate = subprocess.check_output(["keytool", "-exportcert", *keytool_args], env=environment)
        environment["ANDROID_SIGNING_CERT_SHA256"] = hashlib.sha256(certificate).hexdigest()
        environment["ANDROID_SIGNING_KEYSTORE_BASE64"] = base64.b64encode(key.read_bytes()).decode()
        environment["GITHUB_SHA"] = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
        output = scratch / "release"
        subprocess.run([script, unsigned, output], check=True, env=environment)
        subprocess.run(["sha256sum", "--check", "SHA256SUMS"], cwd=output, check=True)
        metadata = json.loads((output / "release.json").read_text())
        if metadata["certificateSha256"] != environment["ANDROID_SIGNING_CERT_SHA256"]:
            raise RuntimeError("Signing metadata contains the wrong fingerprint.")
        environment["ANDROID_SIGNING_CERT_SHA256"] = "f" * 64
        incorrect = scratch / "wrong-key"
        result = subprocess.run([script, unsigned, incorrect], env=environment)
        if result.returncode == 0 or any(incorrect.iterdir()):
            raise RuntimeError("A mismatched signing certificate produced release assets.")


if __name__ == "__main__":
    verify_signing(*sys.argv[1:])
