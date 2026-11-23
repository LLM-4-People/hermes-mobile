"""Resume, verify and publish a signed release, or recover its attested APK."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

from release_metadata import apk_certificate, digest, version_code

APK = "Hermes-Mobile.apk"
METADATA = "release.json"
CHECKSUMS = "SHA256SUMS"
ASSETS = (APK, CHECKSUMS, METADATA)


def gh(*args):
    return subprocess.check_output(["gh", *args])


class Releases:
    def __init__(self, directory):
        self.directory = directory
        self.metadata = json.loads((directory / METADATA).read_bytes())
        self.repo = os.environ["GITHUB_REPOSITORY"]
        self.sha = os.environ["GITHUB_SHA"]
        self.tag = self.metadata["tag"]
        if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?", self.tag):
            raise ValueError("Invalid release tag")
        if self.metadata["sourceCommit"] != self.sha:
            raise ValueError("The build metadata identifies a different commit")
        self.code = version_code(self.metadata["versionCode"])

    def api(self, resource, *options):
        return gh("api", *options, f"repos/{self.repo}/{resource}")

    def release(self, command, *args):
        return gh("release", "--repo", self.repo, command, self.tag, *args)

    def validate_ref(self):
        main = json.loads(self.api("branches/main"))
        if main["commit"]["sha"] != self.sha:
            raise ValueError("The reviewed main revision changed")
        refs = json.loads(self.api("git/matching-refs/tags/" + self.tag))
        if any(row["ref"] == "refs/tags/" + self.tag for row in refs):
            if json.loads(self.api("commits/refs/tags/" + self.tag))["sha"] != self.sha:
                raise ValueError("The tag identifies a different commit")

    def history(self):
        pages = json.loads(self.api("releases?per_page=100", "--paginate", "--slurp"))
        return [release for page in pages for release in page]

    def check_upgrade(self, history):
        for previous in history:
            if previous["draft"] or previous["tag_name"] == self.tag:
                continue
            assets = [a for a in previous["assets"] if a["name"] == METADATA]
            if len(assets) != 1:
                raise ValueError(f"Missing unique build metadata for {previous['tag_name']}")
            asset = assets[0]
            raw = self.api(f"releases/assets/{asset['id']}", "-H", "Accept: application/octet-stream")
            if "sha256:" + hashlib.sha256(raw).hexdigest() != asset["digest"]:
                raise ValueError(f"Build metadata differs from the release digest for {previous['tag_name']}")
            if version_code(json.loads(raw)["versionCode"]) >= self.code:
                raise ValueError(f"versionCode must exceed the published code for {previous['tag_name']}")

    def existing(self, history):
        matches = [r for r in history if r["tag_name"] == self.tag]
        if matches and (len(matches) != 1 or matches[0]["target_commitish"] != self.sha):
            raise ValueError("The existing release identifies another source revision")
        return matches[0] if matches else None

    def snapshot(self):
        view = json.loads(self.release("view", "--json", "isDraft,assets"))
        assets = {entry["name"]: entry["digest"] for entry in view["assets"]}
        if len(assets) != len(view["assets"]) or set(assets) - set(ASSETS):
            raise ValueError("The release contains duplicate or unverified extra assets")
        return view["isDraft"], assets

    def publish(self):
        self.validate_ref()
        history = self.history()
        existing = self.existing(history)
        if not existing or existing["draft"]:
            self.check_upgrade(history)
        if not existing:
            flags = ["--prerelease"] if "-" in self.tag else []
            self.release("create", "--draft", "--target", self.sha, "--title",
                         f"Hermes Mobile {self.tag}", "--generate-notes", *flags)
        draft, remote = self.snapshot()
        expected = {name: "sha256:" + digest(self.directory / name) for name in ASSETS}
        for name, checksum in expected.items():
            if name in remote and remote[name] != checksum:
                raise ValueError(f"Existing release asset {name} differs from the verified bytes")
        if draft:
            for name in ASSETS:
                if name not in remote:
                    self.release("upload", str(self.directory / name))
            self.validate_ref()
            self.check_upgrade(self.history())
        final_draft, final_assets = self.snapshot()
        if final_draft != draft or final_assets != expected:
            raise ValueError("The release assets differ from the complete verified asset set")
        if final_draft:
            self.release("edit", "--draft=false")

    def recover(self, unsigned):
        self.validate_ref()
        if not self.existing(self.history()):
            return
        _, assets = self.snapshot()
        if APK not in assets or assets[APK] == "sha256:" + digest(self.directory / APK):
            return
        with tempfile.TemporaryDirectory(dir=self.directory.parent) as scratch:
            recovered = Path(scratch) / APK
            self.release("download", "--pattern", APK, "--dir", scratch)
            if "sha256:" + digest(recovered) != assets[APK]:
                raise ValueError("Downloaded APK digest differs from the release")
            gh("attestation", "verify", str(recovered), "--repo", self.repo,
               "--source-digest", self.sha, "--signer-workflow", f"{self.repo}/.github/workflows/release.yml")
            validate_recovered(unsigned, recovered, self.metadata, os.environ["ANDROID_BUILD_TOOLS"])
            recovered.replace(self.directory / APK)
        self.metadata["signedSha256"] = digest(self.directory / APK)
        (self.directory / METADATA).write_text(json.dumps(self.metadata, indent=2) + "\n")
        (self.directory / CHECKSUMS).write_text(f"{self.metadata['signedSha256']}  {APK}\n")


def validate_recovered(unsigned, recovered, metadata, tools):
    if digest(unsigned) != metadata["unsignedSha256"]:
        raise ValueError("The unsigned package differs from the verified build input")
    if apk_certificate(tools, recovered) != metadata["certificateSha256"]:
        raise ValueError("The recovered package has an unexpected signer")
    subprocess.run([str(Path(tools) / "zipalign"), "-c", "-P", "16", "4", recovered], check=True)
    with zipfile.ZipFile(unsigned) as source, zipfile.ZipFile(recovered) as restored:
        if source.namelist() != restored.namelist() or len(set(source.namelist())) != len(source.namelist()):
            raise ValueError("The recovered APK entries differ from the unsigned APK")
        for entry in source.infolist():
            if entry.file_size != restored.getinfo(entry.filename).file_size:
                raise ValueError(f"Different entry size for {entry.filename}")
            with source.open(entry) as clean, restored.open(entry.filename) as previous:
                if hashlib.file_digest(clean, "sha256").digest() != hashlib.file_digest(previous, "sha256").digest():
                    raise ValueError(f"Different entry contents for {entry.filename}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--recover-from", metavar="UNSIGNED_APK", type=Path)
    args = parser.parse_args()
    release = Releases(args.directory)
    if args.recover_from:
        release.recover(args.recover_from)
    else:
        release.publish()
