"""Verify the exact requests received by two independent loopback provider endpoints."""

import secrets


ALTERNATE_PROVIDER = "atelier-local"
ALTERNATE_MODEL = "atelier-modele"
ALTERNATE_REPLY = "Réponse du fournisseur Atelier."
ALTERNATE_PROMPT = "Quel fournisseur de vérification répond ?"


def configured_provider(server, key):
    return {
        ALTERNATE_PROVIDER: {
            "api": server.base_url,
            "api_key": key,
            "discover_models": False,
            "default_model": ALTERNATE_MODEL,
            "models": [ALTERNATE_MODEL],
        }
    }


def verify_routes(
    gateway,
    session,
    primary,
    alternate,
    default_reply,
    await_completion,
    require_controls,
):
    features = gateway.request("/v1/capabilities")["features"]
    catalog = gateway.request("/api/model/options?include_unconfigured=false")
    selected = next(
        row for row in catalog["providers"] if ALTERNATE_MODEL in row["models"]
    )
    routes = [
        (selected["slug"], ALTERNATE_MODEL, alternate, ALTERNATE_REPLY),
        (catalog["provider"], catalog["model"], primary, default_reply),
    ]
    controls = features.get("run_toolset_selection") and features.get(
        "run_tool_exclusions"
    )
    if require_controls and not controls:
        raise AssertionError(
            "The requested fork does not advertise both run tool controls"
        )

    for index, (slug, model, server, reply) in enumerate(routes):
        before = [len(target.main_requests()) for target in (primary, alternate)]
        body = {
            "session_id": session,
            "input": ALTERNATE_PROMPT,
            "model": model,
            "provider": slug,
        }
        if controls:
            body["allowed_toolsets"] = ["file"]
            body["excluded_tools"] = ["write_file"] if index == 0 else []
        receipt = gateway.request(
            "/v1/runs",
            method="POST",
            body=body,
            expected=202,
            headers={"Idempotency-Key": secrets.token_urlsafe(24)},
        )
        result = await_completion(gateway, receipt["run_id"])
        if result.get("output") != reply:
            raise AssertionError(
                f"Expected {slug}/{model} to return {reply!r}, received {result.get('output')!r}"
            )
        expected = [
            count + (server is target)
            for count, target in zip(before, (primary, alternate))
        ]
        if [len(target.main_requests()) for target in (primary, alternate)] != expected:
            raise AssertionError(
                f"Unexpected number of model requests after selecting {slug}/{model}"
            )
        request = server.main_requests()[-1]
        if request["model"] != model:
            raise AssertionError(
                f"The selected endpoint received {request['model']!r}, expected {model!r}"
            )
        if controls:
            names = {tool["function"]["name"] for tool in request["tools"]}
            if "read_file" not in names or ("write_file" in names) != (index == 1):
                raise AssertionError(
                    f"The provider received unexpected file tool schemas: {sorted(names)}"
                )
    return {
        "route_models": [item[1] for item in routes],
        "alternate_provider": selected["slug"],
        "tool_exclusions_verified": bool(controls),
    }
