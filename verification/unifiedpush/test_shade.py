"""Only the real notification stack and the enrolled app's visible group may be opened."""
import contextlib
import io
import json
import unittest
from unittest.mock import Mock, patch
from xml.etree.ElementTree import Element, SubElement

from .shade import APP_LABEL, ROW, STACK, SYSTEM_UI, own_group_expander, presentation, shade_focused, show_notice
from .ui import Screen


def shade():
    root = Element("node", {"package": SYSTEM_UI, "bounds": "[0,0][720,1280]"})
    stack = SubElement(root, "node", {"resource-id": STACK, "bounds": "[0,280][720,1250]"})
    return root, stack


class ShadeTest(unittest.TestCase):
    def test_reconnect_and_scroll_until_exact_title_is_rendered(self):
        device, controls = Mock(), Mock()
        device.shell.return_value = "mCurrentFocus=Window{37af7a7 u0 NotificationShade}"
        ui = Screen(device, controls)
        hidden, _ = shade()
        visible, stack = shade()
        title = SubElement(stack, "node", {"text": "Hermes a terminé", "bounds": "[10,300][620,350]"})
        ui.hierarchy = Mock(side_effect=[Element("node", {"package": "private.launcher"}), hidden, visible])
        controls.send.return_value = b"scrolled"
        with patch("verification.unifiedpush.stack.time.sleep"):
            self.assertIs(show_notice(ui, "Hermes a terminé"), title)
        controls.reconnect.assert_called_once_with()
        controls.send.assert_called_once_with("scroll-shade", forward=True)
        device.shell.assert_any_call("cmd", "statusbar", "expand-notifications")

    def test_expand_own_group_without_reading_other_notification_labels_into_diagnostics(self):
        ui = Screen(None, None)
        root, stack = shade()
        own = None
        for label in ("Private app label", APP_LABEL):
            row = SubElement(stack, "node", {"resource-id": ROW})
            SubElement(row, "node", {"text": label})
            own = SubElement(row, "node", {"resource-id": "android:id/expand_button", "bounds": "[2,4][8,12]"})
        self.assertIs(own_group_expander(ui, root), own)
        self.assertEqual(presentation(ui, root, "Hermes a terminé"), {
            "system_ui": True, "notification_stack": True, "notification_rows": 2, "scrollable": False,
            "empty_shade": False, "app_label": True, "event_title": False, "generic_body": False,
        })

    def test_heads_up_with_or_without_stack_does_not_count_as_open_drawer(self):
        for stacked in (True, False):
            with self.subTest(stacked=stacked):
                focus = iter(("mCurrentFocus=Window{abc u0 com.android.launcher3/.Launcher}",
                              "mCurrentFocus=Window{37af7a7 u0 NotificationShade}"))
                device = Mock()
                device.shell.side_effect = lambda *args: next(focus) if args == ("dumpsys", "window") else ""
                ui = Screen(device, Mock())
                heads_up = parent = Element("node", {"package": SYSTEM_UI})
                if stacked:
                    heads_up, parent = shade()
                row = SubElement(parent, "node", {"resource-id": ROW})
                SubElement(row, "node", {"text": "Hermes a terminé"})
                visible, stack = shade()
                title = SubElement(stack, "node", {"text": "Hermes a terminé"})
                ui.hierarchy = Mock(side_effect=[heads_up, visible])
                with patch("verification.unifiedpush.stack.time.sleep"):
                    self.assertIs(show_notice(ui, "Hermes a terminé"), title)
                self.assertEqual(ui.hierarchy.call_count, 2)

    def test_only_named_native_shade_window_qualifies(self):
        device = Mock()
        device.shell.return_value = "mCurrentFocus=Window{beef u0 com.android.systemui/NotificationShade}"
        self.assertTrue(shade_focused(device))
        device.shell.return_value = "mCurrentFocus=Window{beef u0 com.example/NotificationShadePreview}"
        self.assertFalse(shade_focused(device))

    def test_physical_swipe_remains_inside_notification_stack(self):
        device, controls = Mock(), Mock()
        device.shell.return_value = "mCurrentFocus=Window{37af7a7 u0 NotificationShade}"
        controls.send.return_value = b"edge"
        ui = Screen(device, controls)
        first, _ = shade()
        second, stack = shade()
        title = SubElement(stack, "node", {"text": "Hermes a terminé"})
        ui.hierarchy = Mock(side_effect=[first, second])
        with patch("verification.unifiedpush.stack.time.sleep"):
            self.assertIs(show_notice(ui, "Hermes a terminé"), title)
        device.shell.assert_any_call("input", "swipe", "360", "1007", "360", "603", "340")

    def test_show_notice_preserves_primary_error_and_emits_counters_when_diag_shell_fails(self):
        device, controls = Mock(), Mock()

        def shell(*args):
            if args[:2] in (("dumpsys", "window"), ("dumpsys", "power"), ("cmd", "notification")):
                raise RuntimeError("adb offline during diagnostics")
            return ""

        device.shell.side_effect = shell
        ui = Screen(device, controls)
        stderr = io.StringIO()
        with (patch("verification.unifiedpush.shade.eventually", side_effect=TimeoutError("shade timed out")),
              contextlib.redirect_stderr(stderr),
              self.assertRaisesRegex(TimeoutError, "shade timed out")):
            show_notice(ui, "Hermes a terminé")
        output = stderr.getvalue()
        self.assertIn("Android notification shade ", output)
        payload = json.loads(output.split("Android notification shade ", 1)[1].strip())
        self.assertEqual(payload["panel_open_attempts"], 1)
        self.assertEqual(payload["native_scrolls"], 0)
        self.assertEqual(payload["list_swipes"], 0)
        self.assertEqual(payload["group_expansions"], 0)
