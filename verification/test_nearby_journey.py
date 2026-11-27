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

                clock = (10.0 + 3.0 * index for index in range(10))
                with (
                    patch("verification.unifiedpush.stack.time.sleep"),
                    patch("verification.unifiedpush.ui.monotonic", side_effect=clock),
                    redirect_stderr(io.StringIO()),
                ):
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

    def test_ensure_unlocked_skips_unlock_when_grace_kept_workspace_open(self):
        ui = Screen(Mock(), Mock(), lang="en")
        workspace = node_tree((APP, ui.strings["nav_settings"]))
        ui.hierarchy = Mock(return_value=workspace)
        with patch.object(nearby_journey, "unlock") as mock_unlock:
            nearby_journey.ensure_unlocked(ui, "Guide-1", "passphrase-value")
        mock_unlock.assert_not_called()

    def test_ensure_unlocked_unlocks_profile_when_lock_screen_is_shown(self):
        ui = Screen(Mock(), Mock(), lang="en")
        chooser = node_tree((APP, ui.strings["profile_choose_title"]))
        ui.hierarchy = Mock(return_value=chooser)
        with patch.object(nearby_journey, "unlock") as mock_unlock:
            nearby_journey.ensure_unlocked(ui, "Guide-1", "passphrase-value")
        mock_unlock.assert_called_once_with(ui, "Guide-1", "passphrase-value")

    def test_stage_public_location_retries_swallowed_remember_checkbox_until_checked(self):
        for lang in ("en", "fr"):
            with self.subTest(lang=lang):
                device = Mock()
                device.ime_shown.return_value = False
                ui = Screen(device, Mock(), lang=lang)
                s = ui.strings
                consent_title = s["nearby_add_area_title"]
                remember_label = s["nearby_always_profile"]
                use_approx = s["nearby_use_approx"]
                attached = s["nearby_approx_attached"]

                def consent_tree(checked_value):
                    root = Element("hierarchy")
                    SubElement(root, "node", {"package": APP, "text": consent_title, "bounds": "[20,80][300,130]"})
                    SubElement(root, "node", {"package": APP, "text": s["nearby_consent_explain"], "bounds": "[20,140][300,220]"})
                    SubElement(root, "node", {
                        "package": APP, "class": "android.widget.CheckBox", "text": remember_label,
                        "checked": checked_value, "bounds": "[20,240][300,290]",
                    })
                    SubElement(root, "node", {"package": APP, "text": use_approx, "bounds": "[20,310][300,360]"})
                    return root

                unchecked = consent_tree("false")
                checked = consent_tree("true")
                dismissed_with_attached = node_tree((APP, attached))
                # open_consent -> unchecked; remember tap 1 swallowed -> unchecked; remember tap 2 -> checked;
                # choose_consent -> checked -> dismissed_with_attached; ui.wait(attached) -> dismissed_with_attached.
                hierarchies = iter([unchecked, unchecked, unchecked, checked, checked, dismissed_with_attached, dismissed_with_attached])
                ui.hierarchy = Mock(side_effect=lambda: next(hierarchies))
                tapped = []
                ui.tap_node = Mock(side_effect=lambda node: tapped.append(node.get("text")))
                fix = Mock()

                clock = (10.0 + 3.0 * index for index in range(10))
                with (
                    patch("verification.unifiedpush.stack.time.sleep"),
                    patch("verification.unifiedpush.ui.monotonic", side_effect=clock),
                    redirect_stderr(io.StringIO()),
                ):
                    nearby_journey.stage_public_location(ui, fix, system_prompt=False, remember=True)

                fix.publish.assert_called_once()
                self.assertEqual(tapped, [remember_label, remember_label, use_approx])

    def test_enter_message_retries_transient_text_entry_and_open_consent_debounces_back(self):
        device = Mock()
        device.ime_shown.return_value = False
        controls = Mock()
        controls.send.side_effect = [b"retry", b"applied"]
        ui = Screen(device, controls, lang="en")
        s = ui.strings
        composer = Element("hierarchy")
        SubElement(composer, "node", {"package": APP, "text": s["thread_send"], "bounds": "[200,500][300,550]"})
        SubElement(composer, "node", {"package": APP, "class": "android.widget.EditText", "text": "", "bounds": "[20,500][190,550]"})
        ui.hierarchy = Mock(return_value=composer)
        with patch("verification.unifiedpush.stack.time.sleep"):
            nearby_journey.enter_message(ui, "Quels cafés près de moi ?")
        self.assertEqual(controls.send.call_count, 2)

        gms_tree = node_tree((nearby_journey.GMS_PACKAGE, "Improve location accuracy"))
        consent_tree = node_tree((APP, s["nearby_add_area_title"]), (APP, "Hermes and OpenStreetMap"))
        ui.hierarchy = Mock(side_effect=[gms_tree, gms_tree, gms_tree, consent_tree])
        device.shell.reset_mock()
        with patch("verification.unifiedpush.stack.time.sleep"), patch(
            "verification.unifiedpush.ui.monotonic", side_effect=[10.0, 10.1, 10.2]
        ), redirect_stderr(io.StringIO()):
            nearby_journey.open_consent(ui)
        self.assertEqual(device.shell.call_count, 1)


if __name__ == "__main__":
    unittest.main()
