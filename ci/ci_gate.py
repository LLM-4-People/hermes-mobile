"""Fail-closed CI Gate evaluator for `.github/workflows/checks.yml`.

Verifies that every required upstream job succeeded and that jobs were skipped
ONLY when `changes` intentionally routed them off (or `aosp` under release mode).
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys
from typing import Any


ALWAYS_REQUIRED_JOBS: tuple[str, ...] = ("changes", "validate")

ROUTED_JOBS: tuple[str, ...] = (
    "pages-check",
    "workspace",
    "package-debug",
    "package",
    "device",
    "device-native-1",
    "device-live-core",
    "device-journeys",
    "phone-handoffs",
    "nearby-guide",
    "aosp",
)

ALL_UPSTREAM_JOBS: tuple[str, ...] = ALWAYS_REQUIRED_JOBS + ROUTED_JOBS


def _parse_bool_flag(outputs: dict[str, Any], key: str) -> bool:
    raw = outputs.get(key)
    if raw == "true":
        return True
    if raw == "false":
        return False
    raise ValueError(f"changes.outputs.{key} must be 'true' or 'false', got {raw!r}")


def expected_job_matrix(outputs: dict[str, Any], *, release_mode: bool) -> dict[str, bool]:
    """Return whether each upstream job is required to succeed for this run."""
    run_pages = _parse_bool_flag(outputs, "run_pages_check")
    run_workspace = _parse_bool_flag(outputs, "run_workspace")
    run_android = _parse_bool_flag(outputs, "run_android")
    run_push = _parse_bool_flag(outputs, "run_push_acceptance")
    return {
        "changes": True,
        "validate": True,
        "pages-check": run_pages,
        "workspace": run_workspace,
        "package-debug": run_android,
        "package": run_android or run_push,
        "device": run_android,
        "device-native-1": run_android,
        "device-live-core": run_android,
        "device-journeys": run_android,
        "phone-handoffs": run_android,
        "nearby-guide": run_android,
        "aosp": (not release_mode) and run_push,
    }


def evaluate_gate(
    needs: dict[str, Any],
    *,
    release_mode: bool = False,
) -> tuple[bool, list[str], list[tuple[str, str, bool]]]:
    """Evaluate all upstream job results fail-closed.

    Returns `(ok, errors, rows)` where each row is `(job_id, result, required)`.
    """
    if not isinstance(needs, dict):
        return False, ["needs payload must be a JSON object"], []

    errors: list[str] = []
    expected_set = set(ALL_UPSTREAM_JOBS)
    actual_set = set(needs.keys())
    missing_jobs = sorted(expected_set - actual_set)
    extra_jobs = sorted(actual_set - expected_set)
    if missing_jobs:
        errors.append(f"Missing upstream jobs in needs: {', '.join(missing_jobs)}")
    if extra_jobs:
        errors.append(f"Unexpected upstream jobs in needs (update ci_gate.py): {', '.join(extra_jobs)}")

    changes_entry = needs.get("changes")
    changes_result = changes_entry.get("result") if isinstance(changes_entry, dict) else None
    if changes_result != "success":
        errors.append(f"Job 'changes' must succeed, got {changes_result!r}")
        return False, errors, []

    outputs = changes_entry.get("outputs") if isinstance(changes_entry, dict) else None
    if not isinstance(outputs, dict):
        errors.append("Job 'changes' is missing outputs map")
        return False, errors, []

    try:
        required_map = expected_job_matrix(outputs, release_mode=release_mode)
    except ValueError as exc:
        errors.append(str(exc))
        return False, errors, []

    rows: list[tuple[str, str, bool]] = []
    for job_id in ALL_UPSTREAM_JOBS:
        entry = needs.get(job_id)
        result = entry.get("result") if isinstance(entry, dict) else None
        result_str = str(result) if isinstance(result, str) else "<missing>"
        required = required_map[job_id]
        rows.append((job_id, result_str, required))
        if required:
            if result != "success":
                errors.append(f"Required job '{job_id}' did not succeed (result={result_str!r})")
        else:
            if result not in ("skipped", "success"):
                errors.append(f"Optional/routed-off job '{job_id}' failed or was cancelled (result={result_str!r})")

    return len(errors) == 0, errors, rows


def format_summary(ok: bool, errors: list[str], rows: list[tuple[str, str, bool]], *, release_mode: bool) -> str:
    lines = [
        "### CI Gate",
        "",
        f"- **Verdict**: {'PASS' if ok else 'FAIL'}",
        f"- **Release mode**: `{str(release_mode).lower()}`",
        "",
    ]
    if rows:
        lines.extend([
            "| Job | Required | Result |",
            "| --- | --- | --- |",
        ])
        for job_id, result_str, required in rows:
            lines.append(f"| `{job_id}` | `{'yes' if required else 'no'}` | `{result_str}` |")
        lines.append("")
    if errors:
        lines.append("#### Failures")
        for err in errors:
            lines.append(f"- {err}")
        lines.append("")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Fail-closed CI Gate evaluator.")
    parser.add_argument("--needs-json", default=None, help="JSON string of GitHub Actions `needs` context (default: $NEEDS_JSON)")
    parser.add_argument("--release", action="store_true", help="Release workflow_call mode (overrides $RELEASE_MODE)")
    parser.add_argument("--step-summary", default="", help="Optional path to append markdown summary")
    args = parser.parse_args(argv)

    raw_needs = args.needs_json if args.needs_json is not None else os.environ.get("NEEDS_JSON", "")
    release_mode = args.release or (os.environ.get("RELEASE_MODE", "0") == "1")

    try:
        needs = json.loads(raw_needs)
    except json.JSONDecodeError as exc:
        msg = f"Invalid NEEDS_JSON: {exc}"
        print(msg, file=sys.stderr)
        return 1

    ok, errors, rows = evaluate_gate(needs, release_mode=release_mode)
    summary = format_summary(ok, errors, rows, release_mode=release_mode)
    if args.step_summary:
        with Path(args.step_summary).open("a", encoding="utf-8") as handle:
            handle.write(summary)

    if not ok:
        for err in errors:
            print(f"CI Gate FAILED: {err}", file=sys.stderr)
        return 1

    print(f"CI Gate PASSED ({len(rows)} upstream jobs verified).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
