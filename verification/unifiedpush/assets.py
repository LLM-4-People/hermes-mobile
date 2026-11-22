"""Pinned upstream artifacts for the disposable Linux x86-64/AOSP acceptance lane."""

from dataclasses import dataclass
import hashlib
from pathlib import Path
import shutil
import tarfile
import tempfile
from urllib.request import Request, urlopen


@dataclass(frozen=True)
class Release:
    project: str
    version: str
    name: str
    checksum: str

    def fetch(self, cache):
        cache.mkdir(mode=0o700, parents=True, exist_ok=True)
        target = cache / self.name
        if target.is_file() and digest(target) == self.checksum:
            return target
        url = f"https://github.com/binwiederhier/{self.project}/releases/download/v{self.version}/{self.name}"
        with tempfile.NamedTemporaryFile(dir=cache, delete=False) as temp:
            part = Path(temp.name)
        try:
            request = Request(url, headers={"User-Agent": "hermes-mobile-verification"})
            with urlopen(request, timeout=60) as response, part.open("wb") as out:
                shutil.copyfileobj(response, out)
            if digest(part) != self.checksum:
                raise RuntimeError(f"Checksum mismatch for {self.name}")
            part.replace(target)
        finally:
            part.unlink(missing_ok=True)
        return target


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


APK = Release(
    "ntfy-android",
    "1.25.2",
    "ntfy-1.25.2-fdroid-release.apk",
    "8a1075c0963caa1e0dc404996531bc641cb2b982f92a4bc692d4999f9eb53f39",
)
SERVER = Release(
    "ntfy",
    "2.38.1",
    "ntfy_2.38.1_linux_amd64.tar.gz",
    "9727d45e5da86c3f338f0f329044485bddde76f3fbbbc3f9e695c14776f09c32",
)


def extract_server(archive, directory):
    binary = directory / "ntfy"
    with tarfile.open(archive) as tar:
        (member,) = [m for m in tar.getmembers() if m.isfile() and Path(m.name).name == "ntfy"]
        with tar.extractfile(member) as src, binary.open("xb") as dst:
            shutil.copyfileobj(src, dst)
    binary.chmod(0o700)
    return binary
