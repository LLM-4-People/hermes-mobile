"""Place the verified upstream release where the project's Gradle wrapper expects its ZIP."""

import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile


def wrapper_slot(url):
    # Gradle PathAssembler uses unsigned base-36 MD5 as a cache key. SHA-256 verifies the contents.
    value = int.from_bytes(hashlib.md5(url.encode("utf-8"), usedforsecurity=False).digest(), "big")
    digits = ""
    while value:
        value, remainder = divmod(value, 36)
        digits = "0123456789abcdefghijklmnopqrstuvwxyz"[remainder] + digits
    return digits or "0"


def provision(properties, home):
    options = dict(line.split("=", 1) for line in properties.read_text().splitlines()
                   if line and not line.startswith(("#", "!")))
    url = options["distributionUrl"].replace(r"\:", ":")
    release = re.fullmatch(r"https://services\.gradle\.org/distributions/(gradle-([0-9]+(?:\.[0-9]+)+)-(?:bin|all)\.zip)", url)
    checksum = options["distributionSha256Sum"]
    if not release or not re.fullmatch(r"[0-9a-f]{64}", checksum):
        raise ValueError("Expected an official Gradle release URL and its pinned SHA-256")
    if any(options[key] != "GRADLE_USER_HOME" for key in ("distributionBase", "zipStoreBase")):
        raise ValueError("Expected the project's Gradle user-home wrapper layout")
    if any(options[key] != "wrapper/dists" for key in ("distributionPath", "zipStorePath")):
        raise ValueError("Expected the project's wrapper/dists paths")
    filename, version = release.groups()
    slot = home / options["zipStorePath"] / Path(filename).stem / wrapper_slot(url)
    if (slot / (filename + ".ok")).is_file() and (slot / f"gradle-{version}" / "bin" / "gradle").is_file():
        return
    slot.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=slot) as temporary:
        archive = Path(temporary) / filename
        subprocess.run(["gh", "release", "download", f"v{version}", "--repo", "gradle/gradle-distributions",
                        "--pattern", filename, "--output", str(archive)], check=True, timeout=180)
        with archive.open("rb") as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != checksum:
                raise ValueError("Gradle release differs from the SHA-256 in gradle-wrapper.properties")
        archive.replace(slot / filename)


if __name__ == "__main__":
    provision(Path("gradle/wrapper/gradle-wrapper.properties"),
              Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")))
