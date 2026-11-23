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
        for lang in ("en", "fr"):
            with self.subTest(lang=lang):
                ui = Screen(None, None, lang=lang)
                s = ui.strings
                first, original = dialog(s["push_settings_service_title"], s["push_settings_enable_up"])
                second, retry = dialog(s["push_settings_service_title"], s["push_settings_enable_up"])
                third, target = dialog(s["push_settings_choose_distributor"], "ntfy\n" + NTFY)
                fourth, target_retry = dialog(s["push_settings_choose_distributor"], "ntfy\n" + NTFY)
                dismissed = Element("node")
                ui.hierarchy = Mock(side_effect=[first, second, third, fourth, dismissed])
                ui.tap_node = Mock()
                with patch("verification.unifiedpush.stack.time.sleep"):
                    choose_ntfy(ui)
                self.assertEqual(
                    ui.tap_node.call_args_list,
                    [call(original), call(retry), call(target), call(target_retry)],
                )

    def test_reports_real_unavailable_picker_without_tapping_unrelated_controls(self):
        for lang in ("en", "fr"):
            with self.subTest(lang=lang):
                ui = Screen(None, None, lang=lang)
                s = ui.strings
                unrelated, _ = dialog("Fixture-private-title", s["push_settings_enable_up"])
                unavailable, _ = dialog(s["push_settings_choose_distributor"], s["push_settings_install_up"])
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
