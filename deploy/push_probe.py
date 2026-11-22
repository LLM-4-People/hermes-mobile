"""Exercise the push API using disposable credentials and real cron ledger records."""
import base64
import json
from uuid import uuid4

BASE = "/api/mobile/push"


def prepare(root, accounts):
    for name in accounts:
        directory = root / "accounts" / name
        env = [
            f"MOBILE_UNIFIEDPUSH_ORIGIN=https://{name}.example.invalid",
            "MOBILE_VAPID_SUBJECT=mailto:operator@example.invalid",
        ]
        if name == "north":
            # The smoke registers zero devices. No token refresh or provider send is needed.
            adc = {"type": "authorized_user", "client_id": "fixture.invalid",
                   "client_secret": "fixture", "refresh_token": "fixture"}
            (directory / "firebase.json").write_text(json.dumps(adc))
            # Docker's unprivileged push UID reads the file through its private file mount.
            (directory / "firebase.json").chmod(0o644)
            env.extend([f"MOBILE_FIREBASE_PROJECT_ID=fixture-{name}", f"GOOGLE_CLOUD_PROJECT=fixture-{name}",
                        "MOBILE_FIREBASE_SENDER_ID=100000000000",
                        "MOBILE_FIREBASE_APP_ID=1:100000000000:android:abcd", "MOBILE_FIREBASE_API_KEY=fixture"])
        (directory / "push.env").write_text("\n".join(env) + "\n")


def discovery(probe, name):
    status, body = probe.request(name, probe.keys[name], "GET", BASE)
    return json.loads(body) if status == 200 else {}


def verify(probe, wait_for):
    public_keys = {}
    for name, key in probe.keys.items():
        wait_for(lambda: discovery(probe, name), 60)
        config = discovery(probe, name)
        expected = {"unifiedpush", "fcm"} if name == "north" else {"unifiedpush"}
        probe.check(set(config["providers"]) == expected, f"{name} offers only its configured transports")
        if "fcm" in expected:
            probe.check(config["firebase"]["projectId"] == "fixture-" + name, f"{name} offers its own Firebase project")
        else:
            probe.check("firebase" not in config, f"{name} operates without Firebase credentials")
        public_keys[name] = config["unifiedpush"]["vapidPublicKey"]
        point = base64.urlsafe_b64decode(public_keys[name] + "==")
        probe.check(len(point) == 65 and point[0] == 4, f"{name} supplied a P-256 VAPID public key")
        other = next(other for other in probe.keys if other != name)
        status, _ = probe.request(name, probe.keys[other], "GET", BASE)
        probe.check(status == 401, f"{name}'s push discovery rejected the other account's key")
        job = "check-" + uuid4().hex
        probe.command("exec", "-T", "-u", "hermes", "gateway-" + name, "/opt/hermes/.venv/bin/python", "-c",
                      "from cron import executions as e; import sys; "
                      "i=e.create_execution(sys.argv[1], source='smoke')['id']; "
                      "e.mark_execution_running(i); e.finish_execution(i, success=True)", job)

        def events(account):
            status, body = probe.request(account, probe.keys[account], "GET", BASE + "/events")
            return [row["job_id"] for row in json.loads(body)["events"]] if status == 200 else []

        def reminder_received():
            return job in events(name)

        wait_for(reminder_received, 40)
        probe.check(job not in events(other), f"{name}'s reminder was reconciled only by its own push bridge")
    probe.check(len(set(public_keys.values())) == len(public_keys), "the two push bridges keep separate VAPID identities")
    probe.command("restart", *("push-" + name for name in probe.keys))
    for name, public_key in public_keys.items():
        wait_for(lambda: discovery(probe, name), 60)
        probe.check(discovery(probe, name)["unifiedpush"]["vapidPublicKey"] == public_key,
                    f"{name} retained its distributor subscription key across a restart")
