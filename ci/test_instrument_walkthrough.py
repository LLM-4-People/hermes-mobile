"""Verify that the offline viewer only embeds recorded images from the phone journey."""

import base64
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import struct
import sys
from tempfile import TemporaryDirectory
import unittest
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "verification"))
from device.run import PNG_SIGNATURE
from instrument_walkthrough import STEPS, write_walkthrough
from thread_instruments import CAPTURES


def chunk(kind, payload=b""):
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload))


def png(width, height, color):
    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    pixels = (b"\0" + bytes([color, 0, 0]) * width) * height
    return PNG_SIGNATURE + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND")


class ImagesAndLinks(HTMLParser):
    def __init__(self):
        super().__init__()
        self.sources, self.steps = [], []

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if tag == "img":
            self.sources.append(values["src"])
        elif tag == "a":
            self.steps.append(values["href"])


class InstrumentWalkthroughTest(unittest.TestCase):
    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.receipt = self.directory / "thread-instrument.json"
        self.output = self.directory / "walkthrough.html"
        self.expected = dict(CAPTURES)
        # Alternate views share the same numbered journey stage as its overview.
        self.expected.setdefault("22-version-epinglee.png", "Vue de la version épinglée.")
        self.expected.setdefault("18-creer-outil.png", "Action de création dans la discussion.")
        self.images = {}
        records = {}
        for index, (name, caption) in enumerate(self.expected.items()):
            size = (12, 7) if name.startswith("25-") else (4, 8)
            data = self.images[name] = png(*size, index)
            (self.directory / name).write_bytes(data)
            records[name] = {"caption": caption, "width": size[0], "height": size[1],
                             "sha256": hashlib.sha256(data).hexdigest()}
        self.report = {"gateway_revision": "a" * 40, "apk_sha256": "b" * 64, "test_apk_sha256": "c" * 64,
                       "automatically_submitted_turns": 0, "canonical_receipts": [
                           {"status": "completed", "assistant": "PRIVATE_TRANSCRIPT"} for _ in range(4)],
                       "captures": records}
        self.save()

    def save(self):
        self.receipt.write_text(json.dumps(self.report), encoding="utf-8")

    def export(self):
        write_walkthrough(self.directory, self.expected)
        return self.output.read_text(encoding="utf-8")

    def test_stage_order_links_bytes_offline_policy_and_private_fields(self):
        html = self.export()
        view = ImagesAndLinks()
        view.feed(html)
        self.assertEqual(view.steps, [f"#etape-{n}" for n in range(1, 8)])
        self.assertTrue(all(title in html and caption in html for title, caption in STEPS))
        self.assertIn('<html lang="en">', html)
        self.assertIn('>Previous</button>', html)
        self.assertIn('>Next</button>', html)
        self.assertTrue(all(caption not in html for caption in self.expected.values()))
        selected = [next(name for name in self.images if name.startswith(f"{n}-")) for n in range(19, 26)]
        self.assertEqual(view.sources, ["data:image/png;base64," + base64.b64encode(self.images[name]).decode("ascii")
                                        for name in selected])
        self.assertNotIn("PRIVATE_TRANSCRIPT", html)
        script = re.search(r"<script>(.+?)</script>", html, re.DOTALL).group(1)
        signature = base64.b64encode(hashlib.sha256(script.encode()).digest()).decode("ascii")
        self.assertIn(f"script-src 'sha256-{signature}'", html)
        self.assertIn("default-src 'none'; img-src data:", html)

    def test_changed_unselected_image_invalidates_export_and_preserves_previous_file(self):
        previous = self.export()
        (self.directory / "22-version-epinglee.png").write_bytes(png(4, 8, 234))
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            self.export()
        self.assertEqual(self.output.read_text(encoding="utf-8"), previous)

    def test_unexpected_path_and_tampered_caption_are_rejected(self):
        name, entry = next(iter(self.report["captures"].items()))
        self.report["captures"]["../unrelated.png"] = entry
        self.save()
        with self.assertRaisesRegex(ValueError, "Capture names"):
            self.export()
        del self.report["captures"]["../unrelated.png"]
        self.report["captures"][name]["caption"] = "<script>alert(1)</script>"
        self.save()
        with self.assertRaisesRegex(ValueError, "caption"):
            self.export()
        self.assertFalse(self.output.exists())

    def test_requires_a_completed_receipt_and_regular_original_image(self):
        self.report["canonical_receipts"][0]["status"] = "running"
        self.save()
        with self.assertRaisesRegex(ValueError, "completed phone receipt"):
            self.export()
        self.report["canonical_receipts"][0]["status"] = "completed"
        self.save()
        path = self.directory / next(iter(self.images))
        other = self.directory / "some-other-file"
        path.rename(other)
        path.symlink_to(other)
        with self.assertRaisesRegex(ValueError, "Symlink"):
            self.export()

    def test_checks_image_dimensions_metadata_and_trailing_bytes_even_with_matching_hash(self):
        name, original = next(iter(self.images.items()))
        for data in (original + b"hidden", original[:-12] + chunk(b"tEXt", b"unrelated data") + chunk(b"IEND"),
                     png(5, 8, 0), original[:24], original[:-4]):
            with self.subTest(data=data[-18:]):
                (self.directory / name).write_bytes(data)
                self.report["captures"][name]["sha256"] = hashlib.sha256(data).hexdigest()
                self.save()
                with self.assertRaises(ValueError):
                    self.export()
