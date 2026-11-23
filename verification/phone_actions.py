"""Prove reviewed conversation proposals reach the system chooser and real Android editors.

Run from the mobile repository in the pinned gateway's Python environment:
    python -m verification.phone_actions --source /path/to/hermes-agent --serial emulator-5554 \
        --apk /path/to/app-debug.apk --output /tmp/phone-actions.json

Use a disposable, English API 36 userdebug emulator in airplane mode with Wi-Fi off.
Install the APK under test and the official calendar-25-foss-release.apk from
https://github.com/FossifyOrg/Calendar/releases/tag/1.12.2 (source 0a936ec).
Make Fossify the sole calendar insertion handler, and install Google Maps. Read-only
su/sqlite3 on this disposable guest checks Fossify's independent store. The gateway
uses adb reverse. Use --provider-url with a local OpenAI-compatible model, or
--response-file to replay a captured answer through a real Hermes conversation.
The output JSON and PNG crops contain only synthetic proposed fields. Never select
a sharesheet destination or save a calendar entry.
"""
import argparse
from contextlib import ExitStack
from datetime import datetime
from io import BytesIO
import hashlib
import json
from pathlib import Path
import re
import secrets
import shlex
import sqlite3
import sys
import tempfile
from urllib.parse import urlparse
from zoneinfo import ZoneInfo

from verification.device.installed import installed_apk_sha256
from verification.device.run import Emulator
from verification.gateway_fixture import Gateway, PINNED_REVISION, check_source
from verification.unifiedpush.editor import editor
from verification.unifiedpush.shade import bounds
from verification.unifiedpush.stack import eventually
from verification.unifiedpush.ui import APP, Screen, focused_window, launch, start_account


PROMPT = (
    'Je prépare un atelier de dessin à la Maison des arts de Laval le 12 avril 2027 de 14 h à '
    '15 h, heure de Laval (UTC−04:00). Peux-tu me préparer un rendez-vous à ouvrir dans mon '
    'agenda, un message à partager avec mon équipe et une recherche pour retrouver ce lieu sur '
    'la carte de mon téléphone ?'
)
CALENDAR = "org.fossify.calendar"
CALENDAR_APK = "2825816d0a972bd85eb289e556221293fadecfd3179a4a2531bde6f89c6f0238"
MAPS = "com.google.android.apps.maps"
ACTIONS = {
    "calendar_event": ("Vérifier l’événement", "Ouvrir l’agenda"),
    "share_text": ("Vérifier le partage", "Choisir une application"),
    "map_search": ("Vérifier le lieu", "Ouvrir la carte"),
}
REQUESTS = [
    {"kind": "share_text", "text": "Atelier de dessin le 12 avril à Laval."},
    {"kind": "calendar_event", "title": "Atelier de dessin", "start": "2027-04-12T14:00:00-04:00",
     "end": "2027-04-12T15:00:00-04:00", "location": "Maison des arts de Laval"},
    {"kind": "map_search", "query": "Maison des arts de Laval"},
]


def proposal():
    page = {"version": 1, "surface": "page/atelier-dessin", "mode": "replace", "title": "Atelier de dessin",
            "blocks": [{"type": "device", "request": action} for action in REQUESTS]}
    return "Voici les propositions à vérifier.\n\n```hermes-workspace\n" + json.dumps(page, ensure_ascii=False) + "\n```"


def admissions(gateway):
    db = Path(gateway.env["HERMES_HOME"]) / "state.db"
    if not db.is_file():
        return []
    conn = sqlite3.connect(db.as_uri() + "?mode=ro", uri=True)
    try:
        return conn.execute("SELECT request_id,target_session_id FROM session_admissions "
                            "WHERE principal_id='api' ORDER BY seq").fetchall()
    finally:
        conn.close()


