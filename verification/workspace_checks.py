"""Exercise the agent-proposed native page and tool-selection reply paths used by the Android journey."""

import json
import secrets


PROPOSE = "Vérifie mes écrans mobiles."
TOOL_QUESTION = "Quels outils de vérification sont disponibles ?"
AVAILABLE = "Écriture disponible."
EXCLUDED = "Écriture désactivée."

# A deterministic model reply drives the Android proposal preview, explicit apply and native editor.
DEVICE_WORKSPACES = [
    {
        "version": 1,
        "mode": "replace",
        "surface": "page/diagnostic",
        "title": "Diagnostic mobile",
        "blocks": [
            {"type": "value", "title": "Profil actif", "source": "profile.label"},
            {
                "type": "form",
                "title": "Relecture",
                "prompt": "Relire ce fichier.",
                "fields": [{"id": "fichier", "kind": "text", "label": "Fichier"}],
            },
        ],
    },
    {
        "version": 1,
        "surface": "thread",
        "title": "Écriture ciblée",
        "blocks": [{"type": "content", "slots": ["composer", "notice"]}],
    },
]


def tool_names(tools):
    return {tool["function"]["name"] for tool in tools}


def workspace_reply(prompt, tools):
    if prompt == PROPOSE:
        return "Voici les deux dispositions demandées.\n\n" + "\n\n".join(
            "```hermes-workspace\n" + json.dumps(layout, ensure_ascii=False) + "\n```"
            for layout in DEVICE_WORKSPACES
        )
    if prompt == TOOL_QUESTION:
        return AVAILABLE if "write_file" in tool_names(tools) else EXCLUDED
    return None


def verify_workspace_turns(
    primary, secondary, provider, await_completion, transcript, chat_reply, *, controls
):
    session = primary.request(
        "/api/sessions",
        method="POST",
        body={"title": "Propositions mobiles"},
        expected=201,
    )["session"]["id"]
    staged = "Garder la trace.\nRelire ce fichier.\n\nFichier\nsample.py"
    cases = [
        (PROPOSE, workspace_reply(PROPOSE, []), {}),
        (staged, chat_reply, {}),
    ]
    if controls:
        cases.extend(
            [
                (
                    TOOL_QUESTION,
                    EXCLUDED,
                    {"allowed_toolsets": ["file"], "excluded_tools": ["write_file"]},
                ),
                (
                    TOOL_QUESTION,
                    AVAILABLE,
                    {"allowed_toolsets": ["file"], "excluded_tools": []},
                ),
            ]
        )
    expected_history = []
    for prompt, reply, options in cases:
        before = len(provider.main_requests())
        body = {"session_id": session, "input": prompt, **options}
        receipt = primary.request(
            "/v1/runs",
            method="POST",
            body=body,
            expected=202,
            headers={"Idempotency-Key": secrets.token_urlsafe(24)},
        )
        run = receipt["run_id"]
        result = await_completion(primary, run)
        if result.get("output") != reply:
            raise AssertionError(
                f"Unexpected reply for {prompt!r}: {result.get('output')!r}"
            )
        calls = provider.main_requests()[before:]
        if len(calls) != 1:
            raise AssertionError(
                f"Expected one primary provider call on this turn, got {len(calls)}"
            )
        if options:
            names = tool_names(calls[0].get("tools", []))
            if "read_file" not in names or ("write_file" in names) != (
                reply == AVAILABLE
            ):
                raise AssertionError(
                    "Provider-received tools differ from the reply and selected tool policy"
                )
        expected_history.extend([("user", prompt), ("assistant", reply)])
        history = [
            (role, content)
            for role, content in transcript(primary, session, limit=100)
            if role in ("user", "assistant")
        ]
        if history != expected_history:
            summary = [(role, str(content)[:64]) for role, content in history]
            raise AssertionError(f"Workspace transcript after {prompt!r}: {summary!r}")
        secondary.request(f"/v1/runs/{run}", expected=404)

    secondary.request(f"/api/sessions/{session}/messages", expected=404)
    if secondary.request("/api/sessions")["data"]:
        raise AssertionError("Private workspace thread crossed gateway accounts")
    return {
        "workspace_proposal_surfaces": [
            layout["surface"] for layout in DEVICE_WORKSPACES
        ],
        "workspace_turns": len(cases),
        "workspace_tool_replies": [EXCLUDED, AVAILABLE] if controls else [],
        "workspace_other_account_status": 404,
        "workspace_staged_input_persisted": True,
    }
