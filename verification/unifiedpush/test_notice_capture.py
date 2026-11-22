import io
from importlib.util import find_spec
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock, patch
from xml.etree.ElementTree import Element, SubElement

from .shade import BODY, ROW, SYSTEM_UI
from .ui import Screen, open_notice


TITLE = "Hermes a terminé"
FOCUS = "mCurrentFocus=Window{37af7a7 u0 NotificationShade}"
WINDOW = "Window{37af7a7 u0 NotificationShade}"


def snapshot(package=SYSTEM_UI, window="17", heading="[1,1][6,3]", body="[2,4][8,6]"):
    root = Element("node", {"package": package, "window-id": window})
    row = SubElement(root, "node", {"resource-id": ROW})
    SubElement(row, "node", {"text": TITLE, "bounds": heading})
    SubElement(row, "node", {"text": BODY, "bounds": body})
    return root


def attempt_once(description, attempt):
    if not attempt():
        raise AssertionError(description)


class CaptureTest(unittest.TestCase):
    def setUp(self):
        self.ui = Screen(Mock(), Mock())
        self.ui.device.shell.return_value = FOCUS
        self.ui.controls.send.return_value = b"tapped"
        self.directory = Path(self.enterContext(TemporaryDirectory()))
        self.enterContext(patch("verification.unifiedpush.ui.show_notice", return_value=snapshot()))
        self.enterContext(patch("verification.unifiedpush.ui.eventually", side_effect=attempt_once))

    def test_foreign_focused_root_with_same_label_cannot_supply_pixels(self):
        self.ui.snapshot = Mock(return_value=snapshot(package="private.fixture"))
        with self.assertRaisesRegex(AssertionError, "stable focused"):
            open_notice(self.ui, TITLE, self.directory)
        self.ui.device.run.assert_not_called()
        self.ui.controls.send.assert_not_called()
        self.assertEqual(list(self.directory.iterdir()), [])

    def test_passive_capture_check_never_dismisses_a_covering_system_dialog(self):
        self.ui.controls.send.return_value = (
            b'<node package="android"><node text="System UI is not responding"/>'
            b'<node resource-id="android:id/aerr_wait" bounds="[1,1][9,5]"/></node>'
        )
        with self.assertRaisesRegex(AssertionError, "stable focused"):
            open_notice(self.ui, TITLE, self.directory)
        self.ui.controls.send.assert_called_once_with("tree")
        self.ui.device.shell.assert_not_called()
        self.ui.device.run.assert_not_called()
        self.assertEqual(list(self.directory.iterdir()), [])

    def test_screencap_is_discarded_on_window_focus_or_either_label_change(self):
        for changed, focus in (
            (snapshot(package="private.fixture"), FOCUS),
            (snapshot(window="18"), FOCUS),
            (snapshot(), "mCurrentFocus=Window{123 u0 NotificationShade}"),
            (snapshot(), "mCurrentFocus=Window{def u0 com.android.launcher3/.Launcher}"),
            (snapshot(heading="[2,1][7,3]"), FOCUS),
            (snapshot(body="[3,5][9,7]"), FOCUS),
        ):
            with self.subTest(changed=changed, focus=focus):
                self.ui.snapshot = Mock(side_effect=[snapshot(), changed])
                self.ui.device.shell.side_effect = [FOCUS, focus]
                with self.assertRaisesRegex(AssertionError, "stable focused"):
                    open_notice(self.ui, TITLE, self.directory)
                self.ui.controls.send.assert_not_called()
                self.assertEqual(list(self.directory.iterdir()), [])

    def test_window_replaced_between_screencap_and_physical_tap(self):
        self.ui.snapshot = Mock(side_effect=[snapshot(), snapshot(), snapshot(package="private.fixture")])
        images = (Mock(), Mock())
        with patch("verification.unifiedpush.ui.masked_notice", return_value=images):
            with self.assertRaisesRegex(AssertionError, "stable focused"):
                open_notice(self.ui, TITLE, self.directory)
        self.ui.controls.send.assert_not_called()
        self.assertEqual(list(self.directory.iterdir()), [])

    def test_native_last_moment_rejection_discards_staged_evidence(self):
        self.ui.snapshot = Mock(side_effect=[snapshot(), snapshot(), snapshot()])
        self.ui.controls.send.return_value = b"retry"
        with patch("verification.unifiedpush.ui.masked_notice", return_value=(Mock(), Mock())):
            with self.assertRaisesRegex(AssertionError, "stable focused"):
                open_notice(self.ui, TITLE, self.directory)
        self.ui.controls.send.assert_called_once_with("tap-notice", title=TITLE, window=17, focus=WINDOW, bounds=(1, 1, 6, 3))
        self.assertEqual(list(self.directory.iterdir()), [])

    def test_without_capture_tap_uses_fresh_current_window_and_bounds(self):
        self.ui.snapshot = Mock(return_value=snapshot(window="75", heading="[20,21][40,45]"))
        open_notice(self.ui, TITLE)
        self.ui.controls.send.assert_called_once_with("tap-notice", title=TITLE, window=75, focus=WINDOW, bounds=(20, 21, 40, 45))

    @unittest.skipUnless(find_spec("PIL"), "optional screenshot evidence requires Pillow")
    def test_stable_capture_writes_only_pixels_in_known_label_rectangles(self):
        from PIL import Image

        self.ui.snapshot = Mock(side_effect=[snapshot(), snapshot(), snapshot()])
        source = Image.new("RGB", (12, 9), "red")
        source.paste("white", (1, 1, 6, 3))
        source.paste("yellow", (2, 4, 8, 6))
        buffer = io.BytesIO()
        source.save(buffer, "PNG")
        self.ui.device.run.return_value = buffer.getvalue()
        open_notice(self.ui, TITLE, self.directory)
        self.ui.controls.send.assert_called_once_with("tap-notice", title=TITLE, window=17, focus=WINDOW, bounds=(1, 1, 6, 3))
        with Image.open(self.directory / "native-notification-shade.png") as saved:
            self.assertEqual(saved.size, (12, 9))
            self.assertEqual(sorted(saved.getcolors()), [(10, (255, 255, 255)), (12, (255, 255, 0)), (86, (0, 0, 0))])
        with Image.open(self.directory / "native-notification-labels.png") as cropped:
            self.assertEqual(cropped.size, (7, 5))
            self.assertNotIn((255, 0, 0), [color for _, color in cropped.getcolors()])
