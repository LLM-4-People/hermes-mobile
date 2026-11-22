"""Build a shell-only Android UI Automator text helper from the pinned project SDK."""

from contextlib import contextmanager
import os
from pathlib import Path
import secrets
import shutil
import subprocess


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
        yield remote_jar
    finally:
        device.shell("rm", "-f", remote_jar, name + ".txt")
