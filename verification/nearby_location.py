"""Exercise Android's platform location services and real permission controller on a disposable guest."""

from contextlib import contextmanager, ExitStack
import json
import re
import sys

from verification.nearby_fixture import MONTREAL
from verification.unifiedpush.shade import bounds
from verification.unifiedpush.stack import eventually
from verification.unifiedpush.ui import APP


COARSE = "android.permission.ACCESS_COARSE_LOCATION"
PERMISSION_CONTROLLERS = {"com.android.permissioncontroller", "com.google.android.permissioncontroller"}
MIN_TAP_HEIGHT = 24


def location_access(device, *, wait_for_install=False):
    if wait_for_install:
        def ready():
            result = _location_grant(device)
            return (result,) if result is not None else None
        return eventually("the installed coarse runtime permission", ready, timeout=30)[0]
    grant = _location_grant(device)
    if grant is None:
        raise AssertionError("Android did not report the app's coarse runtime permission")
    return grant


def _location_grant(device):
    dump = device.shell("dumpsys", "package", APP)
    if re.search(r'android\.permission\.ACCESS_(?:FINE|BACKGROUND)_LOCATION\b', dump):
        raise AssertionError("The app declares fine or background location")
    match = re.search(r'android\.permission\.ACCESS_COARSE_LOCATION: granted=(true|false)', dump)
    return match[1] == "true" if match else None


def app_activity_stopped(dump):
    """Return True once every ActivityRecord for APP in `dumpsys activity activities` is STOPPED/DESTROYED."""
    in_app = False
    app_pkg = re.compile(rf"\bpackageName={re.escape(APP)}(?:\s|$)")
    for line in dump.splitlines():
        stripped = line.strip()
        if (
            stripped.startswith("* Hist")
            or stripped.startswith("* Task")
            or stripped.startswith("ActivityRecord{")
            or (stripped.startswith("packageName=") and not app_pkg.search(stripped))
        ):
            in_app = False
        if app_pkg.search(stripped):
            in_app = True
        elif in_app and (match := re.search(r"\bstate=([A-Z_]+)\b", stripped)):
            if match.group(1) not in ("STOPPED", "DESTROYED"):
                return False
            in_app = False
    return True


def _clipped_in_scrollable(target, controller_nodes):
    box = bounds(target)
    if len(box) != 4:
        return False
    _, y1, _, y2 = box
    if y2 - y1 < MIN_TAP_HEIGHT:
        return True
    for node in controller_nodes:
        if node.get("scrollable") == "true":
            sbox = bounds(node)
            if len(sbox) == 4 and (y1 < sbox[1] or y2 > sbox[3]):
                return True
    return False


def answer_android_location(ui, *, allow):
    resource = "permission_allow_foreground_only_button" if allow else "permission_deny_button"
    targets = {controller + ":id/" + resource for controller in PERMISSION_CONTROLLERS}
    saw_location = set()
    acted_controller = None
    last_clipped = None
    last_controller_ids = []
    taps = 0
    scrolls = 0
    last_tap = None

    def decide():
        nonlocal acted_controller, last_clipped, last_controller_ids, taps, scrolls, last_tap
        from verification.unifiedpush import ui as _ui
        root = ui.hierarchy()
        controller = None
        controller_nodes = []
        for node in root.iter("node"):
            package = node.get("package")
            if package in PERMISSION_CONTROLLERS:
                if controller is None:
                    controller = package
                if package == controller:
                    controller_nodes.append(node)
        if controller is None:
            if acted_controller is not None and location_access(ui.device) == allow:
                return acted_controller
            return None
        last_controller_ids = sorted({item.get("resource-id", "") for item in controller_nodes
                                      if item.get("resource-id")})
        prompt = " ".join(item.get("text", "") for item in controller_nodes)
        if "location" in prompt.casefold():
            saw_location.add(controller)
        target = next((item for item in controller_nodes if item.get("resource-id") in targets), None)
        if target is not None:
            if controller not in saw_location:
                raise AssertionError("The foreground system permission prompt did not ask for location")
            target_bounds = target.get("bounds")
            if _clipped_in_scrollable(target, controller_nodes) and target_bounds != last_clipped:
                last_clipped = target_bounds
                ui.scroll(root)
                scrolls += 1
                return None
            now = _ui.monotonic()
            if last_tap is None or now - last_tap >= _ui.TAP_RETRY_COOLDOWN_SECONDS:
                ui.tap_node(target)
                acted_controller = controller
                taps += 1
                last_tap = now
            return None
        ui.scroll(root)
        scrolls += 1
        return None

    try:
        return eventually("Android's location permission decision", decide)
    except AssertionError as failure:
        if "did not ask for location" not in str(failure):
            print("Android location permission " + json.dumps({
                "allow": allow,
                "acted_controller": acted_controller,
                "saw_location": sorted(saw_location),
                "taps": taps,
                "scrolls": scrolls,
                "controller_ids": last_controller_ids,
            }, sort_keys=True), file=sys.stderr, flush=True)
        raise


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
