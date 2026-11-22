"""Exercise the push API using disposable credentials and real cron ledger records."""
import json
from uuid import uuid4

BASE = "/api/mobile/push"


def prepare(root, accounts):
    for name in accounts:
        directory = root / "accounts" / name
        # The smoke registers zero devices. No token refresh or provider send is needed.
        adc = {"type": "authorized_user", "client_id": "fixture.invalid",
               "client_secret": "fixture", "refresh_token": "fixture"}
        (directory / "firebase.json").write_text(json.dumps(adc))
        (directory / "push.env").write_text(
            f"MOBILE_FIREBASE_PROJECT_ID=fixture-{name}\nGOOGLE_CLOUD_PROJECT=fixture-{name}\n"
            "MOBILE_FIREBASE_SENDER_ID=100000000000\n"
            "MOBILE_FIREBASE_APP_ID=1:100000000000:android:abcd\nMOBILE_FIREBASE_API_KEY=fixture\n")
        # Docker's unprivileged push UID reads the file through its private file mount.
        (directory / "firebase.json").chmod(0o644)


def verify(probe, wait_for):
    for name, key in probe.keys.items():
        def push_ready():
            status, body = probe.request(name, key, "GET", BASE)
            return status == 200 and json.loads(body)["firebase"]["projectId"] == "fixture-" + name

        wait_for(push_ready, 60)
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
