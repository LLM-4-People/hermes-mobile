"""Test conversational controls with real Hermes gateways and deterministic model responses.

Run with the pinned fork's Python environment:
    python verification/conversation_cards.py --source /path/to/hermes-agent --output /tmp/cards-http
Add --serial emulator-5554 for Android and install the matching debug and androidTest APKs first.
The Android journey uses an instrumentation-only recognizer. Hardware speech quality is outside its scope.
"""

import argparse
from contextlib import ExitStack
import json
from pathlib import Path
import secrets
import sqlite3
import sys
import tempfile
import time

from gateway_fixture import Gateway, PINNED_REVISION, check_source


FILENAME = "partage-demo.txt"
COMMAND = "chmod 666 " + FILENAME
TURNS = [
    {"prompt": "Aide-moi à choisir le format de ma note.", "kind": "clarify",
     "question": "Quel format préférez-vous ?", "choices": ["Liste", "Paragraphe"],
     "answer": "Paragraphe", "reply": "Format retenu : Paragraphe."},
    {"prompt": "Aide-moi à nommer ma note.", "kind": "clarify",
     "question": "Quel titre souhaitez-vous ?", "answer": "Rendez-vous de lundi",
     "reply": "Titre retenu : Rendez-vous de lundi."},
    {"prompt": "Ajoute un sous-titre à ma note.", "kind": "clarify",
     "question": "Quel sous-titre souhaitez-vous ?", "answer": "Planning de la semaine",
     "voice": True, "reply": "Sous-titre retenu : Planning de la semaine."},
    {"prompt": "Prépare le partage de mon fichier de démonstration.", "kind": "approval",
     "answer": "deny", "reply": "Le partage a été refusé."},
    {"prompt": "Réessaie de partager mon fichier de démonstration.", "kind": "approval",
     "answer": "once", "reply": "Le fichier de démonstration est partagé."},
    {"prompt": "Vérifie si tu peux refaire ce partage.", "kind": "approval",
     "answer": "deny", "reply": "Cette nouvelle demande a été refusée."},
]


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def model_reply(record):
    from tests.fakes.fake_llm_provider import Text, ToolCall

    messages = record["body"]["messages"]
    prompt = next((m.get("content") for m in reversed(messages) if m["role"] == "user"), "")
    turn = next((t for t in TURNS if t["prompt"] == prompt), None)
    if not turn:
        return Text("Notes de démonstration")
    last = messages[-1]
    if last["role"] == "user":
        if turn["kind"] == "approval":
            return ToolCall("terminal", {"command": COMMAND})
        question = {key: turn[key] for key in ("question", "choices") if key in turn}
        return ToolCall("clarify", {"questions": [question]})
    if last["role"] == "tool":
        result = json.loads(last["content"])
        if turn["kind"] == "clarify":
            answers = result.get("responses", [])
            if (result.get("outcome") == "submitted" and len(answers) == 1
                    and answers[0].get("status") == "answered"
                    and answers[0].get("user_response") == turn["answer"]):
                return Text(turn["reply"])
        elif turn["answer"] == "once" and result.get("exit_code") == 0:
            return Text(turn["reply"])
        elif turn["answer"] == "deny" and "denied" in str(result).lower():
            return Text(turn["reply"])
    return Text("Le résultat attendu de l’outil est absent.")


def wait(probe, what, check=None):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        if check:
            check()
        value = probe()
        if value:
            return value
        time.sleep(0.1)
    raise TimeoutError("Timed out waiting for " + what)


def admission(gateway, number):
    db = Path(gateway.env["HERMES_HOME"]) / "state.db"
    if not db.exists():
        return None
    with sqlite3.connect(db.as_uri() + "?mode=ro", uri=True) as conn:
        row = conn.execute(
            "SELECT request_id,target_session_id FROM session_admissions WHERE principal_id='api' "
            "ORDER BY seq LIMIT 1 OFFSET ?", (number,),
        ).fetchone()
    return row


def pending(gateway, path):
    state = gateway.request(path)
    if state["status"] in {"completed", "failed", "cancelled"}:
        raise AssertionError(f"Run finished before presenting its control: {state}")
    controls = state.get("pending_controls", [])
    return controls[0] if len(controls) == 1 else None


def configure(gateway):
    import hermes_yaml as yaml

    path = Path(gateway.env["HERMES_HOME"]) / "config.yaml"
    config = yaml.safe_load(path.read_text())
    config["platform_toolsets"]["api_server"] = ["clarify", "terminal"]
    config["approvals"] = {"mode": "manual"}
    path.write_text(yaml.safe_dump(config, allow_unicode=True))
    marker = gateway.workspace / FILENAME
    marker.write_text("Exemple de partage isolé.\n")
    marker.chmod(0o600)
    return marker


