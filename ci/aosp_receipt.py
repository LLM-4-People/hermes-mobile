"""Require every device assertion and bind its receipt to the verified package."""

import json
import os
from pathlib import Path
import subprocess
import sys

from publish_release import METADATA, expected_apk_name, validate_recovered
from release_metadata import describe

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from verification.device.run import APP
from verification.gateway_fixture import PINNED_REVISION
from verification.unifiedpush.assets import APK, SERVER


def release_identity(directory, tools, commit):
    manifest = json.loads((directory / METADATA).read_bytes())
    candidate = directory / expected_apk_name(manifest)
    unsigned = directory / "app-release-unsigned.apk"
    validate_recovered(unsigned, candidate, manifest, tools)
    badging = subprocess.check_output([str(Path(tools) / "aapt"), "dump", "badging", candidate], text=True)
    identity = describe(badging, unsigned, candidate, manifest["certificateSha256"], commit)
    if identity != manifest:
        raise ValueError("Candidate identity or package digest differs from the build manifest")
    properties = dict(line.split("=", 1) for line in (ROOT / "gradle.properties").read_text().splitlines() if "=" in line)
    if (identity["applicationId"] != APP or identity["versionName"] != properties["hermesVersionName"]
            or identity["versionCode"] != int(properties["hermesVersionCode"])):
        raise ValueError("Candidate differs from the declared application or release version")
    return identity


def device_receipt(lines, identity, sdk):
    expected = {
        "hermes_revision": PINNED_REVISION,
        "apk_sha256": identity["signedSha256"],
        "sdk": sdk,
        "ntfy_server": SERVER.version,
        "distributor": APK.version,
        "absent_before_delivery": True,
        "webpush_messages": 1,
        "notification_event_matched": True,
        "owning_profile_unlocked": True,
        "owning_thread_opened": True,
        "revoked_delivery_suppressed": True,
    }
    records = []
    for line in lines:
        try:
            record = json.loads(line)
        except json.JSONDecodeError:
            continue
        if type(record) is dict and expected.keys() & record.keys():
            records.append(record)
    if len(records) != 1:
        raise ValueError("Expected exactly one device receipt")
    for field, value in expected.items():
        reported = records[0].get(field)
        if type(reported) is not type(value) or reported != value:
            raise ValueError(f"Device proof failed for {field}")
    return dict(expected, source_commit=identity["sourceCommit"], application_id=identity["applicationId"],
                version_name=identity["versionName"], version_code=identity["versionCode"],
                unsigned_apk_sha256=identity["unsignedSha256"])


if __name__ == "__main__":
    lock = json.loads(Path(__file__).with_name("aosp-phone.json").read_text())
    api_level = lock["package"].split(";")[1].removeprefix("android-")
    manifest = release_identity(Path(sys.argv[1]), os.environ["ANDROID_BUILD_TOOLS"], os.environ["GITHUB_SHA"])
    print(json.dumps(device_receipt(sys.stdin, manifest, api_level), sort_keys=True))
