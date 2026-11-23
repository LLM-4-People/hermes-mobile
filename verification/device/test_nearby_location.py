import unittest
import xml.etree.ElementTree as ET

from verification.nearby_journey import CONSENT, PROMPT, USE_LOCATION, choose_consent, enter_message, lock, open_consent
from verification.nearby_location import (COARSE, answer_android_location, app_activity_stopped,
                                          location_access, public_city_fix)
from verification.nearby_restaurants import enter_discussion, matches_map_coordinate
from verification.unifiedpush.ui import Screen


class AndroidShell:
    def __init__(self, fail_at=None):
        self.calls = []
        self.fail_at = fail_at

    def shell(self, *args):
        self.calls.append(args)
        if args == self.fail_at:
            raise RuntimeError("injected failure")
        return {
            ("getprop", "ro.kernel.qemu"): "1",
            ("id", "-u"): "2000",
            ("cmd", "location", "is-location-enabled", "--user", "0"): "false",
            ("cmd", "appops", "get", "com.android.shell", "android:mock_location"): "No operations.\nDefault mode: deny",
        }.get(args, "")


class NearbyLocationTest(unittest.TestCase):
    def test_both_platform_providers_get_the_same_public_fix_and_guest_state_is_restored(self):
        guest = AndroidShell()
        with public_city_fix(guest) as fix:
            fix.publish()
        injected = [args for args in guest.calls if "set-test-provider-location" in args]
        self.assertEqual([args[4] for args in injected], ["gps", "fused"])
        self.assertTrue(all(args[6] == "45.5089,-73.5663" for args in injected))
        self.assertEqual(guest.calls[-4:], [
            ("cmd", "location", "providers", "remove-test-provider", "fused"),
            ("cmd", "location", "providers", "remove-test-provider", "gps"),
            ("cmd", "location", "set-location-enabled", "false", "--user", "0"),
            ("cmd", "appops", "set", "com.android.shell", "android:mock_location", "deny"),
        ])

    def test_partial_provider_setup_still_undoes_the_changes_already_made(self):
        guest = AndroidShell(("cmd", "location", "providers", "add-test-provider", "fused", "--accuracy", "1"))
        with self.assertRaisesRegex(RuntimeError, "injected failure"):
            with public_city_fix(guest):
                self.fail("unreachable")
        self.assertEqual(guest.calls[-3], ("cmd", "location", "providers", "remove-test-provider", "gps"))
        self.assertEqual(guest.calls[-1][-1], "deny")

    def test_fine_or_background_declarations_fail_even_if_only_coarse_was_granted(self):
        class Package:
            def __init__(self, permissions):
                self.permissions = permissions

            def shell(self, *args):
                return self.permissions
        self.assertFalse(location_access(Package(COARSE + ": granted=false, flags=[ ]")))
        self.assertTrue(location_access(Package(COARSE + ": granted=true, flags=[ USER_SET ]")))
        for extra in ("FINE", "BACKGROUND"):
            with self.subTest(extra=extra), self.assertRaisesRegex(AssertionError, "fine or background"):
                location_access(Package(COARSE + ": granted=true\nandroid.permission.ACCESS_" + extra + "_LOCATION"))

    def test_wait_for_install_retries_until_coarse_permission_is_reported(self):
        class DelayedPackage:
            def __init__(self, outputs):
                self.outputs = iter(outputs)
                self.calls = 0

            def shell(self, *args):
                self.calls += 1
                return next(self.outputs)

        missing = DelayedPackage(["Package not ready yet"])
        with self.assertRaisesRegex(AssertionError, "coarse runtime permission"):
            location_access(missing)
        delayed = DelayedPackage(["", COARSE + ": granted=false, flags=[ ]"])
        self.assertFalse(location_access(delayed, wait_for_install=True))
        self.assertEqual(delayed.calls, 2)

    def test_enter_discussion_taps_focused_text_button_without_clickable_attribute(self):
        # FocusedText.java omits clickable; Button distinguishes inbox create from the open header TextView.
        inbox = ET.fromstring(
            '<node text="" content-desc="" resource-id="" class="android.widget.FrameLayout" '
            'package="org.llm4people.hermes" window-id="1" scrollable="false" bounds="[0,0][320,640]">'
            '<node text="Nouvelle conversation" content-desc="" resource-id="" class="android.widget.Button" '
            'package="org.llm4people.hermes" window-id="1" scrollable="false" bounds="[0,80][320,140]"/>'
            '</node>'
        )
        loading_chat = ET.fromstring(
            '<node text="" content-desc="" resource-id="" class="android.widget.FrameLayout" '
            'package="org.llm4people.hermes" window-id="1" scrollable="false" bounds="[0,0][320,640]">'
            '<node text="Nouvelle conversation" content-desc="" resource-id="" class="android.widget.TextView" '
            'package="org.llm4people.hermes" window-id="1" scrollable="false" bounds="[48,0][200,48]"/>'
            '</node>'
        )
        ready_chat = ET.fromstring(
            '<node text="" content-desc="" resource-id="" class="android.widget.FrameLayout" '
            'package="org.llm4people.hermes" window-id="1" scrollable="false" bounds="[0,0][320,640]">'
            '<node text="Nouvelle conversation" content-desc="" resource-id="" class="android.widget.TextView" '
            'package="org.llm4people.hermes" window-id="1" scrollable="false" bounds="[48,0][200,48]"/>'
            '<node text="Message à Hermes" content-desc="" resource-id="" class="android.widget.EditText" '
            'package="org.llm4people.hermes" window-id="1" scrollable="false" bounds="[12,560][210,620]"/>'
            '<node text="Envoyer" content-desc="" resource-id="" class="android.widget.Button" '
            'package="org.llm4people.hermes" window-id="1" scrollable="false" bounds="[220,560][310,620]"/>'
            '</node>'
        )
        trees = iter([inbox, loading_chat, ready_chat])
        tapped = []

        class StubScreen(Screen):
            def __init__(self):
                pass

            def hierarchy(self):
                return next(trees)

            def tap_node(self, node):
                tapped.append((node.get("class"), node.get("text")))

        enter_discussion(StubScreen())
        self.assertEqual(tapped, [("android.widget.Button", "Nouvelle conversation")])

    def test_enter_discussion_and_enter_message_accept_restored_non_empty_draft(self):
        restored_chat = ET.fromstring(
            '<node text="" content-desc="" resource-id="" class="android.widget.FrameLayout" '
            'package="org.llm4people.hermes" window-id="1" scrollable="false" bounds="[0,0][320,640]">'
            '<node text="Nouvelle conversation" content-desc="" resource-id="" class="android.widget.TextView" '
            'package="org.llm4people.hermes" window-id="1" scrollable="false" bounds="[48,0][200,48]"/>'
            f'<node text="{PROMPT}" content-desc="" resource-id="" class="android.widget.EditText" '
            'package="org.llm4people.hermes" window-id="1" scrollable="false" bounds="[12,560][210,620]"/>'
            '<node text="Envoyer" content-desc="" resource-id="" class="android.widget.Button" '
            'package="org.llm4people.hermes" window-id="1" scrollable="false" bounds="[220,560][310,620]"/>'
            '</node>'
        )
        sent = []

        class StubControls:
            def send(self, *args, **kwargs):
                sent.append((args, kwargs))
                return b"applied"

        class StubDevice:
            def ime_shown(self):
                return False

        class StubScreen(Screen):
            def __init__(self):
                self.device = StubDevice()
                self.controls = StubControls()

            def hierarchy(self):
                return restored_chat

        ui = StubScreen()
        enter_discussion(ui)
        enter_message(ui, PROMPT)
        self.assertEqual(sent, [])
        enter_message(ui, "Autre question")
        self.assertEqual(sent, [(("text",), {"label": PROMPT, "value": "Autre question"})])

    def test_answer_android_location_matches_google_controller_aosp_resource_id_and_scrolls(self):
        top_dialog = ET.fromstring(
            '<node text="" content-desc="" resource-id="" class="android.widget.ScrollView" '
            'package="com.google.android.permissioncontroller" window-id="2" scrollable="true" bounds="[0,80][320,640]">'
            '<node text="Allow Hermes Mobile to access this device’s approximate location?" content-desc="" '
            'resource-id="com.android.permissioncontroller:id/permission_message" class="android.widget.TextView" '
            'package="com.google.android.permissioncontroller" window-id="2" scrollable="false" bounds="[16,100][304,180]"/>'
            '</node>'
        )
        scrolled_dialog = ET.fromstring(
            '<node text="" content-desc="" resource-id="" class="android.widget.ScrollView" '
            'package="com.google.android.permissioncontroller" window-id="2" scrollable="true" bounds="[0,80][320,640]">'
            '<node text="Don’t allow" content-desc="" '
            'resource-id="com.android.permissioncontroller:id/permission_deny_button" class="android.widget.Button" '
            'package="com.google.android.permissioncontroller" window-id="2" scrollable="false" bounds="[16,520][304,580]"/>'
            '</node>'
        )
        dismissed_to_app = ET.fromstring(
            '<node text="" content-desc="" resource-id="" class="android.widget.FrameLayout" '
            'package="org.llm4people.hermes" window-id="1" scrollable="false" bounds="[0,0][320,640]"/>'
        )
        trees = iter([top_dialog, scrolled_dialog, dismissed_to_app])
        actions = []

        class StubDevice:
            def shell(self, *args):
                return COARSE + ": granted=false, flags=[ ]"

        class StubScreen(Screen):
            def __init__(self):
                self.device = StubDevice()

            def hierarchy(self):
                return next(trees)

            def scroll(self, root):
                actions.append("scroll")

            def tap_node(self, node):
                actions.append(("tap", node.get("resource-id")))

        controller = answer_android_location(StubScreen(), allow=False)
        self.assertEqual(controller, "com.google.android.permissioncontroller")
        self.assertEqual(actions, ["scroll", ("tap", "com.android.permissioncontroller:id/permission_deny_button")])

    def test_answer_android_location_retries_deny_until_dialog_actually_dismisses_and_scrolls_clipped_button(self):
        clipped_dialog = ET.fromstring(
            '<node text="" content-desc="" resource-id="" class="android.widget.ScrollView" '
            'package="com.google.android.permissioncontroller" window-id="2" scrollable="true" bounds="[0,80][320,600]">'
            '<node text="Allow Hermes Mobile to access this device’s approximate location?" content-desc="" '
            'resource-id="com.android.permissioncontroller:id/permission_message" class="android.widget.TextView" '
            'package="com.google.android.permissioncontroller" window-id="2" scrollable="false" bounds="[16,100][304,180]"/>'
            '<node text="Don’t allow" content-desc="" '
            'resource-id="com.android.permissioncontroller:id/permission_deny_button" class="android.widget.Button" '
            'package="com.google.android.permissioncontroller" window-id="2" scrollable="false" bounds="[16,592][304,640]"/>'
            '</node>'
        )
        settled_dialog = ET.fromstring(
            '<node text="" content-desc="" resource-id="" class="android.widget.ScrollView" '
            'package="com.google.android.permissioncontroller" window-id="2" scrollable="true" bounds="[0,80][320,600]">'
            '<node text="Don’t allow" content-desc="" '
            'resource-id="com.android.permissioncontroller:id/permission_deny_button" class="android.widget.Button" '
            'package="com.google.android.permissioncontroller" window-id="2" scrollable="false" bounds="[16,500][304,560]"/>'
            '</node>'
        )
        dismissed_to_app = ET.fromstring(
            '<node text="" content-desc="" resource-id="" class="android.widget.FrameLayout" '
            'package="org.llm4people.hermes" window-id="1" scrollable="false" bounds="[0,0][320,640]"/>'
        )
        # First poll sees clipped button -> scrolls; second poll taps (swallowed by animation);
        # third poll still sees dialog -> retries tap; fourth poll sees app -> returns!
        trees = iter([clipped_dialog, settled_dialog, settled_dialog, dismissed_to_app])
        actions = []

        class StubDevice:
            def shell(self, *args):
                return COARSE + ": granted=false, flags=[ ]"

        class StubScreen(Screen):
            def __init__(self):
                self.device = StubDevice()

            def hierarchy(self):
                return next(trees)

            def scroll(self, root):
                actions.append("scroll")

            def tap_node(self, node):
                actions.append(("tap", node.get("resource-id")))

        controller = answer_android_location(StubScreen(), allow=False)
        self.assertEqual(controller, "com.google.android.permissioncontroller")
        self.assertEqual(actions, [
            "scroll",
            ("tap", "com.android.permissioncontroller:id/permission_deny_button"),
            ("tap", "com.android.permissioncontroller:id/permission_deny_button"),
        ])

    def test_choose_consent_retries_until_consent_dialog_dismisses(self):
        consent_open = ET.fromstring(
            '<node text="" content-desc="" resource-id="" class="android.widget.FrameLayout" '
            'package="org.llm4people.hermes" window-id="3" scrollable="false" bounds="[0,0][320,640]">'
            f'<node text="{CONSENT}" content-desc="" resource-id="" class="android.widget.TextView" '
            'package="org.llm4people.hermes" window-id="3" scrollable="false" bounds="[16,100][304,160]"/>'
            f'<node text="{USE_LOCATION}" content-desc="" resource-id="" class="android.widget.Button" '
            'package="org.llm4people.hermes" window-id="3" scrollable="false" bounds="[16,440][304,500]"/>'
            '</node>'
        )
        consent_closed = ET.fromstring(
            '<node text="" content-desc="" resource-id="" class="android.widget.FrameLayout" '
            'package="org.llm4people.hermes" window-id="1" scrollable="false" bounds="[0,0][320,640]"/>'
        )
        trees = iter([consent_open, consent_open, consent_closed])
        taps = []

        class StubScreen(Screen):
            def __init__(self):
                pass

            def hierarchy(self):
                return next(trees)

            def tap_node(self, node):
                taps.append(node.get("text"))

        choose_consent(StubScreen(), USE_LOCATION)
        self.assertEqual(taps, [USE_LOCATION, USE_LOCATION])

    def test_open_consent_dismisses_ime_and_retries_send_tap_until_disclosure_dialog_appears(self):
        composer = ET.fromstring(
            '<node text="" content-desc="" resource-id="" class="android.widget.FrameLayout" '
            'package="org.llm4people.hermes" window-id="1" scrollable="false" bounds="[0,0][320,640]">'
            '<node text="Envoyer" content-desc="" resource-id="" class="android.widget.Button" '
            'package="org.llm4people.hermes" window-id="1" scrollable="false" bounds="[220,560][310,620]"/>'
            '</node>'
        )
        consent_dialog = ET.fromstring(
            '<node text="" content-desc="" resource-id="" class="android.widget.FrameLayout" '
            'package="org.llm4people.hermes" window-id="3" scrollable="false" bounds="[0,80][320,600]">'
            f'<node text="{CONSENT}" content-desc="" resource-id="" class="android.widget.TextView" '
            'package="org.llm4people.hermes" window-id="3" scrollable="false" bounds="[16,100][304,160]"/>'
            '<node text="Hermes reçoit la zone et peut la transmettre à OpenStreetMap." content-desc="" resource-id="" '
            'class="android.widget.TextView" package="org.llm4people.hermes" window-id="3" scrollable="false" bounds="[16,170][304,260]"/>'
            '</node>'
        )
        # First poll: IME reopened after PermissionController -> dismiss BACK;
        # second & third polls: tap Envoyer until consent dialog opens.
        trees = iter([composer, composer, composer, consent_dialog])
        ime_states = iter([False, True, False, False, False])
        actions = []

        class StubDevice:
            def ime_shown(self):
                return next(ime_states)

            def shell(self, *args):
                actions.append(("shell", args))
                return ""

        class StubScreen(Screen):
            def __init__(self):
                self.device = StubDevice()

            def hierarchy(self):
                return next(trees)

            def tap_node(self, node):
                actions.append(("tap", node.get("text")))

        open_consent(StubScreen())
        self.assertEqual(actions, [
            ("shell", ("input", "keyevent", "BACK")),
            ("tap", "Envoyer"),
            ("tap", "Envoyer"),
        ])

    def test_lock_scrolls_compact_overflow_menu_to_verrouiller_le_profil(self):
        thread_screen = ET.fromstring(
            '<node text="" content-desc="" resource-id="" class="android.widget.FrameLayout" '
            'package="org.llm4people.hermes" window-id="1" scrollable="false" bounds="[0,0][320,640]">'
            '<node text="" content-desc="Profil et verrouillage" resource-id="" class="android.widget.ImageButton" '
            'package="org.llm4people.hermes" window-id="1" scrollable="false" bounds="[260,24][316,72]"/>'
            '</node>'
        )
        menu_top = ET.fromstring(
            '<node text="" content-desc="" resource-id="" class="android.widget.ListView" '
            'package="org.llm4people.hermes" window-id="4" scrollable="true" bounds="[80,48][316,580]">'
            '<node text="Joindre une image…" content-desc="" resource-id="" class="android.widget.TextView" '
            'package="org.llm4people.hermes" window-id="4" scrollable="false" bounds="[88,60][308,108]"/>'
            '</node>'
        )
        menu_bottom = ET.fromstring(
            '<node text="" content-desc="" resource-id="" class="android.widget.ListView" '
            'package="org.llm4people.hermes" window-id="4" scrollable="true" bounds="[80,48][316,580]">'
            '<node text="Verrouiller le profil" content-desc="" resource-id="" class="android.widget.TextView" '
            'package="org.llm4people.hermes" window-id="4" scrollable="false" bounds="[88,500][308,548]"/>'
            '</node>'
        )
        profile_chooser = ET.fromstring(
            '<node text="" content-desc="" resource-id="" class="android.widget.FrameLayout" '
            'package="org.llm4people.hermes" window-id="1" scrollable="false" bounds="[0,0][320,640]">'
            '<node text="Choisir un profil" content-desc="" resource-id="" class="android.widget.TextView" '
            'package="org.llm4people.hermes" window-id="1" scrollable="false" bounds="[16,40][304,96]"/>'
            '</node>'
        )
        trees = iter([thread_screen, menu_top, menu_top, menu_bottom, profile_chooser])
        actions = []

        class StubScreen(Screen):
            def __init__(self):
                pass

            def hierarchy(self):
                return next(trees)

            def scroll(self, root):
                actions.append("scroll")

            def tap_node(self, node):
                actions.append(("tap", node.get("text") or node.get("content-desc")))

        lock(StubScreen())
        self.assertEqual(actions, [
            ("tap", "Profil et verrouillage"),
            "scroll",
            ("tap", "Verrouiller le profil"),
        ])

    def test_matches_map_coordinate_handles_padded_decimals_and_dms_and_rejects_other_venues(self):
        self.assertTrue(matches_map_coordinate("45.506,-73.57", 45.506, -73.57))
        self.assertTrue(matches_map_coordinate("45.506000, -73.570000", 45.506, -73.57))
        self.assertTrue(matches_map_coordinate('45°30\'21.6"N 73°34\'12.0"W', 45.506, -73.57))
        self.assertFalse(matches_map_coordinate("45.511000, -73.570000", 45.506, -73.57))
        self.assertFalse(matches_map_coordinate('45°30\'39.6"N 73°34\'12.0"W', 45.506, -73.57))

    def test_app_activity_stopped_waits_for_stopped_state_and_ignores_test_package(self):
        paused_dump = (
            "  * Hist  #0: ActivityRecord{aaa u0 org.llm4people.hermes/.MainActivity t12}\n"
            "    packageName=org.llm4people.hermes processName=org.llm4people.hermes\n"
            "    state=PAUSED stopped=false delayedResume=false finishing=false\n"
            "  * Hist  #1: ActivityRecord{bbb u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t1}\n"
            "    packageName=com.google.android.apps.nexuslauncher processName=com.google.android.apps.nexuslauncher\n"
            "    state=RESUMED stopped=false delayedResume=false finishing=false\n"
        )
        stopping_dump = paused_dump.replace("state=PAUSED", "state=STOPPING")
        stopped_dump = (
            "  * Hist  #0: ActivityRecord{aaa u0 org.llm4people.hermes/.MainActivity t12}\n"
            "    packageName=org.llm4people.hermes processName=org.llm4people.hermes\n"
            "    state=STOPPED stopped=true delayedResume=false finishing=false\n"
            "  * Hist  #1: ActivityRecord{ccc u0 org.llm4people.hermes.test/.HelperActivity t13}\n"
            "    packageName=org.llm4people.hermes.test processName=org.llm4people.hermes.test\n"
            "    state=RESUMED stopped=false delayedResume=false finishing=false\n"
        )
        self.assertFalse(app_activity_stopped(paused_dump))
        self.assertFalse(app_activity_stopped(stopping_dump))
        self.assertTrue(app_activity_stopped(stopped_dump))
        self.assertTrue(app_activity_stopped(""))

    def test_answer_android_location_rejects_non_location_permission_prompt(self):
        notification_dialog = ET.fromstring(
            '<node text="" content-desc="" resource-id="" class="android.widget.FrameLayout" '
            'package="com.google.android.permissioncontroller" window-id="2" scrollable="false" bounds="[0,80][320,640]">'
            '<node text="Allow Hermes Mobile to send you notifications?" content-desc="" '
            'resource-id="com.android.permissioncontroller:id/permission_message" class="android.widget.TextView" '
            'package="com.google.android.permissioncontroller" window-id="2" scrollable="false" bounds="[16,100][304,180]"/>'
            '<node text="Don’t allow" content-desc="" '
            'resource-id="com.android.permissioncontroller:id/permission_deny_button" class="android.widget.Button" '
            'package="com.google.android.permissioncontroller" window-id="2" scrollable="false" bounds="[16,520][304,580]"/>'
            '</node>'
        )

        class StubScreen(Screen):
            def __init__(self):
                pass

            def hierarchy(self):
                return notification_dialog

            def tap_node(self, node):
                raise AssertionError("Should not tap a non-location permission dialog")

        with self.assertRaisesRegex(AssertionError, "did not ask for location"):
            answer_android_location(StubScreen(), allow=False)
