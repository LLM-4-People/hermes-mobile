# Verify the real Hermes API

The acceptance check starts the fork's gateway in two new temporary homes. Two recording OpenAI-compatible endpoints return distinct French replies over loopback. The fixture selects the alternate provider and model in an existing discussion, then switches back. It checks which endpoint received each turn and the model named in the actual inference request. The script checks authenticated discovery, private thread lists, chat, coding, tool results, token accounting, reminder creation and editing, SSE replay and idempotency after an actual gateway restart.

Prepare a clean checkout of the fork with its Python development environment. The check verifies the checkout against the revision in `verification/gateway_fixture.py`. Run this from the mobile repository, with `fork` set to that checkout's absolute path.

```bash
"$fork/scripts/run-in-hermes-env" python3 -B verification/live_api.py --source "$fork" --require-tool-controls
```

The final JSON line is the result. A failed assertion or gateway error exits nonzero. Use `--revision FULL_COMMIT` to check an explicitly selected new fork revision. The gateway subprocess gets a fresh environment and a loopback-only Python network guard. The tool writes its example into a temporary workspace. The test uses separate keys, homes and ports under the same OS identity. Use the deployment account isolation guidance when hosting different people.

CI requires per-run tool selection from the pinned gateway. Both routed turns request the file toolset. The first excludes `write_file` and the second restores it. The check inspects the schemas received at the model endpoints and requires `read_file` on both. When testing an older fork, omit `--require-tool-controls` to exercise the routes it supports.

## Run a device check against the fixtures

Append `--device-command` followed by a test executable and its arguments. The command runs while both gateways are available. It receives `HERMES_TEST_ENDPOINT`, `HERMES_TEST_BEARER`, `HERMES_TEST_SECOND_ENDPOINT` and `HERMES_TEST_SECOND_BEARER`. It also receives `HERMES_TEST_ALT_PROVIDER` and `HERMES_TEST_ALT_MODEL` from the verified picker contract. The primary runtime has a chat and coding session. The second starts empty. For a debug APK, forward each loopback port with `adb reverse` on the test device. These short-lived bearer values belong only to this test run.

When the device check exits, the fixture asks each gateway to drain through its PID-specific planned-stop mechanism, waits for exit, and removes its temporary files.

<!-- Generated from docs/sources/docs/api-verification.md.in by scripts/docs.py. -->
