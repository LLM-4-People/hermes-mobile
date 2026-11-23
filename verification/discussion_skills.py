"""Verify skill discovery, draft preparation and explicit submission using the packaged app and real gateway."""
import argparse
from contextlib import ExitStack
import json
from pathlib import Path
import sys
import tempfile

from conversation_cards import admission
from device.discussion import discussion_device, require_captures
from gateway_fixture import Gateway, PINNED_REVISION, check_source
from live_api import await_completion, transcript

LOCATOR = "qualite/synthese"
SUMMARY = "Relecture des références et de la conclusion."
DRAFT = "Vérifie mon compte rendu."
REQUEST = DRAFT + "\nUtilise le savoir-faire « " + LOCATOR + " » pour cette demande."
MARKER = "Les étapes sont classées du constat vers la recommandation."
ANSWER = "La fiche de relecture est ouverte."
CAPTURES = ("01-competence-recherche.png", "02-competence-resume.png",
            "03-competence-brouillon.png", "04-competence-reponse.png")


def seed(gateway):
    import hermes_yaml as yaml
    home = Path(gateway.env["HERMES_HOME"])
    cfg = home / "config.yaml"
    config = yaml.safe_load(cfg.read_text())
    config["platform_toolsets"]["api_server"] = ["skills"]
    cfg.write_text(yaml.safe_dump(config))
    for path, summary, body in ((LOCATOR, SUMMARY, MARKER),
                                ("support/synthese", "Tri des demandes de support.", "Autre fiche.")):
        folder = home / "skills" / path
        folder.mkdir(parents=True)
        (folder / "SKILL.md").write_text(f"---\nname: synthese\ndescription: {summary}\n---\n{body}\n")


def model_reply(record):
    from tests.fakes.fake_llm_provider import Text, ToolCall
    messages = record["body"]["messages"]
    if next((m["content"] for m in reversed(messages) if m["role"] == "user"), None) != REQUEST:
        return Text("Demande de démonstration différente.")
    if messages[-1]["role"] == "user":
        return ToolCall("skill_view", {"name": LOCATOR})
    if messages[-1]["role"] == "tool":
        result = json.loads(messages[-1]["content"])
        if result.get("success") and MARKER in result.get("content", ""):
            return Text(ANSWER)
    return Text("La fiche demandée n’a pas été chargée.")


def verify_catalog(gateway):
    gateway.request("/v1/skills", bearer=False, expected=401)
    catalog = gateway.request("/v1/skills")
    if catalog.get("object") != "list":
        raise AssertionError("The authenticated skills endpoint did not return a list")
    rows = {r["name"]: r for r in catalog["data"]}
    target = rows.get(LOCATOR)
    if (not isinstance(target, dict)
            or {k: target.get(k) for k in ("name", "description", "category")}
            != {"name": LOCATOR, "description": SUMMARY, "category": "qualite"}
            or "support/synthese" not in rows or "synthese" in rows):
        raise AssertionError("The gateway did not offer the exact loadable skill locators")


def verify_sent(gateway, provider):
    only = admission(gateway, 0)
    if only is None or admission(gateway, 1) is not None:
        raise AssertionError("Expected exactly one admitted discussion run")
    run, sid = only
    if await_completion(gateway, run).get("output") != ANSWER:
        raise AssertionError("The explicit send did not load the selected skill")
    turns = transcript(gateway, sid)
    if [t for role, t in turns if role == "user"] != [REQUEST] or ("assistant", ANSWER) not in turns:
        raise AssertionError("The owning discussion did not preserve the exact staged request and answer")
    if len(provider.main_requests()) != 2:
        raise AssertionError("Expected one model tool call and its final response")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--revision", default=PINNED_REVISION)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--adb-port", type=int, default=5037)
    parser.add_argument("--verify-apks", type=Path, nargs=2, metavar=("APP_APK", "TEST_APK"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    source = args.source.resolve()
    check_source(source, args.revision)
    sys.path.insert(0, str(source))
    from tests.fakes.fake_llm_provider import FakeLLMServer

    args.output.mkdir(parents=True, exist_ok=True)
    if any(args.output.iterdir()):
        raise ValueError("Use an empty output directory for this run")
    with ExitStack() as stack:
        root = Path(stack.enter_context(tempfile.TemporaryDirectory(prefix="hm-skills-")))
        provider = stack.enter_context(FakeLLMServer(model_reply))
        gateway = Gateway(root, source, provider.base_url)
        seed(gateway)
        stack.enter_context(gateway)
        verify_catalog(gateway)
        with discussion_device(args, gateway, skill=LOCATOR, summary=SUMMARY, marker=MARKER, draft=DRAFT,
                               staged=REQUEST, answer=ANSWER) as (device, receipt):
            device.phase("skills-draft")
            if admission(gateway, 0) is not None or provider.main_requests():
                raise AssertionError("Browsing or preparing the draft already submitted a request")
            device.phase("skills-send")
        verify_sent(gateway, provider)
        receipt.update(gateway_revision=args.revision, selected_skill=LOCATOR, admissions_before_send=0,
                       completed_runs=1, skill_view_calls=1, result_reader_verified=True,
                       captures_sha256=require_captures(args.output, CAPTURES))
        (args.output / "skills-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
        print(json.dumps(receipt), flush=True)


if __name__ == "__main__":
    main()
