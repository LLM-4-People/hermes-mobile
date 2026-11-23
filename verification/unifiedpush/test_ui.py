"""Unit tests for Screen bounds parsing, focus inspection, and verified tap transitions."""

import unittest
from unittest.mock import Mock, call, patch
from xml.etree.ElementTree import Element, SubElement

from .ui import NTFY, Screen, enroll, focused_window, start_account


def tree(*specs, package="org.llm4people.hermes", window_id="10"):
    root = Element("node", {"package": package, "window-id": window_id})
    for attrs in specs:
        SubElement(root, "node", attrs)
    return root


class ScreenUiTest(unittest.TestCase):
    def test_tap_node_taps_center_and_clamps_partially_negative_bounds(self):
        device = Mock()
        ui = Screen(device, Mock())
        ui.tap_node(Element("node", {"bounds": "[10,20][110,60]"}))
        ui.tap_node(Element("node", {"bounds": "[-20,-10][80,50]"}))
        self.assertEqual(
            device.shell.call_args_list,
            [
                call("input", "tap", "60", "40"),
                call("input", "tap", "40", "25"),
            ],
        )

    def test_tap_node_rejects_malformed_empty_inverted_or_offscreen_bounds(self):
        device = Mock()
        ui = Screen(device, Mock())
        for raw in ("", "[1,2]", "[10,10][10,20]", "[50,20][10,40]", "[-40,10][-5,30]", "[10,-40][50,0]"):
            with self.subTest(bounds=raw):
                with self.assertRaises(ValueError):
                    ui.tap_node(Element("node", {"bounds": raw}))
        device.shell.assert_not_called()

    def test_scroll_clamps_negative_bounds_and_skips_invalid_containers(self):
        device = Mock()
        ui = Screen(device, Mock())
        root = tree(
            {"scrollable": "true", "bounds": "[0,0][0,0]"},
            {"scrollable": "true", "bounds": "[-10,-20][100,100]"},
        )
        ui.scroll(root)
        device.shell.assert_called_once_with("input", "swipe", "50", "80", "50", "33", "260")

    def test_focused_window_parses_non_null_window_and_rejects_null_focus(self):
        device = Mock()
        device.shell.return_value = "  mCurrentFocus=Window{abc123 u0 org.llm4people.hermes/org.llm4people.hermes.MainActivity}\n"
        self.assertEqual(
            focused_window(device),
            "Window{abc123 u0 org.llm4people.hermes/org.llm4people.hermes.MainActivity}",
        )
        for output in ("  mCurrentFocus=null\n", "no focus line\n", ""):
            with self.subTest(output=output):
                device.shell.return_value = output
                self.assertIsNone(focused_window(device))

    def test_tap_until_skips_tap_when_already_ready(self):
        ui = Screen(Mock(), Mock())
        ui.hierarchy = Mock(return_value=tree({"text": "Done", "bounds": "[0,0][10,10]"}))
        ui.tap_node = Mock()
        result = ui.tap_until("Trigger", lambda r: ui.node("Done", r) is not None)
        self.assertTrue(result)
        ui.tap_node.assert_not_called()

    def test_tap_until_retries_swallowed_tap_and_scrolls_when_control_is_offscreen(self):
        ui = Screen(Mock(), Mock())
        offscreen = tree({"scrollable": "true", "bounds": "[0,0][320,640]"})
        first_visible = tree({"text": "Vérifier le partage", "bounds": "[20,100][300,150]"})
        second_visible = tree({"text": "Vérifier le partage", "bounds": "[20,100][300,150]"})
        opened = tree({"text": "Choisir une application", "bounds": "[40,300][280,350]"})
        ui.hierarchy = Mock(side_effect=[offscreen, first_visible, second_visible, opened])
        ui.scroll = Mock()
        ui.tap_node = Mock()
        with patch("verification.unifiedpush.stack.time.sleep"):
            result = ui.tap_until(
                "Vérifier le partage",
                lambda r: ui.node("Choisir une application", r) is not None,
                scroll=True,
            )
        self.assertTrue(result)
        ui.scroll.assert_called_once_with(offscreen)
        self.assertEqual(ui.tap_node.call_count, 2)

    def test_tap_until_respects_max_taps_and_permission_prompt(self):
        ui = Screen(Mock(), Mock())
        prompt = tree({"resource-id": "com.android.permissioncontroller:id/permission_allow_button", "bounds": "[1,1][9,9]"})
        button_tree = tree({"text": "Activer", "bounds": "[10,10][90,50]"})
        done = tree({"text": "Activé", "bounds": "[10,10][90,50]"})
        ui.hierarchy = Mock(side_effect=[prompt, button_tree, button_tree, done])
        ui.allow_if_prompted = Mock()
        ui.tap_node = Mock()
        with patch("verification.unifiedpush.stack.time.sleep"):
            self.assertTrue(
                ui.tap_until(
                    "Activer",
                    lambda r: ui.node("Activé", r) is not None,
                    notification_permission=True,
                    max_taps=1,
                )
            )
        ui.allow_if_prompted.assert_called_once_with(prompt)
        self.assertEqual(ui.tap_node.call_count, 1)

    def test_start_account_locks_open_workspace_before_opening_connect_form(self):
        for lang in ("en", "fr"):
            with self.subTest(lang=lang):
                ui = Screen(Mock(), Mock(), lang=lang)
                s = ui.strings
                push_dialog = tree({"text": s["ws_close"], "bounds": "[20,260][120,310]"})
                workspace = tree({"content-desc": s["top_profile_lock"], "bounds": "[200,10][260,70]"})
                security = tree({"text": s["security_lock_now"], "bounds": "[20,100][280,150]"})
                chooser = tree({"text": s["profile_add_action"], "bounds": "[20,200][280,250]"})
                connect = tree({"text": s["profile_connect_title"], "bounds": "[20,40][280,90]"})
                save_form = tree({"text": s["profile_save_action"], "bounds": "[20,500][280,550]"})
                opened_ws = tree({"text": s["thread_new_conversation"], "bounds": "[20,80][280,130]"})
                ui.hierarchy = Mock(side_effect=[push_dialog, workspace, security, chooser, connect, save_form, save_form, opened_ws])
                ui.tap_node = Mock()
                ui.field = Mock()
                with patch("verification.unifiedpush.stack.time.sleep"):
                    start_account(ui, "Alt", "https://example.test", "secret-key", "correct-horse-battery")
                # 4 navigation taps (close, profile_lock, lock_now, add_action) + 2 save taps (1st swallowed, 2nd succeeds).
                self.assertEqual(ui.tap_node.call_count, 6)
                self.assertEqual(ui.field.call_count, 5)

    def test_enroll_retries_swallowed_enable_tap_and_transitions_in_en_and_fr(self):
        for lang in ("en", "fr"):
            with self.subTest(lang=lang):
                ui = Screen(Mock(), Mock(), lang=lang)
                s = ui.strings
                home = tree({"text": s["nav_atelier"], "bounds": "[100,580][180,630]"})
                atelier = tree({"text": s["ws_notifications"], "bounds": "[20,180][300,230]"})
                notif_dialog = tree({"text": s["push_settings_enable"], "bounds": "[20,300][300,350]"})
                transport_picker = tree(
                    {"text": s["push_settings_service_title"], "bounds": "[20,80][300,130]"},
                    {"text": s["push_settings_enable_up"], "bounds": "[20,200][300,250]"},
                )
                distributor_picker = tree(
                    {"text": s["push_settings_choose_distributor"], "bounds": "[20,80][300,130]"},
                    {"text": "ntfy\n" + NTFY, "bounds": "[20,200][300,260]"},
                )
                enrolled = tree({"text": s["push_settings_disable"], "bounds": "[20,300][300,350]"})
                # Swallowed taps on Enable (2nd poll still on notif_dialog) and on ntfy option (still on distributor_picker).
                ui.hierarchy = Mock(side_effect=[
                    home, atelier,
                    atelier, notif_dialog,
                    notif_dialog, notif_dialog, transport_picker,
                    transport_picker,  # allow_if_prompted()
                    transport_picker, distributor_picker, distributor_picker, enrolled,  # choose_ntfy() loop
                ])
                tapped = []
                ui.tap_node = Mock(side_effect=lambda node: tapped.append(node.get("text")))
                with patch("verification.unifiedpush.stack.time.sleep"):
                    enroll(ui)
                self.assertEqual(tapped, [
                    s["nav_atelier"],
                    s["ws_notifications"],
                    s["push_settings_enable"],
                    s["push_settings_enable"],
                    s["push_settings_enable_up"],
                    "ntfy\n" + NTFY,
                    "ntfy\n" + NTFY,
                ])


if __name__ == "__main__":
    unittest.main()
