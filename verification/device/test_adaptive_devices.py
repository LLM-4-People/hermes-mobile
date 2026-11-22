import pathlib
import sys
import unittest

VERIFICATION = pathlib.Path(__file__).resolve().parents[1]
if str(VERIFICATION) not in sys.path:
    sys.path.insert(0, str(VERIFICATION))

import adaptive_devices  # noqa: E402


class FakeDevice:
    def __init__(self, *, size="Physical size: 1080x2340", density="Physical density: 440",
                 user_rotation="free", setting_rotation="null", config="en-rUS-w392dp-h850dp-port-440dpi"):
        self.size_out = size
        self.density_out = density
        self.rotation_state = user_rotation
        self.setting_rotation = setting_rotation
        self.config = config
        self.commands = []
        self.freeze_config = False

    def shell(self, *args):
        self.commands.append(("shell",) + args)
        if args == ("wm", "size"):
            return self.size_out
        if args == ("wm", "density"):
            return self.density_out
        if args == ("wm", "user-rotation"):
            return self.rotation_state
        if args == ("settings", "get", "system", "user_rotation"):
            return self.setting_rotation
        if args == ("am", "get-config"):
            return self.config
        if args[:2] == ("wm", "size") and len(args) == 3:
            if not self.freeze_config and args[2] == "reset":
                self.config = self.config.replace("-w1200dp-h800dp-", "-w392dp-h850dp-")
            elif not self.freeze_config and args[2] == "800x1200":
                self.config = "en-rUS-w1200dp-h800dp-land-160dpi"
            return ""
        if args[:3] == ("wm", "user-rotation", "lock"):
            self.rotation_state = "lock " + args[3]
            if not self.freeze_config:
                if args[3] in {"0", "2"}:
                    self.config = self.config.replace("-land-", "-port-")
                elif args[3] in {"1", "3"}:
                    self.config = self.config.replace("-port-", "-land-")
            return ""
        if args == ("wm", "user-rotation", "free"):
            self.rotation_state = "free"
            return ""
        return ""

    def setting(self, key, value):
        self.commands.append(("setting", key, value))
        self.setting_rotation = value


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

        with self.assertRaises(AssertionError):
            adaptive_devices.validate_matrices(dict(good_resize, sameActivity=False), good_large)
        with self.assertRaises(AssertionError):
            adaptive_devices.validate_matrices(dict(good_resize, sameComposer=False), good_large)
        with self.assertRaises(AssertionError):
            adaptive_devices.validate_matrices(good_resize, {"largeText": {"fontScale": 1.0}})

    def test_set_tablet_viewport_and_restore_display_drive_back_to_portrait_before_freeing(self):
        device = FakeDevice()
        with adaptive_devices.restore_display(device, timeout=0.5):
            adaptive_devices.set_tablet_viewport(device, width=1200, height=800, density=160, timeout=0.5)
            self.assertEqual(device.rotation_state, "lock 1")
            self.assertIn("-w1200dp-h800dp-", device.config)
        # Ensure we drove rotation back to lock 0 BEFORE releasing to free.
        lock0 = device.commands.index(("shell", "wm", "user-rotation", "lock", "0"))
        free = device.commands.index(("shell", "wm", "user-rotation", "free"))
        self.assertLess(lock0, free)
        self.assertEqual(device.rotation_state, "free")
        self.assertIn("-port-", device.config)
        self.assertIn("-w392dp-h850dp-", device.config)

    def test_restore_display_preserves_locked_override_and_recovers_free_even_on_timeout(self):
        locked = FakeDevice(
            size="Physical size: 1080x2340\nOverride size: 720x1280",
            density="Physical density: 440\nOverride density: 320",
            user_rotation="lock 2",
            setting_rotation="2",
            config="en-rUS-w360dp-h640dp-port-320dpi",
        )
        with adaptive_devices.restore_display(locked, timeout=0.5):
            locked.shell("wm", "user-rotation", "lock", "1")
        self.assertIn(("shell", "wm", "size", "720x1280"), locked.commands)
        self.assertIn(("shell", "wm", "density", "320"), locked.commands)
        self.assertEqual(locked.rotation_state, "lock 2")

        stalled = FakeDevice()
        with self.assertRaises(ExceptionGroup):
            with adaptive_devices.restore_display(stalled, timeout=0.0):
                adaptive_devices.set_tablet_viewport(stalled, timeout=0.5)
                stalled.freeze_config = True
        self.assertIn(("shell", "wm", "user-rotation", "free"), stalled.commands)


if __name__ == "__main__":
    unittest.main()
