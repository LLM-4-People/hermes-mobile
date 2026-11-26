"""Deterministic tests for phone handoff review, dismissal, reopen, and inbound draft flows."""

from pathlib import Path
import unittest
from unittest.mock import Mock, patch
from xml.etree.ElementTree import Element, SubElement

from verification import phone_actions
from verification.device.app_strings import AppStrings
from verification.unifiedpush.ui import APP, Screen, TAP_RETRY_COOLDOWN_SECONDS


MAIN_ACTIVITY = "topResumedActivity: ActivityRecord{1 u0 " + APP + "/.MainActivity t1}"
CHOOSER_ACTIVITY = "topResumedActivity: ActivityRecord{2 u0 com.android.intentresolver/.ChooserActivity t1}"
MAIN_WINDOW = "Window{1111 u0 " + APP + "/" + APP + ".MainActivity}"
DIALOG1_WINDOW = "Window{2222 u0 Texte à partager}"
DIALOG2_WINDOW = "Window{3333 u0 Texte à partager}"
MAIN_FOCUS = "mCurrentFocus=" + MAIN_WINDOW
DIALOG1_FOCUS = "mCurrentFocus=" + DIALOG1_WINDOW
DIALOG2_FOCUS = "mCurrentFocus=" + DIALOG2_WINDOW


def node_tree(*labels, package=APP, window_id="10"):
    root = Element("node", {"package": package, "window-id": window_id})
    for index, label in enumerate(labels):
        y = 100 + index * 60
        SubElement(root, "node", {"text": label, "package": package, "bounds": f"[20,{y}][300,{y + 48}]"})
    return root


