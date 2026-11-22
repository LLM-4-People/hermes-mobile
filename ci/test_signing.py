#!/usr/bin/env python3
"""Exercise release signing on a real, unsigned APK using a disposable key."""

import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

from publish_release import validate_recovered


def verify_signing(tools, unsigned, device_input=None):
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
        subprocess.run(["keytool", "-genkeypair", *keytool_args, "-keyalg", "EC",
                        "-groupname", "secp256r1", "-storetype", "PKCS12", "-validity", "1",
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
        check_ecdsa_retry(scratch, script, unsigned, environment, output)
        environment["ANDROID_SIGNING_CERT_SHA256"] = "f" * 64
        incorrect = scratch / "wrong-key"
        result = subprocess.run([script, unsigned, incorrect], env=environment)
        if result.returncode == 0 or any(incorrect.iterdir()):
            raise RuntimeError("A mismatched signing certificate produced release assets.")

        if device_input:
            # Only the signed APK, its public identity and the original input leave this temporary directory.
            destination = Path(device_input)
            destination.mkdir(exist_ok=False)
            shutil.copy2(output / "Hermes-Mobile.apk", destination)
            shutil.copy2(output / "release.json", destination)
            shutil.copy2(unsigned, destination / "app-release-unsigned.apk")


def check_ecdsa_retry(scratch, signing_script, unsigned, environment, first):
    retry = scratch / "retry"
    subprocess.run([signing_script, unsigned, retry], env=environment, check=True)
    name = "Hermes-Mobile.apk"
    original = first / name
    old_checksum = hashlib.sha256(original.read_bytes()).hexdigest()
    if old_checksum == hashlib.sha256((retry / name).read_bytes()).hexdigest():
        raise RuntimeError("The ECDSA signing fixture did not exercise distinct signatures.")
    altered_input = scratch / "changed-input.apk"
    shutil.copyfile(unsigned, altered_input)
    with zipfile.ZipFile(altered_input, "a", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("assets/ci-variant.txt", "Other build")
    altered_out = scratch / "different-build"
    subprocess.run([signing_script, altered_input, altered_out], check=True, env=environment)
    metadata = json.loads((retry / "release.json").read_text())
    try:
        validate_recovered(Path(unsigned), altered_out / name, metadata, environment["ANDROID_BUILD_TOOLS"])
    except ValueError as error:
        if "APK entries differ" not in str(error):
            raise
    else:
        raise RuntimeError("Recovered an APK with a different payload under the same release certificate.")
    state = {"main": environment["GITHUB_SHA"], "ref": None, "download": str(original),
             "attestation": False,
             "releases": [{"tag_name": json.loads((first / "release.json").read_text())["tag"],
                           "target_commitish": environment["GITHUB_SHA"], "draft": True,
                           "assets": [{"name": name, "digest": "sha256:" + old_checksum}]}]}
    state_file = scratch / "state.json"
    state_file.write_text(json.dumps(state))
    gh = scratch / "gh"
    shutil.copyfile(Path(__file__).with_name("fixtures") / "release_cli.py", gh)
    gh.chmod(0o755)
    test_env = dict(environment, PATH=f"{scratch}:{environment['PATH']}",
                    TEST_STATE=str(state_file), GITHUB_REPOSITORY="local/fixture")
    recover = [sys.executable, Path(__file__).with_name("publish_release.py"),
               retry, "--recover-from", unsigned]
    rejected = subprocess.run(recover, env=test_env, capture_output=True)
    if rejected.returncode == 0:
        raise RuntimeError("An unattested APK was reused.")
    state["attestation"] = True
    state_file.write_text(json.dumps(state))
    subprocess.run(recover, check=True, env=test_env)
    for file in (name, "SHA256SUMS", "release.json"):
        if (retry / file).read_bytes() != (first / file).read_bytes():
            raise RuntimeError(f"Recovered {file} differs from the verified original")


if __name__ == "__main__":
    verify_signing(*sys.argv[1:])
