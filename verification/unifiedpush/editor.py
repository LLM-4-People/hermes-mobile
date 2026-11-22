"""Use a single external Android SDK UI automation session for the entire release proof."""

from contextlib import contextmanager
import base64
import json
import os
from pathlib import Path
import secrets
import select
import shutil
import subprocess
import time

from verification.device.run import cleanup_all
from .processes import close_child


OBSERVER_GRACE_SECONDS = 30


class AndroidControls:
    """Keep the official SDK UI automation connected independently of both application processes."""

    def __init__(self, command):
        self.command = command
        self.process = None

    def connect(self):
        self.process = subprocess.Popen(self.command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, bufsize=0)
        if self.answer() != b"ready":
            raise RuntimeError("Android SDK UI session did not initialize")

    def close(self):
        if self.process is None:
            return
        process = self.process
        try:
            # Timed communicate covers a blocked input pipe and drains the observer's final output.
            close_child(process, lambda: process.communicate(
                b'{"action":"finish"}\n', timeout=OBSERVER_GRACE_SECONDS))
        finally:
            self.process = None

    def reconnect(self):
        self.close()
        self.connect()

    def answer(self):
        limit = time.monotonic() + 35
        while time.monotonic() < limit:
            if not select.select([self.process.stdout], [], [], max(0, limit - time.monotonic()))[0]:
                break
            line = self.process.stdout.readline()
            if not line:
                raise RuntimeError("Android SDK UI session exited before returning a result")
            if line.startswith(b"HM_UI "):
                return base64.b64decode(line[6:].strip(), validate=True)
        raise TimeoutError("Android SDK UI session did not respond within 35 seconds")

    def send(self, action, **fields):
        self.process.stdin.write((json.dumps(dict(action=action, **fields)) + "\n").encode())
        self.process.stdin.flush()
        return self.answer()


@contextmanager
def editor(device, directory):
    supplied = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    location = Path(shutil.which(device.command[0]) or device.command[0]).resolve()
    sdk = Path(supplied) if supplied else location.parent.parent
    properties = Path(__file__).resolve().parents[2] / "gradle.properties"
    versions = dict(line.strip().split("=", 1) for line in properties.read_text().splitlines() if "=" in line)
    platform = sdk / "platforms" / ("android-" + versions["hermesCompileSdk"])
    d8 = sdk / "build-tools" / versions["hermesBuildTools"] / "d8"
    libs = [
        platform / "android.jar",
        platform / "uiautomator.jar",
        platform / "optional/android.test.base.jar",
    ]
    classes = directory / "classes"
    classes.mkdir()
    subprocess.run(
        [
            "javac",
            "--release",
            "8",
            "-cp",
            os.pathsep.join(map(str, libs)),
            "-d",
            classes,
            Path(__file__).with_name("FocusedText.java"),
        ],
        check=True,
    )
    jar = directory / "focused-text.jar"
    subprocess.run([d8, "--min-api", "33", "--output", jar, *classes.rglob("*.class")], check=True)
    name = "/data/local/tmp/hm-editor-" + secrets.token_hex(12)
    remote_jar = name + ".jar"
    try:
        device.run("push", str(jar), remote_jar)
        device.shell("chmod", "444", remote_jar)
        command = device.command + ["shell", "-T", "uiautomator", "runtest", remote_jar, "-c",
                                    "org.llm4people.hermes.verification.FocusedText", "-s"]
        controls = AndroidControls(command)
        try:
            controls.connect()
            yield controls
        finally:
            controls.close()
    finally:
        cleanup_all(lambda: device.shell("rm", "-f", remote_jar))
