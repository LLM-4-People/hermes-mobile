"""Verify the AOSP phone package and revision that the Android SDK installs."""

import argparse
import json
import os
from pathlib import Path
from urllib.request import urlopen
import xml.etree.ElementTree as ET

MANIFEST = "https://dl.google.com/android/repository/sys-img/android/sys-img2-3.xml"


def verify_index(lock, index):
    package = index.find(f"remotePackage[@path='{lock['package']}']")
    if package is None or package.findtext("revision/major") != str(lock["revision"]):
        raise ValueError("The official SDK index no longer offers the reviewed image revision")
    for archive in package.findall("archives/archive/complete"):
        if (archive.findtext("url") == lock["archive"]
                and archive.findtext("checksum") == lock["repositorySha1"]):
            return
    raise ValueError("The official AOSP image archive differs from the reviewed digest")


def verify_installed(lock, sdk_directory):
    _, platform, target, arch = lock["package"].split(";")
    image = sdk_directory / "system-images" / platform / target / arch
    lines = (image / "source.properties").read_text().splitlines()
    properties = dict(line.split("=", 1) for line in lines if "=" in line)
    expected = {"Pkg.Revision": str(lock["revision"]), "SystemImage.Abi": arch,
                "SystemImage.TagId": target, "AndroidVersion.ApiLevel": platform.removeprefix("android-")}
    if any(properties.get(key) != value for key, value in expected.items()):
        raise ValueError("Installed AOSP image differs from its declared package")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--installed", action="store_true", help="check the local SDK after sdkmanager installs the image")
    args = parser.parse_args()
    lock = json.loads(Path(__file__).with_name("aosp-phone.json").read_text())
    _, platform, target, arch = lock["package"].split(";")
    if args.installed:
        verify_installed(lock, Path(os.environ["ANDROID_HOME"]))
        return
    with urlopen(MANIFEST, timeout=30) as response:
        verify_index(lock, ET.parse(response).getroot())
    print(f"sdk={platform.removeprefix('android-')}\ntarget={target}\narch={arch}")


if __name__ == "__main__":
    main()
