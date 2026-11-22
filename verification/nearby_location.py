"""Exercise Android's platform location services and real permission controller on a disposable guest."""

from contextlib import contextmanager, ExitStack
import re

from verification.nearby_fixture import MONTREAL
from verification.unifiedpush.stack import eventually
from verification.unifiedpush.ui import APP


COARSE = "android.permission.ACCESS_COARSE_LOCATION"
PERMISSION_CONTROLLERS = {"com.android.permissioncontroller", "com.google.android.permissioncontroller"}


def location_access(device):
    dump = device.shell("dumpsys", "package", APP)
    if re.search(r'android\.permission\.ACCESS_(?:FINE|BACKGROUND)_LOCATION\b', dump):
        raise AssertionError("The app declares fine or background location")
    match = re.search(r'android\.permission\.ACCESS_COARSE_LOCATION: granted=(true|false)', dump)
    if not match:
        raise AssertionError("Android did not report the app's coarse runtime permission")
    return match[1] == "true"


def answer_android_location(ui, *, allow):
    resource = "permission_allow_foreground_only_button" if allow else "permission_deny_button"

    def find_prompt():
        root = ui.hierarchy()
        for node in root.iter("node"):
            package = node.get("package")
            if package in PERMISSION_CONTROLLERS and node.get("resource-id") == package + ":id/" + resource:
                prompt = " ".join(item.get("text", "") for item in root.iter("node")
                                  if item.get("package") == package)
                if "location" not in prompt.casefold():
                    raise AssertionError("The foreground system permission prompt did not ask for location")
                return node, package
        return None

    node, package = eventually("Android's location permission dialog", find_prompt)
    ui.tap_node(node)
    eventually("the Android location permission decision", lambda: location_access(ui.device) == allow)
    return package


class PublicCityFix:
    def __init__(self, device):
        self.device = device

    def enabled(self, on):
        self.device.shell("cmd", "location", "set-location-enabled", str(on).lower(), "--user", "0")

    def publish(self):
        # GPS records the explicit public fix; fused is the foreground provider the coarse-only
        # app subscribes to. Framework coarse obfuscation still takes place in LocationManager.
        coordinates = ",".join(str(value) for value in MONTREAL)
        for provider in ("gps", "fused"):
            self.device.shell("cmd", "location", "providers", "set-test-provider-location", provider,
                              "--location", coordinates, "--accuracy", "35")


@contextmanager
def public_city_fix(device):
    if device.shell("getprop", "ro.kernel.qemu") != "1" or device.shell("id", "-u") != "2000":
        raise ValueError("Use a disposable emulator with a normal Android shell")
    current = device.shell("cmd", "location", "is-location-enabled", "--user", "0")
    if current not in ("true", "false"):
        raise AssertionError("Cannot read the Android user's location setting")
    mode = device.shell("cmd", "appops", "get", "com.android.shell", "android:mock_location")
    match = re.search(r'(?:MOCK_LOCATION|android:mock_location):\s*(allow|ignore|deny|default|foreground)\b', mode)
    if match:
        previous = match[1]
    elif "No operations" in mode:
        default = re.search(r'Default mode:\s*(allow|ignore|deny|default|foreground)\b', mode)
        if not default:
            raise ValueError("Cannot determine the default mock-location app-op")
        previous = default[1]
    else:
        raise ValueError("Cannot read the shell mock-location app-op on this emulator")
    fix = PublicCityFix(device)
    with ExitStack() as cleanup:
        device.shell("cmd", "appops", "set", "com.android.shell", "android:mock_location", "allow")
        cleanup.callback(device.shell, "cmd", "appops", "set", "com.android.shell", "android:mock_location", previous)
        fix.enabled(True)
        cleanup.callback(fix.enabled, current == "true")
        for provider in ("gps", "fused"):
            args = ("cmd", "location", "providers")
            device.shell(*args, "add-test-provider", provider, "--accuracy", "1")
            cleanup.callback(device.shell, *args, "remove-test-provider", provider)
            device.shell(*args, "set-test-provider-enabled", provider, "true")
        yield fix
