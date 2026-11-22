"""Android screenshot collection must prove complete native pixel data before publishing evidence."""

from pathlib import Path
import struct
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock
import zlib

from run import PNG_SIGNATURE, android_png_size, collect_captures


def chunk(kind, payload=b""):
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload))


def image(compressed, width=1, height=1):
    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return PNG_SIGNATURE + chunk(b"IHDR", header) + chunk(b"IDAT", compressed) + chunk(b"IEND")


class PngCaptureTest(unittest.TestCase):
    def test_valid_filters_and_split_idat(self):
        pixels = b"".join(bytes((n, 0, 3, 11, 255)) for n in range(5))
        compressed = zlib.compress(pixels)
        data = image(compressed, height=5).replace(chunk(b"IDAT", compressed),
                chunk(b"sBIT", b"\x08" * 4) + chunk(b"IDAT", compressed[:4]) + chunk(b"IDAT", compressed[4:]))
        self.assertEqual(android_png_size(data, "capture"), (1, 5))

    def test_rejects_truncated_and_invalid_capture_bytes(self):
        compressed = zlib.compress(bytes(5))
        good = image(compressed)
        bad_crc = bytearray(good)
        bad_crc[31] ^= 1
        for label, invalid in {
            "24-byte header": good[:24], "missing IEND": good[:-12], "CRC mismatch": bytes(bad_crc),
            "no pixels": image(zlib.compress(b"")), "invalid deflate": image(b"invalid"),
            "short pixel row": image(zlib.compress(bytes(4))), "excess pixels": image(zlib.compress(bytes(6))),
            "incomplete deflate": image(compressed[:-1]), "extra deflate stream": image(compressed + compressed),
            "unknown filter": image(zlib.compress(bytes([7, 2, 1, 0, 255]))), "extra bytes": good + b"extra",
        }.items():
            with self.subTest(label=label):
                with self.assertRaisesRegex(ValueError, "capture: invalid Android PNG"):
                    android_png_size(invalid, "capture")

    def test_core_collection_does_not_publish_a_truncated_capture(self):
        phone = Mock()
        name = "22-comparaison-outil.png"
        phone.shell.return_value = name
        phone.run.return_value = image(zlib.compress(bytes(5)))[:24]
        with TemporaryDirectory() as temporary:
            output = Path(temporary)
            with self.assertRaisesRegex(ValueError, name):
                collect_captures(phone, "cache/test", output)
            self.assertEqual(list(output.iterdir()), [])
