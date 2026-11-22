"""Run acceptance with a fresh private CA on a named, disposable Android 13 AOSP emulator."""

import argparse
from contextlib import contextmanager
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import tempfile
import time

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT))
from verification.device.run import Emulator, cleanup_all

CA_DIRECTORY = "/system/etc/security/cacerts"


def openssl(*args):
    return subprocess.run(["openssl", *args], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def issue_certificates(directory):
    ca, ca_key = directory / "ca.pem", directory / "ca.key"
    leaf, key, csr = directory / "server.pem", directory / "server.key", directory / "server.csr"
    extensions = directory / "server.ext"
    extensions.write_text("basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n"
                          "extendedKeyUsage=serverAuth\nsubjectAltName=IP:127.0.0.1\n")
    openssl("req", "-new", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256", "-days", "1",
            "-subj", "/CN=Hermes acceptance " + secrets.token_hex(12),
            "-addext", "basicConstraints=critical,CA:TRUE,pathlen:0",
            "-addext", "keyUsage=critical,keyCertSign,cRLSign", "-out", ca, "-keyout", ca_key)
    try:
        openssl("req", "-new", "-newkey", "rsa:2048", "-nodes", "-sha256", "-subj", "/CN=127.0.0.1",
                "-out", csr, "-keyout", key)
        openssl("x509", "-req", "-in", csr, "-CA", ca, "-CAkey", ca_key, "-set_serial",
                str(secrets.randbits(126) + 1), "-days", "1", "-sha256", "-extfile", extensions, "-out", leaf)
    finally:
        ca_key.unlink()
    openssl("verify", "-CAfile", ca, "-purpose", "sslserver", "-verify_ip", "127.0.0.1", leaf)
    return ca, leaf, key


def require_verified_system(device):
    if (device.shell("getprop", "partition.system.verified") != "2"
            or device.shell("getprop", "ro.boot.veritymode") != "enforcing"
            or device.shell("getenforce") != "Enforcing"):
        raise ValueError("Expected the original verified AOSP system and enforcing SELinux")


def require_disposable_phone(device, avd):
    if device.shell("getprop", "ro.boot.qemu.avd_name") != avd:
        raise ValueError("Emulator name differs from the selected disposable AVD")
    expected = {"ro.kernel.qemu": "1", "ro.build.version.sdk": "33", "ro.build.type": "userdebug",
                "ro.product.cpu.abi": "x86_64", "ro.debuggable": "1"}
    if any(device.shell("getprop", key) != value for key, value in expected.items()):
        raise ValueError("Expected the reviewed AOSP API 33 x86_64 userdebug emulator")

    require_verified_system(device)
    packages = set(device.shell("pm", "list", "packages").splitlines())
    excluded = {"package:com.google.android.gms", "package:com.google.android.gsf", "package:com.android.vending",
                "package:org.llm4people.hermes", "package:io.heckel.ntfy"}
    if ("package:com.android.systemui" not in packages or packages & excluded
            or device.shell("am", "get-current-user") != "0"):
        raise ValueError("Expected a fresh Google-free phone with SystemUI on user 0")


def adb_mode(device, mode):
    expected_uid = "0" if mode == "root" else "2000"
    deadline = time.monotonic() + 60
    last_error = None
    while time.monotonic() < deadline:
        try:
            device.run("wait-for-device", timeout=10)
            if device.shell("id", "-u") == expected_uid:
                return
            device.run(mode)
        except RuntimeError as error:
            # adbd may close the root/unroot service before reporting its restart.
            last_error = error
        time.sleep(0.5)
    raise RuntimeError("Could not set the emulator ADB mode to " + mode) from last_error


def reboot_phone(device):
    previous = device.shell("cat", "/proc/sys/kernel/random/boot_id")
    device.run("reboot")
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        try:
            device.run("wait-for-device", timeout=15)
            if (device.shell("getprop", "sys.boot_completed") == "1"
                    and device.shell("cat", "/proc/sys/kernel/random/boot_id") != previous):
                return
        except RuntimeError:
            # adbd can appear twice during a cold framework boot.
            pass
        time.sleep(1)
    raise RuntimeError("The disposable Android image did not finish rebooting")


def build_probe(directory):
    properties = (PROJECT / "gradle.properties").read_text()
    version = re.search(r"^hermesBuildTools=([0-9.]+)$", properties, re.MULTILINE).group(1)
    d8 = Path(os.environ["ANDROID_HOME"]) / "build-tools" / version / "d8"
    subprocess.run(["javac", "--release", "8", "-Xlint:-options", "-d", directory, PROJECT / "ci/PlatformTrust.java"],
                   check=True)
    jar = directory / "probe.jar"
    subprocess.run([d8, "--min-api", "33", "--output", jar, directory / "PlatformTrust.class"], check=True)
    return jar


@contextmanager
def system_trust(device, avd, directory):
    require_disposable_phone(device, avd)
    ca, leaf, key = issue_certificates(directory)
    subject_hash = openssl("x509", "-in", ca, "-noout", "-subject_hash_old").decode().strip()
    if not re.fullmatch(r"[0-9a-f]{8}", subject_hash):
        raise ValueError("Unexpected certificate hash")
    name = subject_hash + ".0"
    anchor = CA_DIRECTORY + "/" + name
    probe = build_probe(directory)
    remote = "/data/local/tmp/hermes-ca-" + secrets.token_hex(12)

    def check(state):
        result = device.shell("env", "CLASSPATH=" + remote + "/probe.jar", "app_process", "/system/bin",
                              "PlatformTrust", state, name, remote + "/ca.pem", remote + "/server.pem")
        if result.splitlines()[-1:] != ["platform-trust-" + state]:
            raise RuntimeError("Platform trust check failed for state " + state)
        decision = "accepted" if state == "present" else "rejected"
        print("Android default trust " + decision + " the loopback server certificate.", file=sys.stderr, flush=True)

    def restore_image():
        adb_mode(device, "root")
        device.run("enable-verity")
        reboot_phone(device)
        adb_mode(device, "unroot")
        require_verified_system(device)
        check("absent")
        print("Restored the original verified Android system.", file=sys.stderr, flush=True)

    def remove_anchor():
        adb_mode(device, "root")
        # A normal reboot by the acceptance runner leaves the existing overlay read-only.
        device.run("remount")
        device.shell("rm", "-f", anchor)

    device.shell("mkdir", remote)
    try:
        for local in (ca, leaf, probe):
            device.run("push", str(local), remote + "/" + local.name)
        check("absent")
        device.shell("test", "!", "-e", anchor)
        was_root = device.shell("id", "-u") == "0"
        try:
            adb_mode(device, "root")
            # Android's API 33 userdebug remount prepares a writable overlay on the next boot.
            try:
                device.run("remount")
                reboot_phone(device)
                adb_mode(device, "root")
                device.run("remount")
                device.run("push", str(ca), anchor)
                device.shell("chmod", "0644", anchor)
                device.shell("restorecon", anchor)
                adb_mode(device, "unroot")
                if device.shell("getenforce") != "Enforcing":
                    raise RuntimeError("Android SELinux must remain enforcing")
                check("present")
                yield {"HERMES_FIXTURE_TLS_CERT": str(leaf), "HERMES_FIXTURE_TLS_KEY": str(key),
                       "HERMES_FIXTURE_TLS_CA": str(ca)}
            finally:
                cleanup_all(remove_anchor, restore_image)
        finally:
            cleanup_all(lambda: adb_mode(device, "root" if was_root else "unroot"))
    finally:
        cleanup_all(lambda: device.shell("rm", "-r", remote))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--avd", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--adb-port", type=int, default=5037)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.serial) or not args.command[:1] == ["--"] or len(args.command) < 2:
        parser.error("select a local emulator serial and pass -- followed by the acceptance command")
    device = Emulator(args.serial, args.adb, args.adb_port)
    with tempfile.TemporaryDirectory(prefix="hermes-aosp-tls-") as tmp, system_trust(device, args.avd, Path(tmp)) as env:
        return subprocess.run(args.command[1:], env=os.environ | env).returncode


if __name__ == "__main__":
    raise SystemExit(main())
