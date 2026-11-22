import unittest
import xml.etree.ElementTree as ET

from verification.nearby_location import COARSE, location_access, public_city_fix
from verification.nearby_restaurants import enter_discussion
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
            'package="org.llm4people.hermes" window-id="1" scrollable="false" bounds="[12,560][240,620]"/>'
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