def completed(gateway):
    rows = admissions(gateway)
    if not rows:
        return None
    if len(rows) != 1:
        raise AssertionError("Expected exactly one mobile-originated admission")
    run, session = rows[0]
    state = gateway.request("/v1/runs/" + run)
    if state["status"] in ("failed", "cancelled", "interrupted"):
        raise AssertionError("The Hermes run did not complete")
    if state["status"] == "completed":
        return run, session, state["output"]


def values(ui):
    return [s for node in ui.hierarchy().iter("node") for key in ("text", "content-desc") if (s := node.get(key))]


def focused(device):
    info = device.shell("dumpsys", "activity", "activities")
    hit = re.search(r"(?:topResumedActivity|mResumedActivity)\s*[:=].*? u\d+ ([\w.$]+/[\w.$]+)", info)
    return hit[1] if hit else ""


def visible(ui, expected):
    texts = values(ui)
    return all(any(item.casefold() in text.casefold() for text in texts) for item in expected)


def assert_offline(device):
    if "Active default network: none" not in device.shell("dumpsys", "connectivity"):
        raise ValueError("Use a dedicated emulator in airplane mode with Wi-Fi disabled")


def event_rows(device):
    result = device.shell("content", "query", "--uri", "content://com.android.calendar/events", "--projection", "_id")
    if result != "No result found." and not re.fullmatch(r"Row: \d+ _id=\d+(?:\nRow: \d+ _id=\d+)*", result):
        raise AssertionError("Shell could not verify the event count on the disposable Calendar provider")
    return result


def local_event_count(dev, *, missing_ok=False):
    # The pinned Fossify Calendar stores local events outside the Android Calendar provider.
    db = "/data/user/0/org.fossify.calendar/databases/events.db"
    query = f'if test -f {db}; then sqlite3 -readonly {db} "SELECT count(*) FROM events"; else echo missing; fi'
    result = dev.shell("su", "0", "sh", "-c", shlex.quote(query))
    if missing_ok and result == "missing":
        return 0
    if not result.isdecimal():
        raise AssertionError("Unable to inspect the disposable Fossify Calendar database")
    return int(result)


def capture_fields(ui, component, matches, path, full_screen):
    """Save only the proposed public fields from the foreground receiving application's pixels."""
    from PIL import Image
    package = component.split("/", 1)[0]
    def regions():
        if focused(ui.device) != component:
            return []
        result = []
        seen = set()
        tree = ui.hierarchy()
        for alternatives in matches:
            for node in tree.iter("node"):
                if node in seen or node.get("package") != package:
                    continue
                observed = next((node.get(key) for key in ("text", "content-desc")
                                 if any(value.casefold() in node.get(key, "").casefold() for value in alternatives)), None)
                if observed is not None:
                    box = bounds(node)
                    result.append((box, observed))
                    seen.add(node)
                    break
            else:
                return []
        return result

    def stable():
        rects = regions()
        if not rects:
            return False
        frame = ui.device.run("exec-out", "screencap", "-p")
        if regions() != rects:
            return False
        with Image.open(BytesIO(frame)) as raw:
            image = raw.convert("RGB")
            masked = Image.new("RGB", image.size)
            for box, _ in rects:
                x, y, right, bottom = box
                if not (0 <= x < right <= image.width and 0 <= y < bottom <= image.height):
                    return False
                masked.paste(image.crop(box), box)
            with path.open("xb") as handle:
                masked.save(handle, "PNG")
            if full_screen:
                with path.with_stem(path.stem + "-screen").open("xb") as handle:
                    image.save(handle, "PNG")
        return [label for _, label in rects]
    rendered = eventually("stable receiving application fields for " + package, stable)
    return rendered, hashlib.sha256(path.read_bytes()).hexdigest()


def calendar_fields(request, zone):
    fields = []
    for key in ("start", "end"):
        value = datetime.fromisoformat(request[key]).astimezone(ZoneInfo(zone))
        fields.append([value.strftime("%B %-d %Y")])
        fields.append([value.strftime("%I:%M %p"), value.strftime("%-I:%M %p"), value.strftime("%H:%M")])
    return fields


