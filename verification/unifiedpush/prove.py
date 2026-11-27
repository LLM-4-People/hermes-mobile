"""Verify a real ntfy wake-up, its private Android deep link, and device revocation."""

import argparse
from contextlib import ExitStack, closing, contextmanager
import json
import os
from pathlib import Path
import secrets
import re
import shlex
import shutil
import sqlite3
import sys
import tempfile
from urllib.parse import urlsplit

from .assets import APK, SERVER, digest, extract_server
from verification.device.installed import installed_apk_sha256
from .editor import editor
try:
    from verification.device.run import Emulator, cleanup_all
except ModuleNotFoundError:
    from device.run import Emulator, cleanup_all
from verification.gateway_fixture import Gateway, PINNED_REVISION, check_source
from .stack import (
    LOCAL_HTTP,
    bridge,
    caddy_binary,
    eventually,
    json_request,
    ntfy,
    proxy,
    wait_for_ntfy_subscriber,
)
from .ui import (
    APP,
    Screen,
    distributor,
    enroll,
    launch,
    open_notifications_settings,
    process_absent,
    start_account,
    tap_notice,
)


PROJECT = Path(__file__).resolve().parents[2]


@contextmanager
def same_port(device, url):
    port = urlsplit(url).port
    spec = f"tcp:{port}"
    if any(spec in entry.split()[1:2] for entry in device.run("reverse", "--list").decode().splitlines()):
        raise RuntimeError("Fixture port already in use on emulator")
    device.run("reverse", "--no-rebind", spec, spec)
    try:
        yield url
    finally:
        cleanup_all(lambda: device.run("reverse", "--remove", spec))


def active_registration(database):
    with closing(sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)) as db:
        return db.execute(
            "SELECT id,token FROM devices WHERE provider='unifiedpush' AND token IS NOT NULL AND token!=''"
        ).fetchone()


def revoked_everywhere(database, device_id, event_id=None):
    with closing(sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)) as db:
        if db.execute("SELECT 1 FROM devices WHERE id=?", (device_id,)).fetchone():
            return False
        if event_id is None:
            return True
        return (
            db.execute(
                "SELECT 1 FROM deliveries WHERE event_id=? AND device_id=?",
                (event_id, device_id),
            ).fetchone()
            is None
        )


def trigger(gateway, title):
    session = gateway.request("/api/sessions", method="POST", body={"title": title}, expected=201)["session"][
        "id"
    ]
    result = gateway.request(
        "/v1/runs",
        method="POST",
        body={"session_id": session, "input": "Bonjour"},
        headers={"Idempotency-Key": secrets.token_hex(24)},
        expected=202,
    )
    return session, result["run_id"]


def completed_event(endpoint, key, run):
    page = json_request(endpoint + "/api/mobile/push/events", key)
    return next(
        (item for item in page["events"] if item["kind"] == "run_completed" and item["run_id"] == run),
        None,
    )


def published_messages(topic):
    parts = urlsplit(topic)
    url = parts._replace(path=parts.path + "/json", query="poll=1&since=all").geturl()
    with LOCAL_HTTP.open(url, timeout=10) as response:
        return [obj for line in response if line.strip() and (obj := json.loads(line))["event"] == "message"]


def new_binary_publication(messages, before):
    if len(messages) == before:
        return False
    if len(messages) != before + 1 or messages[-1].get("encoding") != "base64":
        raise AssertionError("Expected one new binary Web Push on the registered topic")
    return True


def delivery_state(database, device, device_id, event_id):
    # Return counters and flags only. Database rows, topic URLs and distributor logs contain credentials.
    with closing(sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)) as db:
        row = db.execute("SELECT attempts FROM deliveries WHERE device_id=? AND event_id=?",
                         (device_id, event_id)).fetchone()
    permission = device.shell("dumpsys", "package", APP)
    services = device.shell("dumpsys", "activity", "services", "io.heckel.ntfy")
    return {
        "pending_relay_attempts": row[0] if row else None,
        "phone_notification_permission": "android.permission.POST_NOTIFICATIONS: granted=true" in permission,
        "distributor_subscriber_service": "SubscriberService" in services,
    }


