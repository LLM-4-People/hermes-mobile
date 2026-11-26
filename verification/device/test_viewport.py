"""Verify display size, density and portrait rotation settle cleanly after thread_instruments.viewport()."""

from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from thread_instruments import set_viewport, viewport


class FakeDevice:
    def __init__(self, *, size="Physical size: 320x640", density="Physical density: 160",
                 rotation="free", user_rotation="null", settle_portrait=True):
        self.size_report = size
        self.density_report = density
        self.rotation = rotation
        self.user_rotation = user_rotation
        self.width, self.height = 320, 640
        self.orientation = "-port-"
        self.settle_portrait = settle_portrait
        self.calls = []
        self.pending_portrait_polls = 0

    def shell(self, *args):
        self.calls.append(args)
        if args == ("wm", "size"):
            return self.size_report
        if args == ("wm", "density"):
            return self.density_report
        if args == ("wm", "user-rotation"):
            return self.rotation
        if args == ("settings", "get", "system", "user_rotation"):
            return self.user_rotation
        if args == ("am", "get-config"):
            if self.pending_portrait_polls > 0:
                self.pending_portrait_polls -= 1
                return "config: en-rUS-sw320dp-w640dp-h320dp-land-mdpi-v36"
            return f"config: en-rUS-sw320dp-w{self.width}dp-h{self.height}dp{self.orientation}mdpi-v36"
        if args[:2] == ("wm", "size") and len(args) == 3:
            if args[2] == "reset":
                self.width, self.height = (640, 320) if self.orientation == "-land-" else (320, 640)
            else:
                a, b = (int(v) for v in args[2].split("x"))
                self.width, self.height = (b, a) if self.orientation == "-land-" else (a, b)
            return ""
        if args[:2] == ("wm", "density") and len(args) == 3:
            return ""
        if args[:3] == ("settings", "put", "system") and args[3] == "user_rotation":
            self.user_rotation = args[4]
            return ""
        if args[:3] == ("wm", "user-rotation", "lock"):
            self.rotation = "lock " + args[3]
            if args[3] == "1":
                self.orientation = "-land-"
                self.width, self.height = max(self.width, self.height), min(self.width, self.height)
            elif self.settle_portrait:
                self.orientation = "-port-"
                self.width, self.height = min(self.width, self.height), max(self.width, self.height)
                self.pending_portrait_polls = 1
            return ""
        if args == ("wm", "user-rotation", "free"):
            self.rotation = "free"
            return ""
        raise AssertionError(f"Unexpected shell call: {args!r}")

    def setting(self, key, value):
        self.calls.append(("setting", key, value))
        self.user_rotation = value


class ViewportRestorationTest(unittest.TestCase):
    def test_free_portrait_emulator_is_driven_back_to_portrait_before_freeing(self):
        device = FakeDevice(rotation="free", user_rotation="null")
        with patch("device.viewport.time.sleep"):
            with viewport(device):
                set_viewport(device, wide=True)
                self.assertEqual(device.orientation, "-land-")
        self.assertEqual(device.rotation, "free")
        self.assertEqual(device.user_rotation, "null")
        self.assertEqual(device.orientation, "-port-")
        # Cleanup must explicitly lock rotation 0 before releasing to free.
        cleanup_start = device.calls.index(("wm", "size", "reset"))
        cleanup = device.calls[cleanup_start:]
        lock_index = cleanup.index(("wm", "user-rotation", "lock", "0"))
        setting_index = cleanup.index(("setting", "user_rotation", "null"))
        free_index = cleanup.index(("wm", "user-rotation", "free"))
        self.assertLess(lock_index, setting_index)
        self.assertLess(setting_index, free_index)
        self.assertIn(("am", "get-config"), cleanup[free_index:])

    def test_locked_portrait_override_restores_original_size_density_and_lock(self):
        device = FakeDevice(
            size="Physical size: 1080x2340\nOverride size: 320x640",
            density="Physical density: 440\nOverride density: 160",
            rotation="lock 0",
            user_rotation="0",
        )
        with patch("device.viewport.time.sleep"):
            with viewport(device):
                set_viewport(device, wide=True)
        self.assertEqual(device.rotation, "lock 0")
        self.assertEqual(device.orientation, "-port-")
        self.assertIn(("wm", "size", "320x640"), device.calls)
        self.assertIn(("wm", "density", "160"), device.calls)

    def test_failed_portrait_settlement_raises_diagnostic_while_still_restoring_free_mode(self):
        device = FakeDevice(rotation="free", user_rotation="null", settle_portrait=False)
        ticks = iter(range(0, 1000, 15))
        with patch("device.viewport.time.sleep"), patch("device.viewport.time.monotonic",
                                                        side_effect=lambda: float(next(ticks))):
            with self.assertRaises(ExceptionGroup) as group:
                with viewport(device):
                    # Enter with wide=False already portrait, then switch to wide=True so restore stays stuck in land.
                    set_viewport(device, wide=True)
        self.assertEqual(device.rotation, "free")
        self.assertEqual(device.user_rotation, "null")
        messages = [str(err) for err in group.exception.exceptions]
        self.assertTrue(any("-port-" in msg and "-land-" in msg for msg in messages), messages)


if __name__ == "__main__":
    unittest.main()