def action_labels(ui, kind):
    default_prepare, default_confirm = ACTIONS[kind]
    s = ui.strings
    return (
        s.get(f"device_action_{kind}_review", default_prepare),
        s.get(f"device_action_{kind}_open", default_confirm),
    )


def back_to_page(ui, account_name, phrase, label):
    s = ui.strings
    choose_profile = s["profile_choose_title"]
    unlock_btn = s["profile_unlock_action"]
    passphrase_label = s["profile_passphrase_label"]
    try_in_thread = s["instrument_try"]
    trying_status = s["instrument_trial"]
    if not focused(ui.device).startswith(APP + "/"):
        launch(ui.device, APP)
    root = eventually(
        "Hermes window after returning from receiver",
        lambda: (tree,) if (tree := ui.hierarchy()).get("package") == APP else None,
    )[0]
    if ui.node(choose_profile, root) is not None:
        ui.tap_until(
            account_name,
            lambda tree: ui.node(unlock_btn, tree) is not None,
            description="visible control " + unlock_btn,
        )
    if ui.node(unlock_btn) is not None:
        ui.field(passphrase_label, phrase)
        ui.tap_until(
            unlock_btn,
            lambda tree: ui.node(unlock_btn, tree) is None,
            description="the profile to finish unlocking",
        )
    if ui.node(label) is None and ui.node(trying_status) is None:
        ui.tap_until(
            try_in_thread,
            lambda tree: ui.node(trying_status, tree) is not None
            or ui.node(label, tree) is not None,
            scroll=True,
            description="trial workspace sheet",
        )
    ui.wait(label, scroll=True)


def open_review(ui, prepare, confirm, page_window, page_focus):
    cancel = ui.strings.get("device_review_cancel", "Annuler")

    def opened(root):
        if root.get("package") != APP:
            return None
        if ui.node(confirm, root) is None or ui.node(cancel, root) is None:
            return None
        if page_window and root.get("window-id") == page_window:
            return None
        current_focus = focused_window(ui.device)
        if current_focus is None or (page_focus is not None and current_focus == page_focus):
            return None
        return root.get("window-id"), current_focus

    return ui.tap_until(prepare, opened, description="visible control " + confirm)


def close_review(ui, prepare, confirm, dialog_window, dialog_focus):
    cancel = ui.strings.get("device_review_cancel", "Annuler")

    def closed(root):
        if not focused(ui.device).startswith(APP + "/"):
            raise AssertionError("Cancelling the proposal left the app")
        if dialog_window and root.get("window-id") == dialog_window:
            return False
        if ui.node(cancel, root) is not None or ui.node(confirm, root) is not None:
            return False
        if ui.node(prepare, root) is None:
            return False
        current_focus = focused_window(ui.device)
        return current_focus is not None and current_focus != dialog_focus

    ui.tap_until(cancel, closed, description="review dialog to close")


def confirm_review(ui, kind, confirm):
    cancel = ui.strings.get("device_review_cancel", "Annuler")
    unavailable = ui.strings.get("device_review_unavailable", "Application indisponible")

    def submitted(root):
        if not focused(ui.device).startswith(APP + "/"):
            return True
        if ui.node(unavailable, root) is not None:
            raise AssertionError("Hermes reported no available receiving application for " + kind)
        return ui.node(confirm, root) is None and ui.node(cancel, root) is None

    ui.tap_until(confirm, submitted, description="review dialog " + confirm + " to submit")


def leave_receiver(ui, package, kind):
    if ui.device.ime_shown():
        ui.device.shell("input", "keyevent", "BACK")
        eventually("the receiving keyboard to close", lambda:
                   not ui.device.ime_shown())
    if not focused(ui.device).startswith(package + "/"):
        return
    ui.device.shell("input", "keyevent", "BACK")
    if kind == "calendar_event":
        def discarded():
            if not focused(ui.device).startswith(package + "/"):
                return True
            node = ui.node("Discard")
            if node is not None and node.get("package") == package:
                ui.tap_node(node)
            return False
        eventually("the unsaved calendar draft to close", discarded)
    elif kind == "share_text":
        eventually("the native sharesheet to close", lambda: not focused(ui.device).startswith(package + "/"))


