"""Run the mobile contract against a pinned Hermes gateway and its scripted provider."""

import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time

from gateway_fixture import Gateway, PINNED_REVISION, check_source


CHAT_REPLY = "La conversation est prête."
CODE_PROMPT = "Écris carre.py pour calculer un carré en Python."
CODE_CONTENT = "def carre(n):\n    return n * n\n"
CODE_REPLY = f"Voici la fonction demandée.\n\n```python\n{CODE_CONTENT}```"


LAYOUT = {
    "version": 1,
    "surface": "home",
    "title": "Atelier de vérification",
    "blocks": [
        {"title": "Raccourcis de vérification", "actions": ["threads"]},
        {"type": "content"},
    ],
}


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def transcript(gateway, session_id):
    page = gateway.request(f"/api/sessions/{session_id}/messages?order=latest&limit=10")
    return [(row["role"], row["content"]) for row in page["data"]]


def await_completion(gateway, run_id):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        state = gateway.request(f"/v1/runs/{run_id}")
        if state["status"] in {"failed", "cancelled", "completed"}:
            check(
                state["status"] == "completed", f"Run completed unsuccessfully: {state}"
            )
            return state
        time.sleep(0.1)
    raise TimeoutError("Run did not reach a terminal state")


def send(gateway, prompt, title, answer, *, options, expected_output=20):
    session = gateway.request(
        "/api/sessions", method="POST", body={"title": title}, expected=201
    )["session"]
    session_id = session["id"]
    body = {
        "session_id": session_id,
        "input": prompt,
        "model": options["model"],
        "provider": options["provider"],
    }
    headers = {"Idempotency-Key": secrets.token_urlsafe(24)}
    accepted = gateway.request(
        "/v1/runs", method="POST", body=body, headers=headers, expected=202
    )
    run_id = accepted["run_id"]
    events = gateway.events(run_id)
    check(
        events and events[-1]["event"] == "run.completed",
        f"Missing terminal run event: {events}",
    )
    seqs = [event["seq"] for event in events if "seq" in event]
    check(seqs and seqs == sorted(set(seqs)), f"Event ordering: {seqs}")
    replay = gateway.events(run_id, last_seq=seqs[0])
    check(
        [e["seq"] for e in replay] == seqs[1:], f"Inconsistent event replay: {replay}"
    )
    state = await_completion(gateway, run_id)
    check(state.get("output") == answer, f"Unexpected assistant output: {state}")
    check(
        state["usage"]["output_tokens"] == expected_output,
        f"Run usage differs from provider: {state['usage']}",
    )
    messages = transcript(gateway, session_id)
    check(
        ("user", prompt) in messages and ("assistant", answer) in messages,
        f"Transcript: {messages}",
    )
    if prompt == CODE_PROMPT:
        check(
            any(role == "tool" for role, content in messages),
            "Code tool result missing from transcript",
        )
    info = gateway.request(f"/api/sessions/{session_id}")["session"]
    check(
        info["output_tokens"] == expected_output and info["cache_read_tokens"] == 10,
        f"Session accounting: {info}",
    )
    return session_id, run_id, body, headers


def check_reminder(gateway):
    job = gateway.request(
        "/api/jobs",
        method="POST",
        body={
            "name": "Relire le code",
            "schedule": "in 2d",
            "prompt": "Relire carre.py",
            "deliver": "local",
        },
    )["job"]
    path = f"/api/jobs/{job['id']}"
    check(
        gateway.request(path + "/pause", method="POST")["job"]["enabled"] is False,
        "Pause ignored",
    )
    check(
        gateway.request(path, method="PATCH", body={"name": "Relire la fonction"})[
            "job"
        ]["name"]
        == "Relire la fonction",
        "Reminder rename ignored",
    )
    check(
        gateway.request(path + "/resume", method="POST")["job"]["enabled"] is True,
        "Resume ignored",
    )
    check(gateway.request(path, method="DELETE")["ok"], "Removal ignored")
    gateway.request(path, expected=404)


