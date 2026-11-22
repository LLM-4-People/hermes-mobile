"""Guard the full Google-free phone image used to test notification taps."""

import json
from pathlib import Path
import tempfile
import unittest
from xml.etree import ElementTree as ET

from aosp_phone import verify_index, verify_installed


class ImageLockTest(unittest.TestCase):
    def setUp(self):
        self.lock = json.loads(Path(__file__).with_name("aosp-phone.json").read_text())

    def test_changed_archive_or_revision_stops_the_job(self):
        xml = ET.fromstring(
            f'<repository><remotePackage path="{self.lock["package"]}"><revision><major>'
            f'{self.lock["revision"]}</major></revision><archives><archive><complete>'
            f'<url>{self.lock["archive"]}</url><checksum>{self.lock["repositorySha1"]}</checksum>'
            '</complete></archive></archives></remotePackage></repository>'
        )
        verify_index(self.lock, xml)
        for path in ["revision/major", "archives/archive/complete/checksum"]:
            field = xml.find("remotePackage/" + path)
            with self.subTest(path=path), self.assertRaises(ValueError):
                original = field.text
                field.text = "changed"
                verify_index(self.lock, xml)
            field.text = original

    def test_google_apis_or_atd_image_rejected_at_install(self):
        _, platform, target, arch = self.lock["package"].split(";")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            package = root / "system-images" / platform / target / arch
            package.mkdir(parents=True)
            for tag in [target, "google_apis", "aosp_atd"]:
                (package / "source.properties").write_text(
                    f"Pkg.Revision={self.lock['revision']}\nSystemImage.Abi={arch}\n"
                    f"SystemImage.TagId={tag}\nAndroidVersion.ApiLevel={platform.removeprefix('android-')}\n"
                )
                if tag == target:
                    verify_installed(self.lock, root)
                else:
                    with self.subTest(tag=tag), self.assertRaises(ValueError):
                        verify_installed(self.lock, root)
