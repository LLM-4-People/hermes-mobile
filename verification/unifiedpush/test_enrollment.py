import io
import json
import unittest
from contextlib import redirect_stderr
from unittest.mock import Mock, call, patch
from xml.etree.ElementTree import Element, SubElement

from .ui import NTFY, Screen, choose_ntfy


def dialog(title, option):
    root = Element("node")
    SubElement(root, "node", {"text": title})
    return root, SubElement(root, "node", {"text": option})


class EnrollmentTest(unittest.TestCase):
    def test_retries_swallowed_provider_tap_and_selects_only_the_named_distributor(self):
        ui = Screen(None, None)
        first, original = dialog("Service de notification", "Activer avec UnifiedPush")
        second, retry = dialog("Service de notification", "Activer avec UnifiedPush")
        third, target = dialog("Choisir un distributeur", "ntfy\n" + NTFY)
        ui.hierarchy = Mock(side_effect=[first, second, third])
        ui.tap_node = Mock()
        with patch("verification.unifiedpush.stack.time.sleep"):
            choose_ntfy(ui)
        self.assertEqual(ui.tap_node.call_args_list, [call(original), call(retry), call(target)])

    def test_reports_real_unavailable_picker_without_tapping_unrelated_controls(self):
        ui = Screen(None, None)
        unrelated, _ = dialog("Fixture-private-title", "Activer avec UnifiedPush")
        unavailable, _ = dialog("Choisir un distributeur",
                                "Installez ou configurez un distributeur UnifiedPush, par exemple ntfy, puis réessayez.")
        ui.hierarchy = Mock(side_effect=[unrelated, unavailable])
        ui.tap_node = Mock()
        stderr = io.StringIO()
        with patch("verification.unifiedpush.stack.time.sleep"), redirect_stderr(stderr):
            with self.assertRaisesRegex(AssertionError, "no available"):
                choose_ntfy(ui)
        ui.tap_node.assert_not_called()
        flags = json.loads(stderr.getvalue().removeprefix("Android enrollment "))
        self.assertEqual(flags, dict(distributor_picker=True, ntfy_option=False, transport_picker=False,
                                     transport_taps=0, unavailable=True, unifiedpush_option=False))