def verify(gateway, provider):
    gateway.request("/api/sessions", bearer=False, expected=401)
    caps = gateway.request("/v1/capabilities")
    check(
        caps["features"]["runs_idempotency"]["durable"], "Expected a durable run ledger"
    )
    options = gateway.request("/api/model/options?include_unconfigured=false")
    check(options["model"] == "fake-model", f"Model inventory: {options}")
    check(
        any(row.get("models") for row in options["providers"]),
        "No provider with models",
    )
    chat = send(
        gateway,
        "Bonjour Hermes",
        "Conversation de vérification",
        CHAT_REPLY,
        options=options,
    )
    code = send(
        gateway,
        CODE_PROMPT,
        "Exemple Python",
        CODE_REPLY,
        options=options,
        expected_output=30,
    )
    check(
        (gateway.workspace / "carre.py").read_text() == CODE_CONTENT,
        "Coding tool did not write the source",
    )
    check(chat[0] != code[0], "Two sessions received the same ID")
    listed = gateway.request("/api/sessions?source=api_server&limit=10")
    check(
        {s["id"] for s in listed["data"]} == {chat[0], code[0]},
        f"Thread listing: {listed}",
    )
    check_reminder(gateway)
    original_count = len(provider.main_requests())
    check(original_count == 3, f"Expected three provider calls, saw {original_count}")
    gateway.stop()
    gateway.start()
    for session_id, run_id, body, headers in (chat, code):
        state = await_completion(gateway, run_id)
        check(
            ("assistant", state["output"]) in transcript(gateway, session_id),
            "Transcript lost after restart",
        )
        replay = gateway.request(
            "/v1/runs", method="POST", body=body, headers=headers, expected=202
        )
        check(
            replay.get("replayed") and replay["run_id"] == run_id,
            "Exact retry created a different run",
        )
        gateway.request(
            "/v1/runs",
            method="POST",
            body={**body, "input": "Autre demande"},
            headers=headers,
            expected=409,
        )
    check(
        len(provider.main_requests()) == original_count, "Replay invoked provider again"
    )
    return {
        "authenticated": True,
        "unauthenticated_status": 401,
        "model": options["model"],
        "provider": options["provider"],
        "reminder_crud": True,
        "threads": 2,
        "provider_calls": original_count,
        "restart_replays": 2,
        "idempotency_conflict_status": 409,
        "cache_read_tokens_per_turn": 10,
        "output_tokens_by_session": [20, 30],
        "coding_tool": "write_file",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source",
        required=True,
        type=Path,
        help="Clean checkout of LLM-4-People/hermes-agent",
    )
    parser.add_argument(
        "--revision", default=PINNED_REVISION, help="Full Git commit of the checkout"
    )
    parser.add_argument(
        "--device-command",
        nargs=argparse.REMAINDER,
        help="Optional device check (command argv) with the fixture still available",
    )
    args = parser.parse_args()
    if args.device_command == []:
        parser.error("--device-command requires a command")
    source = args.source.resolve()
    check_source(source, args.revision)
    sys.path.insert(0, str(source))
    from tests.fakes.fake_llm_provider import FakeLLMServer, Text, ToolCall

    def response(record):
        messages = record["body"]["messages"]
        prompt = next(
            (m["content"] for m in reversed(messages) if m["role"] == "user"), ""
        )
        if (
            str(prompt) == CODE_PROMPT
            and messages[-1]["role"] == "user"
            and any(
                t.get("function", {}).get("name") == "write_file"
                for t in record["body"].get("tools", [])
            )
        ):
            return ToolCall("write_file", {"path": "carre.py", "content": CODE_CONTENT})
        if "Disposition de vérification" in str(prompt):
            reply = (
                "```hermes-workspace\n"
                + json.dumps(LAYOUT, ensure_ascii=False)
                + "\n```"
            )
        else:
            reply = CODE_REPLY if "Python" in str(prompt) else CHAT_REPLY
        return Text(reply, cached_tokens=10)

    with (
        tempfile.TemporaryDirectory(prefix="hm-api-") as root,
        FakeLLMServer(response) as provider,
    ):
        with Gateway(Path(root) / "primary", source, provider.base_url) as primary:
            report = verify(primary, provider)
            with Gateway(
                Path(root) / "secondary", source, provider.base_url
            ) as secondary:
                secondary.request(
                    "/api/sessions",
                    headers={"Authorization": f"Bearer {primary.key}"},
                    expected=401,
                )
                primary.request(
                    "/api/sessions",
                    headers={"Authorization": f"Bearer {secondary.key}"},
                    expected=401,
                )
                check(
                    secondary.request("/api/sessions")["data"] == [],
                    "Secondary runtime inherited primary history",
                )
                if args.device_command:
                    subprocess.run(
                        args.device_command,
                        check=True,
                        env={
                            **os.environ,
                            "HERMES_TEST_ENDPOINT": primary.endpoint,
                            "HERMES_TEST_BEARER": primary.key,
                            "HERMES_TEST_SECOND_ENDPOINT": secondary.endpoint,
                            "HERMES_TEST_SECOND_BEARER": secondary.key,
                        },
                    )
            print(
                json.dumps(
                    {"revision": args.revision, "independent_runtimes": 2, **report},
                    sort_keys=True,
                )
            )


if __name__ == "__main__":
    main()
