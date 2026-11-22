"""Only the real notification stack and the enrolled app's visible group may be opened."""
import unittest
from unittest.mock import Mock, patch
from xml.etree.ElementTree import Element, SubElement

from .shade import APP_LABEL, ROW, STACK, SYSTEM_UI, own_group_expander, presentation, show_notice
from .ui import Screen


def shade():
    root = Element("node", {"package": SYSTEM_UI, "bounds": "[0,0][720,1280]"})
    stack = SubElement(root, "node", {"resource-id": STACK, "bounds": "[0,280][720,1250]"})
    return root, stack


class ShadeTest(unittest.TestCase):
    def test_reconnect_and_scroll_until_exact_title_is_rendered(self):
        device, controls = Mock(), Mock()
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

    def test_floating_notification_does_not_count_as_the_open_drawer(self):
        device = Mock()
        device.shell.return_value = "mCurrentFocus=Window{Launcher}"
        ui = Screen(device, Mock())
        heads_up = Element("node", {"package": SYSTEM_UI})
        SubElement(heads_up, "node", {"text": "Hermes a terminé"})
        visible, stack = shade()
        title = SubElement(stack, "node", {"text": "Hermes a terminé"})
        ui.hierarchy = Mock(side_effect=[heads_up, visible])
        with patch("verification.unifiedpush.stack.time.sleep"):
            self.assertIs(show_notice(ui, "Hermes a terminé"), title)
        device.shell.assert_any_call("dumpsys", "window")

    def test_physical_swipe_remains_inside_notification_stack(self):
        device, controls = Mock(), Mock()
        controls.send.return_value = b"edge"
        ui = Screen(device, controls)
        first, _ = shade()
        second, stack = shade()
        title = SubElement(stack, "node", {"text": "Hermes a terminé"})
        ui.hierarchy = Mock(side_effect=[first, second])
        with patch("verification.unifiedpush.stack.time.sleep"):
            self.assertIs(show_notice(ui, "Hermes a terminé"), title)
        device.shell.assert_any_call("input", "swipe", "360", "1007", "360", "603", "340")