class PhoneActionsTransitionTest(unittest.TestCase):
    def test_review_waits_for_dialog_dismissal_focus_and_retries_swallowed_reopen_tap(self):
        s = AppStrings.for_lang("en")
        page = node_tree(s["instrument_trial"], s["ws_dev_share_review"], window_id="10")
        dialog1 = node_tree(
            s["ws_dev_share_title"],
            "Atelier de dessin le 12 avril à Laval.",
            s["ws_cancel"],
            s["ws_dev_share_open"],
            window_id="11",
        )
        dialog2 = node_tree(
            s["ws_dev_share_title"],
            "Atelier de dessin le 12 avril à Laval.",
            s["ws_cancel"],
            s["ws_dev_share_open"],
            window_id="12",
        )
        chooser = node_tree(
            "Sharing text",
            "Atelier de dessin le 12 avril à Laval.",
            package="com.android.intentresolver",
            window_id="13",
        )

        # Simulate the exact CI race:
        # 1. Open dialog #1 (first poll sees page -> tap; second sees dialog1 with DIALOG1_FOCUS)
        # 2. Dismiss via Annuler: first poll taps Annuler; second poll sees MainActivity in accessibility
        #    while mCurrentFocus is still DIALOG1_FOCUS; third poll sees mCurrentFocus=null;
        #    fourth poll sees mCurrentFocus=MAIN_FOCUS -> dismissal complete!
        # 3. Reopen dialog #2: first tap on Vérifier le partage is swallowed (page remains visible);
        #    second tap opens dialog2 with DIALOG2_FOCUS.
        # 4. Confirm: first tap on Choisir une application is swallowed (dialog2 remains);
        #    second tap dismisses dialog2 and opens ChooserActivity.
        hierarchies = iter([
            page,      # wait(prepare, scroll=True)
            page,      # page_root
            page,      # open_review #1 attempt 1 -> tap prepare
            dialog1,   # open_review #1 attempt 2 -> opened!
            dialog1,   # visible(ui, detail)
            dialog1,   # close_review attempt 1 -> tap Annuler
            page,      # close_review attempt 2 -> accessibility switched, focus still DIALOG1_FOCUS
            page,      # close_review attempt 3 -> focus null during teardown
            page,      # close_review attempt 4 -> focus MAIN_FOCUS -> closed!
            page,      # page_root before reopen
            page,      # open_review #2 attempt 1 -> tap prepare (swallowed!)
            page,      # open_review #2 attempt 2 -> retry tap prepare!
            dialog2,   # open_review #2 attempt 3 -> opened!
            dialog2,   # confirm_review attempt 1 -> tap confirm (swallowed!)
            dialog2,   # confirm_review attempt 2 -> retry tap confirm!
            chooser,   # confirm_review attempt 3 -> submitted!
            chooser,   # ready() visible(ui, detail)
        ])
        windows = iter([
            MAIN_FOCUS,     # initial page_focus
            DIALOG1_FOCUS,  # open_review #1 attempt 2
            DIALOG1_FOCUS,  # close_review attempt 2 (still dialog!)
            "mCurrentFocus=null",  # close_review attempt 3 (transitioning!)
            MAIN_FOCUS,     # close_review attempt 4 (back on MainActivity!)
            MAIN_FOCUS,     # page_focus before reopen
            DIALOG2_FOCUS,  # open_review #2 attempt 3
        ])
        activities = iter([
            MAIN_ACTIVITY,     # in-app review check
            MAIN_ACTIVITY,     # close_review attempt 1
            MAIN_ACTIVITY,     # close_review attempt 2
            MAIN_ACTIVITY,     # close_review attempt 3
            MAIN_ACTIVITY,     # close_review attempt 4
            MAIN_ACTIVITY,     # confirm_review attempt 1
            MAIN_ACTIVITY,     # confirm_review attempt 2
            CHOOSER_ACTIVITY,  # confirm_review attempt 3
            CHOOSER_ACTIVITY,  # eventually Android receiving activity
            CHOOSER_ACTIVITY,  # ready()
        ])

        taps = []
        device = Mock()

        def shell(*args):
            if args == ("dumpsys", "window"):
                return next(windows)
            if args == ("dumpsys", "activity", "activities"):
                return next(activities)
            if args[:2] == ("input", "tap"):
                taps.append(args[2:])
                return ""
            raise AssertionError(f"Unexpected shell call: {args!r}")

        device.shell.side_effect = shell
        ui = Screen(device, Mock(), lang="en")
        ui.hierarchy = Mock(side_effect=lambda: next(hierarchies))

        clock = (10.0 + 3.0 * index for index in range(20))
        with (
            patch("verification.unifiedpush.stack.time.sleep"),
            patch("verification.unifiedpush.ui.monotonic", side_effect=clock),
            patch.object(phone_actions, "capture_fields", return_value=(["Atelier de dessin le 12 avril à Laval."], "deadbeef")),
            patch.object(phone_actions, "leave_receiver"),
        ):
            proof = phone_actions.review(
                ui,
                "share_text",
                phone_actions.REQUESTS[0],
                Path("/tmp/phone-actions.json"),
                False,
            )

        self.assertEqual(proof["kind"], "share_text")
        self.assertEqual(proof["receiver"], "com.android.intentresolver/.ChooserActivity")
        # 1 tap open#1 + 1 tap Annuler + 2 taps open#2 (1 swallowed + 1 retry) + 2 taps confirm (1 swallowed + 1 retry)
        self.assertEqual(len(taps), 6)

    def test_close_review_fails_fast_if_cancelling_leaves_app(self):
        device = Mock()
        device.shell.return_value = CHOOSER_ACTIVITY
        ui = Screen(device, Mock())
        ui.hierarchy = Mock(return_value=node_tree("Sharing text", package="com.android.intentresolver", window_id="20"))
        with self.assertRaisesRegex(AssertionError, "Cancelling the proposal left the app"):
            phone_actions.close_review(ui, "Vérifier le partage", "Choisir une application", "11", DIALOG1_WINDOW)

    def test_confirm_review_fails_fast_when_app_reports_unavailable_receiver(self):
        device = Mock()
        device.shell.return_value = MAIN_ACTIVITY
        ui = Screen(device, Mock())
        s = ui.strings
        unavailable = node_tree(s["ws_dev_app_unavailable_title"], s["ws_close"], window_id="15")
        ui.hierarchy = Mock(return_value=unavailable)
        with self.assertRaisesRegex(AssertionError, "no available receiving application"):
            phone_actions.confirm_review(ui, "calendar_event", s["ws_dev_calendar_open"])

    def test_open_review_ignores_dialog_with_stale_page_window_or_focus(self):
        device = Mock()
        ui = Screen(device, Mock())
        s = ui.strings
        page = node_tree(s["ws_dev_map_review"], window_id="10")
        stale_window_dialog = node_tree(s["ws_cancel"], s["ws_dev_map_open"], window_id="10")
        new_window_dialog = node_tree(s["ws_cancel"], s["ws_dev_map_open"], window_id="14")
        hierarchies = iter([page, stale_window_dialog, new_window_dialog, new_window_dialog])
        windows = iter([MAIN_FOCUS, "mCurrentFocus=Window{4444 u0 Lieu à rechercher}"])
        device.shell.side_effect = lambda *args: next(windows) if args == ("dumpsys", "window") else ""
        ui.hierarchy = Mock(side_effect=lambda: next(hierarchies))
        with patch("verification.unifiedpush.stack.time.sleep"):
            wid, focus = phone_actions.open_review(
                ui, s["ws_dev_map_review"], s["ws_dev_map_open"], "10", MAIN_WINDOW
            )
        self.assertEqual(wid, "14")
        self.assertEqual(focus, "Window{4444 u0 Lieu à rechercher}")

    def test_back_to_page_waits_for_hermes_window_and_retries_unlock_and_trial_open(self):
        for lang in ("en", "fr"):
            with self.subTest(lang=lang):
                s = AppStrings.for_lang(lang)
                foreign = node_tree("Maps", package="com.google.android.apps.maps", window_id="2")
                chooser = node_tree(s["profile_choose_title"], "Actions123", window_id="3")
                unlock = node_tree(s["profile_passphrase_label"], s["profile_unlock_action"], window_id="3")
                thread = node_tree(s["instrument_try"], window_id="3")
                sheet = node_tree(s["instrument_trial"], s["ws_dev_map_review"], window_id="3")
                hierarchies = iter([
                    foreign,   # wait for APP window
                    chooser,   # APP window arrived
                    chooser,   # tap_until(account_name) attempt 1 -> tap (swallowed)
                    chooser,   # tap_until(account_name) attempt 2 -> tap
                    unlock,    # tap_until(account_name) attempt 3 -> unlocked screen visible
                    unlock,    # if ui.node(unlock_btn) is not None
                    unlock,    # tap_until(unlock_btn) attempt 1 -> tap
                    thread,    # tap_until(unlock_btn) attempt 2 -> unlocked!
                    thread,    # if ui.node(label) is None
                    thread,    # and ui.node(trying_status) is None
                    thread,    # tap_until(try_in_thread) attempt 1 -> tap
                    sheet,     # tap_until(try_in_thread) attempt 2 -> sheet visible
                    sheet,     # ui.wait(label, scroll=True)
                ])
                taps = []
                device = Mock()
                device.shell.side_effect = lambda *args: (
                    MAIN_ACTIVITY
                    if args == ("dumpsys", "activity", "activities")
                    else (taps.append(args[2:]) or "") if args[:2] == ("input", "tap") else ""
                )
                ui = Screen(device, Mock(), lang=lang)
                ui.hierarchy = Mock(side_effect=lambda: next(hierarchies))
                ui.field = Mock()
                clock = (10.0 + 3.0 * index for index in range(20))
                with patch("verification.unifiedpush.stack.time.sleep"), patch(
                    "verification.unifiedpush.ui.monotonic", side_effect=clock
                ):
                    phone_actions.back_to_page(ui, "Actions123", "secretphrase", s["ws_dev_map_review"])
                ui.field.assert_called_once_with(s["profile_passphrase_label"], "secretphrase")
                # 2 taps on account_name (1 swallowed + 1 retry) + 1 tap on unlock + 1 tap on try_in_thread.
                self.assertEqual(len(taps), 4)

    def test_inbound_drafts_retries_swallowed_profile_chooser_and_confirm_taps(self):
        account = "Actionsabc"
        session = "session-123"
        first_text = "Note de préparation pour Laval."
        second_text = "dessin à Laval"
        combined = first_text + "\n" + second_text

        for lang in ("en", "fr"):
            with self.subTest(lang=lang):
                s = AppStrings.for_lang(lang)
                chooser = node_tree(s["profile_choose_title"], account, window_id="20")
                unlock = node_tree(s["profile_passphrase_label"], s["profile_unlock_action"], window_id="20")
                review1 = node_tree(s["share_add_text_title"], account, session, first_text, s["share_add_to_draft"], window_id="21")
                composer1 = node_tree(s["thread_send"], first_text, window_id="20")
                review2 = node_tree(s["share_add_text_title"], account, session, second_text, s["share_add_to_draft"], window_id="22")
                composer2 = node_tree(s["thread_send"], combined, window_id="20")

                hierarchies = iter([
                    # SEND
                    chooser, chooser, unlock,  # swallowed chooser tap, then retry
                    unlock, review1,           # unlock -> share review
                    review1,                   # visible(ui, [account, session, value])
                    review1, review1, composer1,  # swallowed confirm tap, then retry
                    composer1,                 # ui.wait(previous)
                    # PROCESS_TEXT
                    chooser, unlock,
                    unlock, review2,
                    review2,
                    review2, composer2,
                    composer2,
                ])
                device = Mock()
                device.shell.return_value = "Status: ok"
                ui = Screen(device, Mock(), lang=lang)
                ui.hierarchy = Mock(side_effect=lambda: next(hierarchies))
                ui.field = Mock()
                tapped = []
                ui.tap_node = Mock(side_effect=lambda node: tapped.append(node.get("text")))
                clock = (10.0 + 3.0 * index for index in range(30))
                with patch("verification.unifiedpush.stack.time.sleep"), patch(
                    "verification.unifiedpush.ui.monotonic", side_effect=clock
                ):
                    results = phone_actions.inbound_drafts(ui, account, "secretphrase", session)
                self.assertEqual(
                    tapped,
                    [
                        account,
                        account,
                        s["profile_unlock_action"],
                        s["share_add_to_draft"],
                        s["share_add_to_draft"],
                        account,
                        s["profile_unlock_action"],
                        s["share_add_to_draft"],
                    ],
                )
                self.assertEqual(
                    results,
                    [
                        {"kind": "share", "source_uid": "shell", "unsent_draft": first_text},
                        {"kind": "selection", "source_uid": "shell", "unsent_draft": combined},
                    ],
                )

    def test_send_composer_message_dismisses_ime_and_retries_swallowed_send_tap(self):
        prompt = "Test request for composer send"
        for lang in ("en", "fr"):
            with self.subTest(lang=lang):
                s = AppStrings.for_lang(lang)
                before_send = Element("node", {"package": APP, "window-id": "20"})
                SubElement(before_send, "node", {"package": APP, "class": "android.widget.EditText", "text": prompt, "bounds": "[10,400][220,460]"})
                SubElement(before_send, "node", {"package": APP, "class": "android.widget.Button", "text": s["thread_send"], "bounds": "[230,400][310,460]"})
                after_send = Element("node", {"package": APP, "window-id": "20"})
                SubElement(after_send, "node", {"package": APP, "class": "android.widget.EditText", "text": "", "bounds": "[10,400][220,460]"})
                SubElement(after_send, "node", {"package": APP, "class": "android.widget.Button", "text": s["thread_stop"], "bounds": "[230,400][310,460]"})

                device = Mock()
                device.ime_shown.side_effect = [True, False]
                ui = Screen(device, Mock(), lang=lang)
                ui.hierarchy = Mock(side_effect=[before_send, before_send, after_send])
                tapped = []
                ui.tap_node = Mock(side_effect=lambda node: tapped.append(node.get("text")))
                step = TAP_RETRY_COOLDOWN_SECONDS + 0.5
                with patch("verification.unifiedpush.stack.time.sleep"), patch(
                    "verification.unifiedpush.ui.monotonic", side_effect=[10.0, 10.0 + step]
                ):
                    phone_actions.send_composer_message(ui, prompt)
                device.shell.assert_called_once_with("input", "keyevent", "BACK")
                self.assertEqual(tapped, [s["thread_send"], s["thread_send"]])

    def test_assert_offline_waits_reactively_for_transient_network_teardown(self):
        device = Mock()
        device.shell.side_effect = [
            "NetworkAgentInfo\nActive default network: 100\n",
            "NetworkAgentInfo\nActive default network: 100\n",
            "NetworkAgentInfo\nActive default network: none\n",
        ]
        with patch.object(phone_actions, "sleep") as mock_sleep, patch.object(
            phone_actions, "monotonic", side_effect=[10.0, 10.25, 10.5]
        ):
            phone_actions.assert_offline(device, timeout=5.0, poll_interval=0.25)
        self.assertEqual(device.shell.call_count, 3)
        self.assertEqual(mock_sleep.call_count, 2)

    def test_assert_offline_reports_observed_network_line_when_still_online(self):
        for dump, expected_detail in (
            ("Header\n  Active default network: 100\nTail\n", "Active default network: 100"),
            ("No connectivity status line\n", "Active default network: <missing>"),
        ):
            with self.subTest(expected_detail=expected_detail):
                device = Mock()
                device.shell.return_value = dump
                with patch.object(phone_actions, "sleep"), patch.object(
                    phone_actions, "monotonic", side_effect=[10.0, 15.1]
                ):
                    with self.assertRaises(ValueError) as raised:
                        phone_actions.assert_offline(device, timeout=5.0, poll_interval=0.25)
                msg = str(raised.exception)
                self.assertIn("Use a dedicated emulator in airplane mode with Wi-Fi disabled", msg)
                self.assertIn(expected_detail, msg)

    def test_leave_receiver_retries_swallowed_back_and_discard_for_calendar_draft(self):
        calendar_act = "topResumedActivity: ActivityRecord{3 u0 org.fossify.calendar/.EventActivity t1}"
        editor = node_tree("Atelier de dessin", package=phone_actions.CALENDAR, window_id="30")
        discard_dialog = node_tree("Discard changes?", "Discard", "Cancel", package=phone_actions.CALENDAR, window_id="31")

        # 1. Initial check in calendar; 2. First BACK swallowed (still editor);
        # 3. Second BACK opens discard dialog -> tap Discard (swallowed);
        # 4. Retry tap Discard -> returns to MainActivity.
        activities = iter([
            calendar_act,  # initial focus check
            calendar_act,  # poll 1 -> BACK #1
            calendar_act,  # poll 2 -> BACK #2 (after cooldown)
            calendar_act,  # poll 3 -> Discard #1
            calendar_act,  # poll 4 -> Discard #2 (after cooldown)
            MAIN_ACTIVITY, # poll 5 -> left calendar!
        ])
        hierarchies = iter([editor, editor, discard_dialog, discard_dialog])
        backs = []
        taps = []
        device = Mock()
        device.ime_shown.return_value = False

        def shell(*args):
            if args == ("dumpsys", "activity", "activities"):
                return next(activities)
            if args == ("input", "keyevent", "BACK"):
                backs.append(args)
                return ""
            if args[:2] == ("input", "tap"):
                taps.append(args[2:])
                return ""
            raise AssertionError(f"Unexpected shell call: {args!r}")

        device.shell.side_effect = shell
        ui = Screen(device, Mock(), lang="en")
        ui.hierarchy = Mock(side_effect=lambda: next(hierarchies))
        step = TAP_RETRY_COOLDOWN_SECONDS + 0.5
        clock = [10.0 + step * i for i in range(6)]
        with patch("verification.unifiedpush.stack.time.sleep"), patch.object(
            phone_actions, "monotonic", side_effect=clock
        ):
            phone_actions.leave_receiver(ui, phone_actions.CALENDAR, "calendar_event")
        self.assertEqual(len(backs), 2)
        self.assertEqual(len(taps), 2)

    def test_leave_receiver_retries_swallowed_back_for_sharesheet(self):
        activities = iter([CHOOSER_ACTIVITY, CHOOSER_ACTIVITY, CHOOSER_ACTIVITY, MAIN_ACTIVITY])
        backs = []
        device = Mock()
        device.ime_shown.return_value = False

        def shell(*args):
            if args == ("dumpsys", "activity", "activities"):
                return next(activities)
            if args == ("input", "keyevent", "BACK"):
                backs.append(args)
                return ""
            raise AssertionError(f"Unexpected shell call: {args!r}")

        device.shell.side_effect = shell
        ui = Screen(device, Mock(), lang="en")
        step = TAP_RETRY_COOLDOWN_SECONDS + 0.5
        with patch("verification.unifiedpush.stack.time.sleep"), patch.object(
            phone_actions, "monotonic", side_effect=[10.0, 10.0 + step]
        ):
            phone_actions.leave_receiver(ui, "com.android.intentresolver", "share_text")
        self.assertEqual(len(backs), 2)


if __name__ == "__main__":
    unittest.main()
