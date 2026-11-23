"""Deterministic tests for nearby journey consent and Google Play Services overlay dismissal."""

from contextlib import redirect_stderr
import io
import json
import unittest
from unittest.mock import Mock, patch
from xml.etree.ElementTree import Element, SubElement

from verification import nearby_journey
from verification.unifiedpush.ui import APP, Screen


def node_tree(*items):
    """Build an accessibility tree from (package, text) pairs."""
    root = Element("hierarchy")
    for index, (package, text) in enumerate(items):
        y = 100 + index * 60
        SubElement(root, "node", {"package": package, "text": text, "bounds": f"[20,{y}][300,{y + 48}]"})
    return root


class NearbyJourneyConsentTest(unittest.TestCase):
    def test_open_consent_dismisses_gms_location_off_warning_and_opens_consent(self):
        for lang in ("en", "fr"):
            with self.subTest(lang=lang):
                device = Mock()
                device.ime_shown.return_value = False
                shell_calls = []

                def shell(*args):
                    shell_calls.append(args)
                    return ""

                device.shell.side_effect = shell
                ui = Screen(device, Mock(), lang=lang)
                send_btn = ui.strings["thread_send"]
                consent_title = ui.strings["nearby_add_area_title"]

                gms_warning = node_tree((nearby_journey.GMS_PACKAGE, "Location is off"))
                gms_with_systemui = node_tree(
                    ("com.android.systemui", "12:00"),
                    (nearby_journey.GMS_PACKAGE, "Location is off"),
                )
                thread = node_tree((APP, send_btn))
                consent = node_tree(
                    (APP, consent_title),
                    (APP, "Partager avec Hermes et OpenStreetMap"),
                )
                hierarchies = iter([gms_warning, gms_with_systemui, thread, consent])
                ui.hierarchy = Mock(side_effect=lambda: next(hierarchies))

                with patch("verification.unifiedpush.stack.time.sleep"), redirect_stderr(io.StringIO()):
                    result = nearby_journey.open_consent(ui)

                self.assertIs(result, consent)
                back_calls = [call for call in shell_calls if call == ("input", "keyevent", "BACK")]
                tap_calls = [call for call in shell_calls if call[:2] == ("input", "tap")]
                self.assertEqual(len(back_calls), 2)
                self.assertEqual(len(tap_calls), 1)

    def test_open_consent_does_not_dismiss_when_app_or_non_gms_package_is_visible(self):
        device = Mock()
        device.ime_shown.return_value = False
        shell_calls = []
        device.shell.side_effect = lambda *args: (shell_calls.append(args), "")[1]
        ui = Screen(device, Mock(), lang="fr")
        send_btn = ui.strings["thread_send"]
        consent_title = ui.strings["nearby_add_area_title"]

        permission_dialog = node_tree(("com.google.android.permissioncontroller", "Allow location"))
        app_with_gms_node = node_tree((APP, send_btn), (nearby_journey.GMS_PACKAGE, "Autofill"))
        consent = node_tree((APP, consent_title), (APP, "Hermes + OpenStreetMap"))
        hierarchies = iter([permission_dialog, app_with_gms_node, consent])
        ui.hierarchy = Mock(side_effect=lambda: next(hierarchies))

        with patch("verification.unifiedpush.stack.time.sleep"), redirect_stderr(io.StringIO()):
            result = nearby_journey.open_consent(ui)

        self.assertIs(result, consent)
        self.assertNotIn(("input", "keyevent", "BACK"), shell_calls)
        self.assertEqual(sum(1 for call in shell_calls if call[:2] == ("input", "tap")), 1)

    def test_open_consent_reports_gms_dismissals_in_diagnostics_on_timeout(self):
        device = Mock()
        device.ime_shown.return_value = False
        device.shell.return_value = ""
        ui = Screen(device, Mock(), lang="fr")
        gms_warning = node_tree((nearby_journey.GMS_PACKAGE, "Location is off"))
        ui.hierarchy = Mock(return_value=gms_warning)

        def fail_after_one_attempt(label, check, *args, **kwargs):
            check()
            raise AssertionError("Timed out waiting for " + label)

        stderr = io.StringIO()
        with (
            patch.object(nearby_journey, "eventually", side_effect=fail_after_one_attempt),
            patch.object(nearby_journey, "focused", return_value="com.google.android.gms/.location.settings.LocationOffWarningActivity"),
            patch.object(nearby_journey, "location_access", return_value=False),
            redirect_stderr(stderr),
        ):
            with self.assertRaises(AssertionError):
                nearby_journey.open_consent(ui)

        lines = [line for line in stderr.getvalue().splitlines() if line.startswith("Android open_consent ")]
        self.assertEqual(len(lines), 1)
        payload = json.loads(lines[0].removeprefix("Android open_consent "))
        self.assertEqual(payload["gms_dismissals"], 1)
        self.assertEqual(payload["taps"], 0)
        self.assertEqual(payload["packages"], [nearby_journey.GMS_PACKAGE])


if __name__ == "__main__":
    unittest.main()
