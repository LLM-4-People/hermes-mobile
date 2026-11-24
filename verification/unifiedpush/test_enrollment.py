import io
import json
import unittest
from contextlib import redirect_stderr
from unittest.mock import Mock, call, patch
from xml.etree.ElementTree import Element, SubElement

from .ui import NTFY, PERMISSION_ALLOW_IDS, Screen, TAP_RETRY_COOLDOWN_SECONDS, choose_ntfy


def dialog(title, option):
    root = Element("node")
    SubElement(root, "node", {"text": title})
    return root, SubElement(root, "node", {"text": option})


def permission_dialog(resource_id=PERMISSION_ALLOW_IDS[0]):
    root = Element("node")
    return root, SubElement(root, "node", {"resource-id": resource_id})


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
                # Advance past cooldown between swallowed taps so retries fire deterministically.
                step = TAP_RETRY_COOLDOWN_SECONDS + 0.5
                clock = [step * index for index in range(1, 6)]
                with patch("verification.unifiedpush.stack.time.sleep"), patch(
                    "verification.unifiedpush.ui.monotonic", side_effect=clock
                ):
                    choose_ntfy(ui)
                self.assertEqual(
                    ui.tap_node.call_args_list,
                    [call(original), call(retry), call(target), call(target_retry)],
                )

    def test_debounces_permission_and_picker_taps_during_dismissal_and_retries_after_cooldown(self):
        for lang in ("en", "fr"):
            with self.subTest(lang=lang):
                ui = Screen(None, None, lang=lang)
                s = ui.strings
                perm_first, allow_first = permission_dialog()
                perm_dismissing, _ = permission_dialog()
                perm_swallowed, allow_retry = permission_dialog()
                searching = Element("node")
                SubElement(searching, "node", {"text": s["push_settings_searching_services"]})
                transport_first, up_first = dialog(s["push_settings_service_title"], s["push_settings_enable_up"])
                transport_dismissing, _ = dialog(s["push_settings_service_title"], s["push_settings_enable_up"])
                dist_first, ntfy_first = dialog(s["push_settings_choose_distributor"], "ntfy\n" + NTFY)
                dist_dismissing, _ = dialog(s["push_settings_choose_distributor"], "ntfy\n" + NTFY)
                dismissed = Element("node")
                ui.hierarchy = Mock(
                    side_effect=[
                        perm_first,
                        perm_dismissing,
                        perm_swallowed,
                        searching,
                        transport_first,
                        transport_dismissing,
                        dist_first,
                        dist_dismissing,
                        dismissed,
                    ]
                )
                ui.tap_node = Mock()
                # Consecutive polls 0.1s apart must NOT double-tap; a poll past cooldown retries.
                clock = [
                    10.0,
                    10.1,
                    10.0 + TAP_RETRY_COOLDOWN_SECONDS,
                    12.2,
                    12.3,
                    12.4,
                    12.5,
                    12.6,
                    12.7,
                ]
                with patch("verification.unifiedpush.stack.time.sleep"), patch(
                    "verification.unifiedpush.ui.monotonic", side_effect=clock
                ):
                    choose_ntfy(ui)
                self.assertEqual(
                    ui.tap_node.call_args_list,
                    [call(allow_first), call(allow_retry), call(up_first), call(ntfy_first)],
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
                self.assertEqual(
                    flags,
                    dict(
                        distributor_picker=True,
                        ntfy_option=False,
                        permission_blocked=False,
                        permission_prompt=False,
                        searching=False,
                        transport_picker=False,
                        transport_taps=0,
                        unavailable=True,
                        unifiedpush_option=False,
                    ),
                )
