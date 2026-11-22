import unittest
from unittest.mock import Mock

from installed import installed_apk_sha256


class InstalledPackageTest(unittest.TestCase):
    path = "/data/app/~~p3T=/org.example-za_10==/base.apk"
    digest = "a" * 64

    def test_digest_is_read_from_installed_package(self):
        device = Mock()
        device.shell.side_effect = ["package:" + self.path, self.digest + "  " + self.path]
        self.assertEqual(installed_apk_sha256(device, "org.example"), self.digest)
        self.assertEqual(device.shell.call_args.args, ("sha256sum", self.path))

    def test_split_unknown_or_malformed_paths_fail_before_hash(self):
        for path in ["package:/other/base.apk", "package:" + self.path + "\npackage:/split.apk", "missing"]:
            device = Mock()
            device.shell.return_value = path
            with self.subTest(path=path), self.assertRaises(ValueError):
                installed_apk_sha256(device, "org.example")
            device.shell.assert_called_once()

    def test_missing_or_wrong_on_device_digest_fails(self):
        for response in ["denied", self.digest + "  /other.apk", "0  " + self.path]:
            device = Mock()
            device.shell.side_effect = ["package:" + self.path, response]
            with self.subTest(response=response), self.assertRaises(ValueError):
                installed_apk_sha256(device, "org.example")