def review(ui, kind, request, output, full_screen):
    prepare, confirm = action_labels(ui, kind)
    ui.wait(prepare, scroll=True)
    page_root = ui.hierarchy()
    page_window, page_focus = page_root.get("window-id"), focused_window(ui.device)
    dialog_window, dialog_focus = open_review(ui, prepare, confirm, page_window, page_focus)
    expected = {"share_text": ["text"], "map_search": ["query"], "calendar_event": ["title", "location"]}[kind]
    detail = [request[field] for field in expected if request.get(field)]
    if not focused(ui.device).startswith(APP + "/") or not visible(ui, detail):
        raise AssertionError("The original proposal values were absent from the in-app review")
    close_review(ui, prepare, confirm, dialog_window, dialog_focus)
    page_root = ui.hierarchy()
    page_window, page_focus = page_root.get("window-id"), focused_window(ui.device)
    open_review(ui, prepare, confirm, page_window, page_focus)
    confirm_review(ui, kind, confirm)
    component = eventually("Android receiving activity", lambda: (c if (c := focused(ui.device)) and
                           not c.startswith(APP + "/") else None))
    if kind == "share_text":
        if "ChooserActivity" not in component or not component.startswith((
                "android/", "com.android.intentresolver/", "com.google.android.intentresolver/")):
            raise AssertionError("Android did not open its native sharesheet: " + component)
        package = component.split("/", 1)[0]
    else:
        package = CALENDAR if kind == "calendar_event" else MAPS
        if not component.startswith(package + "/"):
            raise AssertionError("Unexpected receiving application: " + component)
    def ready():
        active = focused(ui.device)
        if not active.startswith(package + "/"):
            return False
        if visible(ui, detail):
            return active
        if kind == "map_search":
            tree = ui.hierarchy()
            for label in ("Skip", "Continue without an account"):
                button = ui.node(label, tree)
                if button is not None and button.get("package") == MAPS:
                    ui.tap_node(button)
                    break
        return False
    component = eventually("visible proposal fields in " + package, ready, timeout=150)
    if kind == "calendar_event" and ui.device.ime_shown():
        ui.device.shell("input", "keyevent", "BACK")
        eventually("the calendar keyboard to close", lambda: not ui.device.ime_shown())
    matches = [[value] for value in detail]
    if kind == "calendar_event":
        matches.extend(calendar_fields(request, ui.device.shell("getprop", "persist.sys.timezone")))
    filename = output.with_name(output.stem + "-" + kind + ".png")
    rendered, digest = capture_fields(ui, component, matches, filename, full_screen)
    leave_receiver(ui, package, kind)
    proof = {"kind": kind, "receiver": component, "fields": detail, "rendered_labels": rendered,
             "image": filename.name, "image_sha256": digest}
    if full_screen:
        frame = filename.with_stem(filename.stem + "-screen")
        proof.update(screen=frame.name, screen_sha256=hashlib.sha256(frame.read_bytes()).hexdigest())
    return proof