def app_notice(device, event_id):
    # cmd notification list gives active notification keys; the application uses its event ID in the tag.
    for key in device.shell("cmd", "notification", "list").splitlines():
        parts = key.split("|")
        if len(parts) >= 5 and parts[1] == APP and parts[3].endswith("/" + event_id):
            return key
    return None


def notice_title(device, key):
    record = device.shell("cmd", "notification", "get", shlex.quote(key))
    title = re.search(r"^\s*android\.title=String \((.+)\)$", record, re.MULTILINE)
    if not title:
        raise AssertionError("Exact posted notification has no display title")
    return title.group(1)


@contextmanager
def disposable_app(device, apk):
    if device.shell("getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("Use a dedicated Android emulator")
    if int(device.shell("getprop", "ro.build.version.sdk")) < 33:
        raise RuntimeError("This test exercises Android 13+ Task Manager stop")
    packages = device.shell("pm", "list", "packages")
    if "package:com.android.systemui" not in packages.splitlines():
        raise RuntimeError("Use a full Android phone image with SystemUI")
    if device.shell("am", "get-current-user") != "0":
        raise RuntimeError("Use user 0 of a disposable test emulator")
    if "package:" + APP in packages.splitlines():
        raise RuntimeError("Use a fresh emulator without a Hermes Mobile installation")
    device.run("install", str(apk))
    try:
        installed = installed_apk_sha256(device, APP)
        if installed != digest(apk):
            raise AssertionError("Installed application differs from the supplied APK")
        yield installed
    finally:
        cleanup_all(lambda: device.run("uninstall", APP))


def run(args):
    if args.evidence_dir:
        args.evidence_dir.mkdir(mode=0o700, parents=True, exist_ok=False)
    source = args.source.resolve()
    check_source(source, args.revision)
    sys.path.insert(0, str(source))
    from tests.fakes.fake_llm_provider import FakeLLMServer

    adb = Emulator(args.serial, args.adb, args.adb_port)
    apk = APK.fetch(args.cache)
    archive = SERVER.fetch(args.cache)
    with tempfile.TemporaryDirectory(prefix="hm-notice-") as tmp, ExitStack() as stack:
        installed = stack.enter_context(disposable_app(adb, args.apk))
        root = Path(tmp)
        unique = secrets.token_hex(4)
        reply = "Réponse du fil " + unique
        fake = stack.enter_context(FakeLLMServer(default_text=reply))
        primary = stack.enter_context(Gateway(root / "one", source, fake.base_url))
        other = stack.enter_context(Gateway(root / "two", source, fake.base_url))
        ntfy_url = stack.enter_context(ntfy(root, extract_server(archive, root)))
        push, db = stack.enter_context(bridge(root, primary, ntfy_url, args.push_python, PROJECT))
        options = {
            "tls_cert": args.tls_cert,
            "tls_key": args.tls_key,
            "tls_ca": args.tls_ca,
            "executable": caddy_binary(root),
        }
        account = stack.enter_context(proxy(root, primary, push, **options))
        secondary = other.endpoint
        if args.tls_cert:
            second_proxy = root / "secondary-proxy"
            second_proxy.mkdir()
            secondary = stack.enter_context(proxy(second_proxy, other, None, **options))
        account_url = stack.enter_context(same_port(adb, account))
        other_url = stack.enter_context(same_port(adb, secondary))
        stack.enter_context(same_port(adb, ntfy_url))
        ui = Screen(adb, stack.enter_context(editor(adb, root)))
        stack.enter_context(distributor(ui, apk, ntfy_url))
        owner, alt = "Bureau-" + unique, "Autre-" + unique
        phrase = secrets.token_hex(14)
        title = "Discussion-" + unique
        try:
            s = ui.strings
            disable_push = s["push_settings_disable"]
            open_distributor = s["push_settings_open_distributor"]
            launch(adb, APP)
            start_account(ui, owner, account_url, primary.key, phrase)
            enroll(ui)
            device_id, topic = eventually(
                "authenticated device subscription", lambda: active_registration(db)
            )
            if not topic.startswith(ntfy_url + "/up"):
                raise AssertionError("Device registered with another push origin")
            before = len(published_messages(topic))
            ui.wait(disable_push)
            ui.tap_until(
                open_distributor,
                lambda root: ui.node("Subscribed topics", root) is not None,
                scroll=True,
                notification_permission=True,
                description="ntfy subscribed topics screen after tapping " + open_distributor,
            )
            wait_for_ntfy_subscriber(root, topic)
            launch(adb, APP)
            start_account(ui, alt, other_url, other.key, secrets.token_hex(14))
            # Confirm the same stream stays connected after leaving ntfy, before closing Hermes.
            wait_for_ntfy_subscriber(root, topic)
            process_absent(ui)
            session, run_id = trigger(primary, title)
            event = eventually(
                "terminal event in relay",
                lambda: completed_event(push, primary.key, run_id),
            )
            if event["session_id"] != session:
                raise AssertionError("Event lost its session association")
            try:
                eventually("one binary Web Push on the registered ntfy topic",
                           lambda: new_binary_publication(published_messages(topic), before))
                print("ntfy received one binary Web Push on the enrolled topic", file=sys.stderr, flush=True)
                notice = eventually(
                    "Android notification with this exact event",
                    lambda: app_notice(adb, event["event_id"]),
                )
            except AssertionError as failure:
                try:
                    state = delivery_state(db, adb, device_id, event["event_id"])
                    failure.add_note("Delivery state " + json.dumps(state, sort_keys=True))
                except Exception as diagnostic:
                    failure.add_note("Delivery state unavailable (" + type(diagnostic).__name__ + ")")
                raise
            tap_notice(ui, notice_title(adb, notice), owner, phrase, title, reply, args.evidence_dir)
            open_notifications_settings(ui, disable_push)
            ui.tap_until(
                disable_push,
                lambda root: ui.node(disable_push, root) is None or ui.node(s["push_settings_enable"], root) is not None,
                scroll=True,
                description="Push settings disabled after tapping " + disable_push,
            )
            eventually("server revocation", lambda: revoked_everywhere(db, device_id))
            _, second_run = trigger(primary, "Après révocation")
            revoked = eventually(
                "terminal event after revoke",
                lambda: completed_event(push, primary.key, second_run),
            )
            if (
                not revoked_everywhere(db, device_id, revoked["event_id"])
                or app_notice(adb, revoked["event_id"])
                or len(published_messages(topic)) != before + 1
            ):
                raise AssertionError("Revoked device received another message")
            print(
                json.dumps(
                    {
                        "hermes_revision": args.revision,
                        "apk_sha256": installed,
                        "sdk": adb.shell("getprop", "ro.build.version.sdk"),
                        "ntfy_server": SERVER.version,
                        "distributor": APK.version,
                        "absent_before_delivery": True,
                        "webpush_messages": 1,
                        "notification_event_matched": True,
                        "owning_profile_unlocked": True,
                        "owning_thread_opened": True,
                        "revoked_delivery_suppressed": True,
                    },
                    sort_keys=True,
                )
            )
        finally:
            cleanup_all(lambda: adb.shell("cmd", "statusbar", "collapse"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--apk", type=Path, required=True, help="Signed Android APK under test")
    parser.add_argument("--revision", default=PINNED_REVISION)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default=shutil.which("adb") or "adb")
    parser.add_argument("--adb-port", type=int, default=5037)
    parser.add_argument(
        "--push-python",
        default=sys.executable,
        help="Python with the dependencies from server/requirements.txt",
    )
    parser.add_argument("--cache", type=Path, default=Path.home() / ".cache/hermes-mobile/ntfy")
    parser.add_argument(
        "--tls-cert",
        type=Path,
        default=os.environ.get("HERMES_FIXTURE_TLS_CERT"),
        help="PEM certificate for 127.0.0.1",
    )
    parser.add_argument(
        "--tls-key",
        type=Path,
        default=os.environ.get("HERMES_FIXTURE_TLS_KEY"),
        help="PEM key for the account certificate",
    )
    parser.add_argument(
        "--tls-ca",
        type=Path,
        default=os.environ.get("HERMES_FIXTURE_TLS_CA"),
        help="PEM fixture CA trusted by the disposable Android phone",
    )
    parser.add_argument("--evidence-dir", type=Path, help="New directory for the generic shade labels and verified thread controls")
    args = parser.parse_args()
    tls = (args.tls_cert, args.tls_key, args.tls_ca)
    if any(tls) and not all(tls):
        parser.error("--tls-cert, --tls-key and --tls-ca must be supplied together")
    run(args)


if __name__ == "__main__":
    main()
