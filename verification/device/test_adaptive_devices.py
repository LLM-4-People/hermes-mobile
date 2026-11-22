import pathlib
import sys
import unittest

VERIFICATION = pathlib.Path(__file__).resolve().parents[1]
if str(VERIFICATION) not in sys.path:
    sys.path.insert(0, str(VERIFICATION))

import adaptive_devices  # noqa: E402


class AdaptiveDevicesDriverTest(unittest.TestCase):
    def test_parse_matrix_extracts_valid_json(self):
        report = (
            "INSTRUMENTATION_RESULT: journey=adaptive passed\n"
            'INSTRUMENTATION_RESULT: matrix={"sameActivity":true,"sameComposer":true,'
            '"initial":{"widthPx":1200,"heightPx":800},"rotated":{"widthPx":800,"heightPx":1200}}\n'
            "INSTRUMENTATION_CODE: -1\n"
        )
        parsed = adaptive_devices.parse_matrix(report)
        self.assertTrue(parsed["sameActivity"])
        self.assertEqual(parsed["initial"]["widthPx"], 1200)

    def test_parse_matrix_rejects_missing_or_duplicate_lines(self):
        with self.assertRaises(ValueError):
            adaptive_devices.parse_matrix("")
        with self.assertRaises(ValueError):
            adaptive_devices.parse_matrix("INSTRUMENTATION_RESULT: journey=adaptive passed\n")
        duplicate = (
            'INSTRUMENTATION_RESULT: matrix={"sameActivity":true}\n'
            'INSTRUMENTATION_RESULT: matrix={"sameActivity":false}\n'
        )
        with self.assertRaises(ValueError):
            adaptive_devices.parse_matrix(duplicate)

    def test_validate_matrices_accepts_preserved_session_and_rejects_regressions(self):
        good_resize = {
            "sameActivity": True,
            "sameComposer": True,
            "initial": {"widthPx": 1200, "heightPx": 800},
            "rotated": {"widthPx": 800, "heightPx": 1200},
        }
        good_large = {"largeText": {"fontScale": 1.5, "widthPx": 1200, "heightPx": 800}}
        adaptive_devices.validate_matrices(good_resize, good_large)

        recreated = dict(good_resize, sameActivity=False)
        with self.assertRaises(AssertionError):
            adaptive_devices.validate_matrices(recreated, good_large)

        lost_composer = dict(good_resize, sameComposer=False)
        with self.assertRaises(AssertionError):
            adaptive_devices.validate_matrices(lost_composer, good_large)

        unscaled = {"largeText": {"fontScale": 1.0, "widthPx": 1200, "heightPx": 800}}
        with self.assertRaises(AssertionError):
            adaptive_devices.validate_matrices(good_resize, unscaled)


if __name__ == "__main__":
    unittest.main()