def inbound_drafts(ui, account_name, phrase, session):
    """Drive the exported activity from Android's shell UID and leave the composer unsent."""
    s = ui.strings
    passphrase_label = s["profile_passphrase_label"]
    unlock_btn = s["profile_unlock_action"]
    send_btn = s["thread_send"]
    share_title = s.get("share_draft_title", "Ajouter au brouillon ?")
    share_confirm = s.get("share_draft_confirm", "Ajouter au brouillon")
    previous = ""
    results = []
    for kind, action, extra, value in (
            ("share", "SEND", "TEXT", "Note de préparation pour Laval."),
            ("selection", "PROCESS_TEXT", "PROCESS_TEXT", "dessin à Laval")):
        dispatch = ui.device.shell("am", "start", "-W", "-a", "android.intent.action." + action,
                                   "-t", "text/plain", "-p", APP, "--es", "android.intent.extra." + extra,
                                   shlex.quote(value), "--ez", "android.intent.extra.PROCESS_TEXT_READONLY", "true")
        if "Error" in dispatch:
            raise AssertionError("Android could not resolve the text entry")
        ui.tap_until(
            account_name,
            lambda root: ui.node(passphrase_label, root) is not None
            and ui.node(unlock_btn, root) is not None,
            description="profile unlock form",
        )
        ui.field(passphrase_label, phrase)
        ui.tap_until(
            unlock_btn,
            lambda root: ui.node(share_title, root) is not None,
            description="visible control " + share_title,
        )
        if not visible(ui, [account_name, session, value]):
            raise AssertionError("The share review did not identify the exact account, thread and source text")
        ui.tap_until(
            share_confirm,
            lambda root: ui.node(share_confirm, root) is None
            and ui.node(send_btn, root) is not None,
            description="visible control " + send_btn,
        )
        previous = previous + "\n" + value if previous else value
        ui.wait(previous)
        results.append({"kind": kind, "source_uid": "shell", "unsent_draft": previous})
    return results


