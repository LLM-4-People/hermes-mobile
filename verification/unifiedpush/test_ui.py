"""Unit tests for Screen bounds parsing, focus inspection, and verified tap transitions."""

from pathlib import Path
import subprocess
import unittest
from unittest.mock import Mock, call, patch
from xml.etree.ElementTree import Element, SubElement

from .ui import (
    NOTIFICATIONS_ROW_ID,
    NTFY,
    PERMISSION_ALLOW_IDS,
    Screen,
    TAP_RETRY_COOLDOWN_SECONDS,
    TOP_SETTINGS_ID,
    distributor,
    enroll,
    focused_window,
    open_notifications_settings,
    process_absent,
    start_account,
)


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
        step = TAP_RETRY_COOLDOWN_SECONDS + 0.5
        clock = [10.0 + step * index for index in range(5)]
        with patch("verification.unifiedpush.stack.time.sleep"), patch(
            "verification.unifiedpush.ui.monotonic", side_effect=clock
        ):
            result = ui.tap_until(
                "Vérifier le partage",
                lambda r: ui.node("Choisir une application", r) is not None,
                scroll=True,
            )
        self.assertTrue(result)
        ui.scroll.assert_called_once_with(offscreen)
        self.assertEqual(ui.tap_node.call_count, 2)

    def test_tap_until_debounces_consecutive_polls_by_default_and_retries_after_cooldown(self):
        ui = Screen(Mock(), Mock())
        trigger = tree({"text": "Open distributor", "bounds": "[20,100][300,150]"})
        ready_tree = tree({"text": "Subscribed topics", "bounds": "[20,80][300,130]"})
        ui.hierarchy = Mock(side_effect=[trigger, trigger, trigger, trigger, ready_tree])
        ui.tap_node = Mock()
        # Rapid polls within TAP_RETRY_COOLDOWN_SECONDS must NOT double-tap; retry fires only once cooldown elapses.
        clock = [10.0, 10.1, 10.0 + TAP_RETRY_COOLDOWN_SECONDS - 0.1, 10.0 + TAP_RETRY_COOLDOWN_SECONDS]
        with patch("verification.unifiedpush.stack.time.sleep"), patch(
            "verification.unifiedpush.ui.monotonic", side_effect=clock
        ):
            result = ui.tap_until(
                "Open distributor",
                lambda r: ui.node("Subscribed topics", r) is not None,
            )
        self.assertTrue(result)
        self.assertEqual(ui.tap_node.call_count, 2)

    def test_tap_until_handles_permission_prompt_without_scrolling_and_adds_focused_window_note_on_timeout(self):
        ui = Screen(Mock(), Mock())
        prompt = tree({"resource-id": PERMISSION_ALLOW_IDS[0], "scrollable": "true", "bounds": "[1,1][9,9]"})
        ready_tree = tree({"text": "Subscribed topics", "bounds": "[20,80][300,130]"})
        ui.hierarchy = Mock(side_effect=[prompt, prompt, ready_tree])
        ui.tap_node = Mock()
        ui.scroll = Mock()
        with patch("verification.unifiedpush.stack.time.sleep"), patch(
            "verification.unifiedpush.ui.monotonic", side_effect=[10.0, 10.1, 10.2]
        ):
            self.assertTrue(
                ui.tap_until(
                    "Open distributor",
                    lambda r: ui.node("Subscribed topics", r) is not None,
                    scroll=True,
                    notification_permission=True,
                )
            )
        ui.tap_node.assert_called_once()
        ui.scroll.assert_not_called()

        device = Mock()
        device.shell.return_value = "  mCurrentFocus=Window{def456 u0 io.heckel.ntfy/io.heckel.ntfy.ui.MainActivity}\n"
        stuck_ui = Screen(device, Mock())
        stuck_ui.hierarchy = Mock(return_value=tree({"text": "Other screen", "bounds": "[0,0][10,10]"}))
        with patch("verification.unifiedpush.ui.eventually", side_effect=AssertionError("Timed out waiting for ntfy")):
            with self.assertRaises(AssertionError) as ctx:
                stuck_ui.tap_until("Open distributor", lambda r: False)
        notes = getattr(ctx.exception, "__notes__", [])
        self.assertTrue(
            any("io.heckel.ntfy/io.heckel.ntfy.ui.MainActivity" in note for note in notes),
            notes,
        )

    def test_tap_until_respects_max_taps_and_permission_prompt(self):
        ui = Screen(Mock(), Mock())
        prompt = tree({"resource-id": "com.android.permissioncontroller:id/permission_allow_button", "bounds": "[1,1][9,9]"})
        button_tree = tree({"text": "Activer", "bounds": "[10,10][90,50]"})
        done = tree({"text": "Activé", "bounds": "[10,10][90,50]"})
        ui.hierarchy = Mock(side_effect=[prompt, button_tree, button_tree, done])
        ui.allow_if_prompted = Mock()
        ui.tap_node = Mock()
        step = TAP_RETRY_COOLDOWN_SECONDS + 0.5
        clock = [10.0 + step * index for index in range(10)]
        with patch("verification.unifiedpush.stack.time.sleep"), patch(
            "verification.unifiedpush.ui.monotonic", side_effect=clock
        ):
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
                workspace = tree({"content-desc": s["nav_settings"], "bounds": "[200,10][260,70]"})
                settings_screen = tree(
                    {"content-desc": s["nav_settings"], "bounds": "[200,10][260,70]"},
                    {"text": s["security_lock_now"], "bounds": "[20,100][280,150]"},
                )
                chooser = tree({"text": s["profile_add_action"], "bounds": "[20,200][280,250]"})
                connect = tree({"text": s["profile_connect_title"], "bounds": "[20,40][280,90]"})
                save_form = tree({"text": s["profile_save_action"], "bounds": "[20,500][280,550]"})
                opened_ws = tree({"text": s["thread_new_conversation"], "bounds": "[20,80][280,130]"})
                ui.hierarchy = Mock(side_effect=[push_dialog, workspace, settings_screen, chooser, connect, save_form, save_form, opened_ws])
                ui.tap_node = Mock()
                ui.field = Mock()
                step = TAP_RETRY_COOLDOWN_SECONDS + 0.5
                clock = [10.0 + step * index for index in range(10)]
                with patch("verification.unifiedpush.stack.time.sleep"), patch(
                    "verification.unifiedpush.ui.monotonic", side_effect=clock
                ):
                    start_account(ui, "Alt", "https://example.test", "secret-key", "correct-horse-battery")
                # 4 navigation taps (close, settings, lock_now, add_action) + 2 save taps (1st swallowed, 2nd succeeds).
                self.assertEqual(ui.tap_node.call_count, 6)
                self.assertEqual(ui.field.call_count, 5)

    def test_start_account_scrolls_settings_to_top_by_tapping_top_settings_button_not_heading(self):
        for lang in ("en", "fr"):
            with self.subTest(lang=lang):
                ui = Screen(Mock(), Mock(), lang=lang)
                s = ui.strings
                # Returned to SettingsHubPanel scrolled down: heading text equals nav_settings and
                # precedes top_settings_action in the tree, while security_lock_now is off-screen.
                scrolled_settings = tree(
                    {"text": s["settings_panel_title"], "bounds": "[60,10][200,70]"},
                    {"resource-id": TOP_SETTINGS_ID, "content-desc": s["nav_settings"], "bounds": "[250,10][310,70]"},
                    {"resource-id": NOTIFICATIONS_ROW_ID, "bounds": "[20,400][300,460]"},
                )
                top_settings = tree(
                    {"text": s["settings_panel_title"], "bounds": "[60,10][200,70]"},
                    {"resource-id": TOP_SETTINGS_ID, "content-desc": s["nav_settings"], "bounds": "[250,10][310,70]"},
                    {"text": s["security_lock_now"], "bounds": "[20,160][280,210]"},
                )
                chooser = tree({"text": s["profile_add_action"], "bounds": "[20,200][280,250]"})
                connect = tree({"text": s["profile_connect_title"], "bounds": "[20,40][280,90]"})
                save_form = tree({"text": s["profile_save_action"], "bounds": "[20,500][280,550]"})
                opened_ws = tree({"text": s["thread_new_conversation"], "bounds": "[20,80][280,130]"})
                ui.hierarchy = Mock(side_effect=[
                    scrolled_settings, top_settings, chooser, connect, save_form, opened_ws
                ])
                tapped = []
                ui.tap_node = Mock(side_effect=lambda node: tapped.append(
                    node.get("resource-id") or node.get("text") or node.get("content-desc")
                ))
                ui.field = Mock()
                step = TAP_RETRY_COOLDOWN_SECONDS + 0.5
                clock = [10.0 + step * index for index in range(10)]
                with patch("verification.unifiedpush.stack.time.sleep"), patch(
                    "verification.unifiedpush.ui.monotonic", side_effect=clock
                ):
                    start_account(ui, "Alt", "https://example.test", "secret-key", "correct-horse-battery")
                self.assertEqual(tapped, [
                    TOP_SETTINGS_ID,
                    s["security_lock_now"],
                    s["profile_add_action"],
                    s["profile_save_action"],
                ])

    def test_enroll_retries_swallowed_enable_tap_and_transitions_in_en_and_fr(self):
        for lang in ("en", "fr"):
            with self.subTest(lang=lang):
                ui = Screen(Mock(), Mock(), lang=lang)
                s = ui.strings
                home = tree({"content-desc": s["nav_settings"], "bounds": "[240,10][300,70]"})
                settings_screen = tree({"resource-id": NOTIFICATIONS_ROW_ID, "bounds": "[20,180][300,230]"})
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
                # Swallowed taps on Enable (2nd poll after cooldown) and on ntfy option (still on distributor_picker).
                ui.hierarchy = Mock(side_effect=[
                    home, settings_screen,
                    settings_screen, notif_dialog,
                    notif_dialog, notif_dialog, transport_picker,
                    transport_picker, distributor_picker, distributor_picker, enrolled,  # choose_ntfy() loop
                ])
                tapped = []
                ui.tap_node = Mock(side_effect=lambda node: tapped.append(
                    node.get("text") or node.get("content-desc") or node.get("resource-id")
                ))
                step = TAP_RETRY_COOLDOWN_SECONDS + 0.5
                clock = [step * index for index in range(1, 20)]
                with patch("verification.unifiedpush.stack.time.sleep"), patch(
                    "verification.unifiedpush.ui.monotonic", side_effect=clock
                ):
                    enroll(ui)
                self.assertEqual(tapped, [
                    s["nav_settings"],
                    NOTIFICATIONS_ROW_ID,
                    s["push_settings_enable"],
                    s["push_settings_enable"],
                    s["push_settings_enable_up"],
                    "ntfy\n" + NTFY,
                    "ntfy\n" + NTFY,
                ])

    def test_enroll_handles_permission_prompt_without_double_tap_or_transition_scroll(self):
        for lang in ("en", "fr"):
            with self.subTest(lang=lang):
                ui = Screen(Mock(), Mock(), lang=lang)
                s = ui.strings
                home = tree({"content-desc": s["nav_settings"], "bounds": "[240,10][300,70]"})
                settings_screen = tree(
                    {"scrollable": "true", "bounds": "[0,60][320,580]"},
                    {"resource-id": NOTIFICATIONS_ROW_ID, "bounds": "[20,180][300,230]"},
                )
                notif_dialog = tree({"text": s["push_settings_enable"], "bounds": "[20,300][300,350]"})
                # Transient frame after tapping Enable while Android launches PermissionController.
                transition_frame = tree({"scrollable": "true", "bounds": "[0,60][320,580]"})
                perm_dialog = tree({"resource-id": PERMISSION_ALLOW_IDS[0], "bounds": "[20,400][300,450]"})
                searching_dialog = tree({"text": s["push_settings_searching_services"], "bounds": "[20,250][300,300]"})
                transport_picker = tree(
                    {"text": s["push_settings_service_title"], "bounds": "[20,80][300,130]"},
                    {"text": s["push_settings_enable_up"], "bounds": "[20,200][300,250]"},
                )
                distributor_picker = tree(
                    {"text": s["push_settings_choose_distributor"], "bounds": "[20,80][300,130]"},
                    {"text": "ntfy\n" + NTFY, "bounds": "[20,200][300,260]"},
                )
                enrolled = tree({"text": s["push_settings_disable"], "bounds": "[20,300][300,350]"})
                ui.hierarchy = Mock(side_effect=[
                    home, settings_screen,
                    settings_screen, notif_dialog,
                    notif_dialog, transition_frame, perm_dialog,  # tap_until(enable)
                    perm_dialog, perm_dialog, searching_dialog,  # choose_ntfy: tap allow ONCE, debounce 0.1s dismissal
                    transport_picker, distributor_picker, enrolled,
                ])
                tapped = []
                ui.tap_node = Mock(side_effect=lambda node: tapped.append(
                    node.get("text") or node.get("content-desc") or node.get("resource-id")
                ))
                ui.scroll = Mock()
                # Rapid 0.1s ticks: Enable and permission_allow_button must NOT be double-tapped!
                clock = [10.0 + 0.1 * index for index in range(20)]
                with patch("verification.unifiedpush.stack.time.sleep"), patch(
                    "verification.unifiedpush.ui.monotonic", side_effect=clock
                ):
                    enroll(ui)
                ui.scroll.assert_not_called()
                self.assertEqual(tapped, [
                    s["nav_settings"],
                    NOTIFICATIONS_ROW_ID,
                    s["push_settings_enable"],
                    PERMISSION_ALLOW_IDS[0],
                    s["push_settings_enable_up"],
                    "ntfy\n" + NTFY,
                ])

    def test_open_notifications_settings_scrolls_when_notifications_is_below_the_fold(self):
        for lang in ("en", "fr"):
            with self.subTest(lang=lang):
                ui = Screen(Mock(), Mock(), lang=lang)
                s = ui.strings
                thread_view = tree({"content-desc": s["nav_settings"], "bounds": "[240,10][300,70]"})
                settings_top = tree(
                    {"scrollable": "true", "bounds": "[0,60][320,580]"},
                    {"text": s["security_lock_now"], "bounds": "[20,100][300,150]"},
                    {"text": s["settings_switch_profile"], "bounds": "[20,200][300,240]"},
                )
                settings_scrolled = tree(
                    {"scrollable": "true", "bounds": "[0,60][320,580]"},
                    {"resource-id": NOTIFICATIONS_ROW_ID, "bounds": "[20,400][300,450]"},
                )
                notif_dialog = tree({"text": s["push_settings_disable"], "bounds": "[20,300][300,350]"})
                ui.hierarchy = Mock(side_effect=[
                    thread_view, settings_top,
                    settings_top, settings_scrolled, notif_dialog,
                ])
                scrolled = []
                tapped = []
                ui.scroll = Mock(side_effect=lambda root: scrolled.append(root))
                ui.tap_node = Mock(side_effect=lambda node: tapped.append(
                    node.get("text") or node.get("content-desc") or node.get("resource-id")
                ))
                with patch("verification.unifiedpush.stack.time.sleep"):
                    open_notifications_settings(ui, s["push_settings_disable"])
                self.assertEqual(len(scrolled), 1)
                self.assertEqual(tapped, [s["nav_settings"], NOTIFICATIONS_ROW_ID])

    def test_set_text_retries_transient_retry_response_with_unicode(self):
        controls = Mock()
        controls.send.side_effect = [b"retry", b"applied"]
        ui = Screen(Mock(), controls)
        ui.hierarchy = Mock(return_value=tree({"text": "Composer", "bounds": "[0,0][10,10]"}))
        with patch("verification.unifiedpush.stack.time.sleep"):
            ui.set_text("Composer", "Quels cafés près de Montréal ?")
        self.assertEqual(controls.send.call_count, 2)

    def test_start_account_debounces_repeated_same_control_polls(self):
        ui = Screen(Mock(), Mock(), lang="en")
        s = ui.strings
        chooser = tree({"text": s["profile_add_action"], "bounds": "[20,200][280,250]"})
        connect = tree({"text": s["profile_connect_title"], "bounds": "[20,40][280,90]"})
        save_form = tree({"text": s["profile_save_action"], "bounds": "[20,500][280,550]"})
        opened_ws = tree({"text": s["thread_new_conversation"], "bounds": "[20,80][280,130]"})
        # chooser repeats 3 times within cooldown -> add_action must be tapped only ONCE!
        ui.hierarchy = Mock(side_effect=[chooser, chooser, chooser, connect, save_form, opened_ws])
        tapped = []
        ui.tap_node = Mock(side_effect=lambda node: tapped.append(node.get("text")))
        ui.field = Mock()
        clock = [10.0, 10.1, 10.2, 10.3, 10.4, 10.5]
        with patch("verification.unifiedpush.stack.time.sleep"), patch(
            "verification.unifiedpush.ui.monotonic", side_effect=clock
        ):
            start_account(ui, "Alt", "https://example.test", "secret-key", "correct-horse-battery")
        self.assertEqual(tapped, [s["profile_add_action"], s["profile_save_action"]])

    def test_hierarchy_debounces_system_ui_aerr_wait_taps(self):
        anr = tree(
            {"text": "System UI isn't responding", "bounds": "[20,100][300,150]"},
            {"resource-id": "android:id/aerr_wait", "text": "Wait", "bounds": "[160,200][280,250]"},
        )
        ready = tree({"text": "Ready", "bounds": "[20,100][200,150]"})
        ui = Screen(Mock(), Mock())
        ui.snapshot = Mock(side_effect=[anr, anr, anr, anr, ready])
        tapped = []
        ui.tap_node = Mock(side_effect=lambda node: tapped.append(node.get("resource-id")))
        # First 3 ANR polls are within cooldown (10.0, 10.1, 10.2) -> 1 tap; 4th at 13.0 -> retry tap.
        with patch("verification.unifiedpush.stack.time.sleep"), patch(
            "verification.unifiedpush.ui.monotonic", side_effect=[10.0, 10.1, 10.2, 13.0]
        ):
            root = ui.hierarchy()
        self.assertIsNotNone(ui.node("Ready", root))
        self.assertEqual(tapped, ["android:id/aerr_wait", "android:id/aerr_wait"])

    def test_wait_debounces_notification_permission_without_scrolling(self):
        perm_tree = tree(
            {"scrollable": "true", "bounds": "[0,0][320,640]"},
            {"resource-id": "com.android.permissioncontroller:id/permission_allow_button", "bounds": "[40,300][280,360]"},
        )
        scrolled_needed = tree({"scrollable": "true", "bounds": "[0,0][320,640]"})
        target_tree = tree({"text": "Target", "bounds": "[20,200][200,250]"})
        ui = Screen(Mock(), Mock())
        ui.hierarchy = Mock(side_effect=[perm_tree, perm_tree, perm_tree, perm_tree, scrolled_needed, target_tree])
        tapped = []
        scrolled = []
        ui.tap_node = Mock(side_effect=lambda node: tapped.append(node.get("resource-id")))
        ui.scroll = Mock(side_effect=lambda root: scrolled.append(root))
        with patch("verification.unifiedpush.stack.time.sleep"), patch(
            "verification.unifiedpush.ui.monotonic", side_effect=[10.0, 10.1, 10.2, 13.0]
        ):
            node = ui.wait("Target", scroll=True, notification_permission=True)
        self.assertEqual(node.get("text"), "Target")
        self.assertEqual(len(tapped), 2)
        self.assertEqual(len(scrolled), 1)

    def test_dismiss_ime_retries_debounced_back_until_hidden(self):
        device = Mock()
        # Initial check True; poll1 True->BACK #1; poll1 post-check True;
        # poll2 True (within cooldown, no BACK); poll2 post-check True;
        # poll3 True (after cooldown->BACK #2); poll3 post-check False -> done!
        device.ime_shown.side_effect = [True, True, True, True, True, True, False]
        ui = Screen(device, Mock())
        with patch("verification.unifiedpush.stack.time.sleep"), patch(
            "verification.unifiedpush.ui.monotonic", side_effect=[10.0, 10.1, 13.0]
        ):
            ui.dismiss_ime()
        self.assertEqual(device.shell.call_count, 2)
        device.shell.assert_called_with("input", "keyevent", "BACK")

    def test_distributor_retries_debounced_back_to_subscribed_topics(self):
        device = Mock()
        device.shell.side_effect = lambda *args: (
            "" if args == ("pm", "list", "packages", NTFY)
            else NTFY + "/.MainActivity" if args[:2] == ("cmd", "package")
            else ""
        )
        device.ime_shown.side_effect = [True, True, False]
        ui = Screen(device, Mock())
        url_field = NTFY + ":id/default_server_dialog_url"
        editor_tree = tree(
            {"resource-id": url_field, "bounds": "[20,200][300,260]"},
            {"text": "Save", "bounds": "[200,300][300,350]"},
        )
        saved_settings = tree({"text": "http://127.0.0.1:8080", "bounds": "[20,200][300,260]"})
        topics = tree({"text": "Subscribed topics", "bounds": "[20,60][280,110]"})
        # open_editor -> editor_tree; tap_until(Save) -> editor_tree, saved_settings;
        # back_to_topics -> saved_settings (BACK #1), saved_settings (cooldown skip), saved_settings (BACK #2), topics!
        ui.hierarchy = Mock(side_effect=[
            editor_tree,
            editor_tree, saved_settings,
            saved_settings, saved_settings, saved_settings, topics,
        ])
        ui.type = Mock()
        ui.tap_node = Mock()
        with patch("verification.unifiedpush.stack.time.sleep"), patch(
            "verification.unifiedpush.ui.monotonic", side_effect=[10.0, 10.0, 20.0, 20.1, 23.0]
        ):
            with distributor(ui, Path("/tmp/ntfy.apk"), "http://127.0.0.1:8080"):
                pass
        back_calls = [c for c in device.shell.call_args_list if c.args == ("input", "keyevent", "BACK")]
        # 1 BACK for dismiss_ime + 2 debounced BACKs for back_to_topics.
        self.assertEqual(len(back_calls), 3)

    def test_process_absent_handles_exit_retry_error_and_pidof_timeout(self):
        def make_ui():
            device = Mock()
            device.command = ["adb", "-s", "emulator-5554"]
            device.shell.side_effect = lambda *args: (
                "    User 0: ceDataInode=1 stopped=false" if args[:2] == ("dumpsys", "package") else ""
            )
            return Screen(device, Mock())

        # Positive exit (returncode=1, empty stdout) with timeout=30 verified.
        with patch("verification.unifiedpush.stack.time.sleep"), patch(
            "verification.unifiedpush.ui.subprocess.run",
            return_value=subprocess.CompletedProcess([], 1, b"", b""),
        ) as run_mock:
            process_absent(make_ui())
        run_mock.assert_called_once_with(
            ["adb", "-s", "emulator-5554", "shell", "pidof", "org.llm4people.hermes"],
            capture_output=True,
            timeout=30,
        )

        # Retry-then-exit (0 -> 1).
        with patch("verification.unifiedpush.stack.time.sleep"), patch(
            "verification.unifiedpush.ui.subprocess.run",
            side_effect=[
                subprocess.CompletedProcess([], 0, b"12345\n", b""),
                subprocess.CompletedProcess([], 1, b"", b""),
            ],
        ) as run_mock:
            process_absent(make_ui())
        self.assertEqual(run_mock.call_count, 2)

        # Unexpected exit code (2 -> RuntimeError).
        with patch("verification.unifiedpush.stack.time.sleep"), patch(
            "verification.unifiedpush.ui.subprocess.run",
            return_value=subprocess.CompletedProcess([], 2, b"", b"err"),
        ), self.assertRaisesRegex(RuntimeError, "PID verification failed"):
            process_absent(make_ui())

        # TimeoutExpired -> bounded RuntimeError("adb shell pidof exceeded 30s").
        with patch("verification.unifiedpush.stack.time.sleep"), patch(
            "verification.unifiedpush.ui.subprocess.run",
            side_effect=subprocess.TimeoutExpired(["adb"], 30),
        ), self.assertRaisesRegex(RuntimeError, "adb shell pidof exceeded 30s"):
            process_absent(make_ui())

    def test_distributor_cleanup_preserves_primary_error_and_propagates_clean_exit_failure(self):
        def setup_ui():
            device = Mock()
            device.shell.side_effect = lambda *args: (
                "" if args == ("pm", "list", "packages", NTFY)
                else NTFY + "/.MainActivity" if args[:2] == ("cmd", "package")
                else ""
            )
            device.ime_shown.return_value = False
            device.run.side_effect = lambda *args: (
                (_ for _ in ()).throw(RuntimeError("adb uninstall failed")) if args == ("uninstall", NTFY) else b""
            )
            ui = Screen(device, Mock())
            url_field = NTFY + ":id/default_server_dialog_url"
            editor_tree = tree(
                {"resource-id": url_field, "bounds": "[20,200][300,260]"},
                {"text": "Save", "bounds": "[200,300][300,350]"},
            )
            saved_settings = tree({"text": "http://127.0.0.1:8080", "bounds": "[20,200][300,260]"})
            topics = tree({"text": "Subscribed topics", "bounds": "[20,60][280,110]"})
            ui.hierarchy = Mock(side_effect=[editor_tree, editor_tree, saved_settings, topics])
            ui.type = Mock()
            ui.tap_node = Mock()
            return ui

        primary = ValueError("distributor body failed")
        with patch("verification.unifiedpush.stack.time.sleep"), self.assertRaises(ValueError) as raised:
            with distributor(setup_ui(), Path("/tmp/ntfy.apk"), "http://127.0.0.1:8080"):
                raise primary
        self.assertIs(raised.exception, primary)
        self.assertIn("adb uninstall failed", str(primary.__notes__))

        with patch("verification.unifiedpush.stack.time.sleep"), self.assertRaises(ExceptionGroup) as clean_err:
            with distributor(setup_ui(), Path("/tmp/ntfy.apk"), "http://127.0.0.1:8080"):
                pass
        self.assertIn("adb uninstall failed", str(clean_err.exception.exceptions[0]))


if __name__ == "__main__":
    unittest.main()