def run(primary, secondary, revision, phone=None):
    session = None
    if not phone:
        session = primary.request("/api/sessions", method="POST", body={"title": "Notes"}, expected=201)["session"]["id"]
    receipts = []
    expected_mode = 0o600
    for i, turn in enumerate(TURNS):
        if not phone:
            admitted = primary.request("/v1/runs", method="POST", expected=202,
                headers={"Idempotency-Key": secrets.token_urlsafe(24)},
                body={"session_id": session, "input": turn["prompt"]})
        run_id, thread = wait(lambda: admission(primary, i), "canonical admission", phone.check if phone else None)
        if not session:
            session = thread
        require(thread == session, "A control moved into a different conversation")
        if not phone:
            require(run_id == admitted["run_id"], "Ledger and admission disagree")
        path = "/v1/runs/" + run_id
        control = wait(lambda: pending(primary, path), "pending canonical prompt", phone.check if phone else None)
        kind = turn["kind"]
        require(control["kind"] == kind, "Unexpected prompt kind")
        if kind == "clarify":
            require(control["question"].splitlines()[0] == turn["question"], "Unexpected question")
            if "choices" in turn:
                require(len(control["choices"]) == 2 and control["choices"][1] == turn["answer"], "Unexpected options")
        else:
            require(control["command"] == COMMAND, "Unexpected approval action")
        body = {"request_id": control["prompt_id"], "execution_generation": control["execution_generation"],
                ("choice" if kind == "approval" else "answer"): turn["answer"]}
        endpoint = path + "/" + kind
        if i == 0:
            primary.request(endpoint, method="POST", body=body,
                            headers={"Authorization": "Bearer " + secondary.key}, expected=401)
        secondary.request(endpoint, method="POST", body=body, expected=404)
        refusal = primary.request(endpoint, method="POST", body={**body, "execution_generation": body["execution_generation"] + 1}, expected=409)
        require(refusal["error"]["code"] == "stale_generation", "Unexpected stale-generation response")
        require(pending(primary, path)["prompt_id"] == control["prompt_id"], "Rejected response settled the prompt")
        if phone:
            phone.permit(i)
            if turn.get("voice"):
                wait(phone.voice_filled, "dictated editable answer", phone.check)
                require(pending(primary, path)["prompt_id"] == control["prompt_id"],
                        "Inserting a dictated answer submitted it automatically")
                phone.submit_voice()
        else:
            primary.request(endpoint, method="POST", body=body)
        def completed():
            state = primary.request(path)
            require(state["status"] not in {"failed", "cancelled"}, f"Unsuccessful run: {state}")
            return state if state["status"] == "completed" else None
        final = wait(completed, f"agent continuation after {kind} on turn {i + 1}", phone.check if phone else None)
        require(final["output"] == turn["reply"], f"Unexpected model continuation: {final['output']}")
        events = primary.events(run_id)
        controls = [(e["event"], e.get("prompt_id"), e.get("execution_generation")) for e in events
                    if e["event"] in {kind + ".request", kind + ".settled"}]
        require(controls == [(kind + suffix, control["prompt_id"], body["execution_generation"])
                             for suffix in (".request", ".settled")], "Missing or duplicated control events")
        primary.request(endpoint, method="POST", body=body, expected=409)
        mode = (primary.workspace / FILENAME).stat().st_mode & 0o777
        if turn["answer"] == "once":
            expected_mode = 0o666
        require(mode == expected_mode, "Filesystem does not reflect the confirmed decision")
        receipts.append({"kind": kind, "run_id": run_id, "prompt_id": control["prompt_id"],
                         "execution_generation": body["execution_generation"], "decision": turn["answer"]})
    require(len({item["prompt_id"] for item in receipts}) == len(TURNS), "A settled ID was reused")
    require(admission(primary, len(TURNS)) is None, "A duplicate response admitted another run")
    rows = primary.request(f"/api/sessions/{session}/messages?order=latest&include_compacted=true&limit=100")["data"]
    archived = [json.loads(row["content"]) for row in rows
                if row["role"] == "tool" and row.get("tool_name") == "clarify"]
    pairs = [(entry["question"], entry["user_response"]) for receipt in archived
             if receipt.get("outcome") == "submitted" for entry in receipt["responses"]
             if entry.get("status") == "answered"]
    require(pairs == [(t["question"], t["answer"]) for t in TURNS if t["kind"] == "clarify"],
            "Canonical transcript lost a submitted question or its answer")
    require([r["content"] for r in rows if r["role"] == "user"] == [t["prompt"] for t in TURNS],
            "The thread contains an unexpected user message")
    require(all(any(r["role"] == "assistant" and r["content"] == t["reply"] for r in rows) for t in TURNS),
            "Continuation missing from stored transcript")
    if phone:
        phone.finish()
    return {
        "mode": "android" if phone else "http",
        "gateway_revision": revision,
        "authority": "canonical",
        "turns": receipts,
        "voice_input": "synthetic_recognizer_result" if phone else "http_text",
        "hardware_microphone_tested": False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--revision", default=PINNED_REVISION)
    parser.add_argument("--output", type=Path, required=True, help="New evidence directory")
    parser.add_argument("--serial", help="Dedicated emulator with matching app and test APKs installed")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--adb-port", type=int, default=5037)
    parser.add_argument("--verify-apks", nargs=2, type=Path, metavar=("APP_APK", "TEST_APK"),
                        help="Match the installed app and instrumentation APKs against these files")
    args = parser.parse_args()
    if args.verify_apks and not args.serial:
        parser.error("--verify-apks requires --serial")
    source = args.source.resolve()
    check_source(source, args.revision)
    sys.path.insert(0, str(source))
    from tests.fakes.fake_llm_provider import FakeLLMServer

    args.output.mkdir(parents=True, exist_ok=False)
    with ExitStack() as stack:
        root = Path(stack.enter_context(tempfile.TemporaryDirectory(prefix="hm-cards-")))
        provider = stack.enter_context(FakeLLMServer(model_reply))
        primary = Gateway(root / "primary", source, provider.base_url)
        configure(primary)
        stack.enter_context(primary)
        secondary = stack.enter_context(Gateway(root / "secondary", source, provider.base_url))
        phone = None
        if args.serial:
            from device.card_journey import CardPhone
            phone = stack.enter_context(CardPhone(args, primary, TURNS))
        report = run(primary, secondary, args.revision, phone)
    if phone:
        report.update(phone.evidence())
    (args.output / "conversation-cards.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps(report, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