def run(args):
    source = args.source.resolve()
    check_source(source, PINNED_REVISION)
    sys.path.insert(0, str(source))
    from tests.fakes.fake_llm_provider import FakeLLMServer
    dev = Emulator(args.serial, args.adb, args.adb_port)
    if dev.shell("getprop", "ro.kernel.qemu") != "1":
        raise ValueError("Expected a disposable emulator")
    assert_offline(dev)
    if dev.shell("id", "-u") != "2000":
        raise ValueError("Use the non-root adb shell for the external sender")
    if args.full_screens:
        counts = re.findall(r"Accounts: (\d+)", dev.shell("dumpsys", "account"))
        contacts = dev.shell("su", "0", "content", "query", "--uri", "content://com.android.contacts/contacts",
                             "--projection", "_id")
        if counts != ["0"] or contacts != "No result found.":
            raise ValueError("Full screenshots require an empty disposable guest with zero Android accounts and contacts")
    if installed_apk_sha256(dev, CALENDAR) != CALENDAR_APK:
        raise ValueError("Install the pinned Fossify Calendar release in the disposable emulator")
    package_hash = installed_apk_sha256(dev, APP)
    with args.apk.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != package_hash:
            raise ValueError("The installed APK differs from --apk")
    with tempfile.TemporaryDirectory(prefix="hermes-actions-") as folder, ExitStack() as stack:
        root = Path(folder)
        if args.provider_url:
            if urlparse(args.provider_url).hostname not in ("localhost", "127.0.0.1", "::1"):
                raise ValueError("Expected a local model endpoint")
            api = args.provider_url
        else:
            reply = args.response_file.read_text() if args.response_file else proposal()
            api = stack.enter_context(FakeLLMServer(default_text=reply)).base_url
        gateway = Gateway(root / "gateway", source, api)
        if args.provider_url:
            import hermes_yaml
            config = Path(gateway.env["HERMES_HOME"]) / "config.yaml"
            settings = hermes_yaml.safe_load(config.read_text())
            settings["model"].update(default=args.model, context_length=131072)
            settings["providers"]["custom"].update(request_timeout_seconds=780, stale_timeout_seconds=750)
            settings["platform_toolsets"]["api_server"] = []
            config.write_text(hermes_yaml.safe_dump(settings, allow_unicode=True))
        stack.enter_context(gateway)
        stack.enter_context(dev.reverse(gateway.endpoint))
        ui = Screen(dev, stack.enter_context(editor(dev, root)))
        phrase = secrets.token_hex(16)
        start = (event_rows(dev), local_event_count(dev, missing_ok=True))
        launch(dev, APP)
        account_name = "Actions" + secrets.token_hex(3)
        start_account(ui, account_name, gateway.endpoint, gateway.key, phrase)
        s = ui.strings
        composer_hint = s["thread_message_hint"]
        ui.tap_until(
            s["thread_new_conversation"],
            lambda root: ui.node(composer_hint, root) is not None,
            description="visible control " + composer_hint,
        )
        if ui.controls.send("text", label=composer_hint, value=args.prompt) != b"applied":
            raise AssertionError("Could not type the request in the public composer")
        ui.tap(s["thread_send"])
        run_id, session_id, reply = eventually("completed Hermes run", lambda: completed(gateway), timeout=800 if args.provider_url else 90)
        def canonical_exchange():
            rows = gateway.request(f"/api/sessions/{session_id}/messages?order=latest&limit=10")["data"]
            turns = [(r["role"], r["content"]) for r in rows if r["role"] in {"user", "assistant"}]
            if len(turns) < 2:
                return False
            if turns != [("user", args.prompt), ("assistant", reply)]:
                raise AssertionError(f"Unexpected synthetic user/assistant exchange: {turns!r}")
            return True
        eventually("the original canonical phone exchange", canonical_exchange)
        match = re.search(r"```hermes-workspace\s*\n(.*?)\n```", reply, re.S)
        if not match:
            raise AssertionError("Hermes returned no native action proposal")
        actions = [b["request"] for b in json.loads(match[1])["blocks"] if b["type"] == "device"]
        if not actions or (not args.provider_url and not args.response_file and actions != REQUESTS):
            raise AssertionError("Unexpected device actions in the canonical reply")
        receipt = {"gateway_revision": PINNED_REVISION, "apk_sha256": package_hash,
                   "api": dev.shell("getprop", "ro.build.version.sdk"),
                   "model": args.model if args.provider_url else ("recorded response" if args.response_file else "scripted"),
                   "run_id": run_id, "conversation": session_id, "user": args.prompt,
                   "reply_sha256": hashlib.sha256(reply.encode()).hexdigest()}
        with args.output.with_name(args.output.stem + "-conversation.json").open("x") as handle:
            json.dump(dict(receipt, assistant=reply), handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        ui.tap_until(
            s["instrument_try"],
            lambda root: ui.node(s["instrument_trial"], root) is not None,
            scroll=True,
            description="trial workspace sheet",
        )
        results = []
        for request in actions:
            kind = request["kind"]
            if results:
                back_to_page(ui, account_name, phrase, action_labels(ui, kind)[0])
            results.append(review(ui, kind, request, args.output, args.full_screens))
        inbound = inbound_drafts(ui, account_name, phrase, session_id) if args.inbound else []
        if (event_rows(dev), local_event_count(dev)) != start or admissions(gateway) != [(run_id, session_id)]:
            raise AssertionError("Android review created a calendar entry or an extra Hermes run")
        return dict(receipt, actions=results, inbound=inbound, calendar_writes=0, additional_runs=0,
                    local_event_count=start[1], android_event_count=start[0].count("Row: "))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--adb-port", default=5037, type=int)
    provider = parser.add_mutually_exclusive_group()
    provider.add_argument("--provider-url")
    provider.add_argument("--response-file", type=Path)
    parser.add_argument("--full-screens", action="store_true", help="Retain full public receiver screens on a verified empty guest")
    parser.add_argument("--inbound", action="store_true", help="Also stage Android SEND and PROCESS_TEXT from uid shell")
    parser.add_argument("--model", default="Qwen2.5-Coder-7B-Instruct-int4-ov")
    parser.add_argument("--prompt", default=PROMPT)
    args = parser.parse_args()
    report = run(args)
    with args.output.open("x") as output:
        json.dump(report, output, ensure_ascii=False, indent=2)
        output.write("\n")
    print(json.dumps(report, ensure_ascii=False), flush=True)
