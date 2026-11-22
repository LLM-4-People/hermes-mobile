"""Deterministic tests for phone handoff review, dismissal, reopen, and inbound draft flows."""

from pathlib import Path
import unittest
from unittest.mock import Mock, patch
from xml.etree.ElementTree import Element, SubElement

from verification import phone_actions
from verification.unifiedpush.ui import APP, Screen


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
        page = node_tree("En essai dans cette discussion", "Vérifier le partage", window_id="10")
        dialog1 = node_tree(
            "Texte à partager",
            "Atelier de dessin le 12 avril à Laval.",
            "Annuler",
            "Choisir une application",
            window_id="11",
        )
        dialog2 = node_tree(
            "Texte à partager",
            "Atelier de dessin le 12 avril à Laval.",
            "Annuler",
            "Choisir une application",
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
        ui = Screen(device, Mock())
        ui.hierarchy = Mock(side_effect=lambda: next(hierarchies))

        with (
            patch("verification.unifiedpush.stack.time.sleep"),
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
        unavailable = node_tree("Application indisponible", "Fermer", window_id="15")
        ui.hierarchy = Mock(return_value=unavailable)
        with self.assertRaisesRegex(AssertionError, "no available receiving application"):
            phone_actions.confirm_review(ui, "calendar_event", "Ouvrir l’agenda")

    def test_open_review_ignores_dialog_with_stale_page_window_or_focus(self):
        page = node_tree("Vérifier le lieu", window_id="10")
        stale_window_dialog = node_tree("Annuler", "Ouvrir la carte", window_id="10")
        new_window_dialog = node_tree("Annuler", "Ouvrir la carte", window_id="14")
        hierarchies = iter([page, stale_window_dialog, new_window_dialog, new_window_dialog])
        windows = iter([MAIN_FOCUS, "mCurrentFocus=Window{4444 u0 Lieu à rechercher}"])
        device = Mock()
        device.shell.side_effect = lambda *args: next(windows) if args == ("dumpsys", "window") else ""
        ui = Screen(device, Mock())
        ui.hierarchy = Mock(side_effect=lambda: next(hierarchies))
        with patch("verification.unifiedpush.stack.time.sleep"):
            wid, focus = phone_actions.open_review(ui, "Vérifier le lieu", "Ouvrir la carte", "10", MAIN_WINDOW)
        self.assertEqual(wid, "14")
        self.assertEqual(focus, "Window{4444 u0 Lieu à rechercher}")

    def test_back_to_page_waits_for_hermes_window_and_retries_unlock_and_trial_open(self):
        foreign = node_tree("Maps", package="com.google.android.apps.maps", window_id="2")
        chooser = node_tree("Choisir un profil", "Actions123", window_id="3")
        unlock = node_tree("Phrase secrète du profil", "Déverrouiller", window_id="3")
        thread = node_tree("Essayer dans la discussion", window_id="3")
        sheet = node_tree("En essai dans cette discussion", "Vérifier le lieu", window_id="3")
        hierarchies = iter([
            foreign,   # wait for APP window
            chooser,   # APP window arrived
            chooser,   # tap_until(account_name) attempt 1 -> tap (swallowed)
            chooser,   # tap_until(account_name) attempt 2 -> tap
            unlock,    # tap_until(account_name) attempt 3 -> unlocked screen visible
            unlock,    # if ui.node("Déverrouiller") is not None
            unlock,    # tap_until("Déverrouiller") attempt 1 -> tap
            thread,    # tap_until("Déverrouiller") attempt 2 -> unlocked!
            thread,    # if ui.node(label) is None
            thread,    # and ui.node("En essai dans cette discussion") is None
            thread,    # tap_until("Essayer dans la discussion") attempt 1 -> tap
            sheet,     # tap_until("Essayer dans la discussion") attempt 2 -> sheet visible
            sheet,     # ui.wait(label, scroll=True)
        ])
        device = Mock()
        device.shell.side_effect = lambda *args: MAIN_ACTIVITY if args == ("dumpsys", "activity", "activities") else ""
        ui = Screen(device, Mock())
        ui.hierarchy = Mock(side_effect=lambda: next(hierarchies))
        ui.field = Mock()
        with patch("verification.unifiedpush.stack.time.sleep"):
            phone_actions.back_to_page(ui, "Actions123", "secretphrase", "Vérifier le lieu")
        ui.field.assert_called_once_with("Phrase secrète du profil", "secretphrase")

    def test_inbound_drafts_retries_swallowed_profile_chooser_and_confirm_taps(self):
        account = "Actionsabc"
        session = "session-123"
        first_text = "Note de préparation pour Laval."
        second_text = "dessin à Laval"
        combined = first_text + "\n" + second_text

        chooser = node_tree("Choisir un profil", account, window_id="20")
        unlock = node_tree("Phrase secrète du profil", "Déverrouiller", window_id="20")
        review1 = node_tree("Ajouter au brouillon ?", account, session, first_text, "Ajouter au brouillon", window_id="21")
        composer1 = node_tree("Envoyer", first_text, window_id="20")
        review2 = node_tree("Ajouter au brouillon ?", account, session, second_text, "Ajouter au brouillon", window_id="22")
        composer2 = node_tree("Envoyer", combined, window_id="20")

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
        ui = Screen(device, Mock())
        ui.hierarchy = Mock(side_effect=lambda: next(hierarchies))
        ui.field = Mock()
        with patch("verification.unifiedpush.stack.time.sleep"):
            results = phone_actions.inbound_drafts(ui, account, "secretphrase", session)
        self.assertEqual(
            results,
            [
                {"kind": "share", "source_uid": "shell", "unsent_draft": first_text},
                {"kind": "selection", "source_uid": "shell", "unsent_draft": combined},
            ],
        )


if __name__ == "__main__":
    unittest.main()
