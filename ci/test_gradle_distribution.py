import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from gradle_distribution import provision, wrapper_slot


class DistributionTest(unittest.TestCase):
    url = "https://services.gradle.org/distributions/gradle-9.8.2-bin.zip"
    properties = Path(__file__).resolve().parents[1] / "gradle/wrapper/gradle-wrapper.properties"

    def test_slot_matches_gradle_path_assembler(self):
        self.assertEqual(wrapper_slot(self.url), "8wepis63pzuhrlfaiy04bagwu")

    def test_only_verified_bytes_are_published_for_the_real_wrapper(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = b"fixture"
            checksum = hashlib.sha256(archive).hexdigest()
            config = root / "fixture.properties"
            config.write_text(self.properties.read_text().replace(
                next(line for line in self.properties.read_text().splitlines() if line.startswith("distributionSha256Sum=")),
                "distributionSha256Sum=" + checksum))

            def download(arguments, **kwargs):
                self.assertEqual(arguments[1:3], ["release", "download"])
                self.assertEqual(arguments[arguments.index("--repo") + 1], "gradle/gradle-distributions")
                Path(arguments[-1]).write_bytes(archive)

            with patch("gradle_distribution.subprocess.run", side_effect=download):
                with self.assertRaises(ValueError):
                    provision(self.properties, root)
                self.assertEqual(list(root.rglob("*.zip")), [])
                provision(config, root)
            (saved,) = root.rglob("*.zip")
            self.assertEqual(saved.read_bytes(), archive)
            self.assertEqual(list(root.rglob("*.ok")), [])
