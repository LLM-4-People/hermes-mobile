"""Fail-closed path-aware CI routing and PR area labeling.

Zero third-party dependencies. Classifies changed repository paths into exact CI
execution domains and PR labels, defaulting fail-closed (run everything) on any
unknown file, git diff error, release build, or manual workflow dispatch.
"""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys


ZERO_SHA = "0" * 40
SHA_RE = re.compile(r"^[0-9a-fA-F]{40}$")
RUN_FULL_CI_LABEL = "run-full-ci"

# Strictly allowlisted non-code paths that never affect Android, server, or push runtime.
PAGES_RE = re.compile(r"^(?:site/|\.github/workflows/pages\.yml$)")
DOCS_ONLY_RE = re.compile(
    r"^(?:"
    r"docs/"
    r"|[^/]+\.md$"
    r"|\.impeccable/"
    r"|\.github/ISSUE_TEMPLATE/"
    r"|\.github/PULL_REQUEST_TEMPLATE\.md$"
    r"|\.github/workflows/pr-labeler\.yml$"
    r")"
)
# Strictly allowlisted docs-generation, writing-quality, Pages, and release-automation paths
# verified completely by Validate sources (fast_check.py --ci-only, actionlint, shellcheck) + Pages site check.
CI_META_RE = re.compile(
    r"^(?:"
    r"scripts/docs\.py$"
    r"|ci/semver_bump\.py$"
    r"|ci/test_semver_bump\.py$"
    r"|ci/test_docs_generator\.py$"
    r"|ci/check_writing_quality\.py$"
    r"|ci/test_writing_quality\.py$"
    r"|ci/check_pages\.py$"
    r"|\.github/workflows/auto-release\.yml$"
    r"|\.github/release\.yml$"
    r"|\.github/labeler\.yml$"
    r")"
)
SERVER_ONLY_RE = re.compile(r"^(?:server/|deploy/)")
PUSH_BRIDGE_RE = re.compile(r"^server/(?:mobile_push/|requirements\.txt$|logging_util\.py$|Dockerfile$)")

LABEL_RULES = (
    ("android", re.compile(r"^(?:app/|gradle/|settings\.gradle|build\.gradle|gradle\.properties|gradlew)")),
    ("server", re.compile(r"^(?:server/|deploy/)")),
    ("pages", PAGES_RE),
    ("docs", re.compile(r"^(?:docs/|[^/]+\.md$|\.impeccable/)")),
    ("ci", re.compile(r"^(?:\.github/|ci/|scripts/|verification/)")),
    ("dependencies", re.compile(r"^(?:gradle/libs\.versions\.toml$|server/requirements\.txt$|\.github/dependabot\.yml$)")),
)


def detect_labels(paths):
    """Return sorted area labels matching the changed paths."""
    found = set()
    for path in paths:
        cleaned = path.strip()
        if not cleaned:
            continue
        for label, pattern in LABEL_RULES:
            if pattern.search(cleaned):
                found.add(label)
    return sorted(found)


def classify_files(paths, *, event_name="pull_request", release_mode=False, pr_labels=()):
    """Classify changed files into fail-closed CI execution flags."""
    cleaned = [p.strip() for p in paths if p and p.strip()]
    labels = detect_labels(cleaned)
    applied_labels = {str(lbl).strip() for lbl in (pr_labels or ()) if lbl and str(lbl).strip()}

    def full(reason):
        return {
            "run_workspace": True,
            "run_android": True,
            "run_push_acceptance": True,
            "run_pages_check": True,
            "docs_only": False,
            "labels": labels,
            "file_count": len(cleaned),
            "reason": reason,
        }

    if release_mode:
        return full("release build runs full verification unconditionally")
    if event_name == "workflow_dispatch":
        return full("manual workflow_dispatch runs full verification unconditionally")
    if RUN_FULL_CI_LABEL in applied_labels:
        return full(f"PR carries '{RUN_FULL_CI_LABEL}' label override")
    if not cleaned:
        return full("empty or unavailable diff fails closed to full verification")

    run_workspace = False
    run_android = False
    run_push_acceptance = False
    run_pages_check = False
    ci_meta_touched = False
    unknown_paths = []

    for path in cleaned:
        if PAGES_RE.search(path):
            run_pages_check = True
        elif CI_META_RE.search(path):
            ci_meta_touched = True
            run_pages_check = True
        elif DOCS_ONLY_RE.search(path):
            continue
        elif SERVER_ONLY_RE.search(path):
            run_workspace = True
            if PUSH_BRIDGE_RE.search(path):
                run_push_acceptance = True
        else:
            unknown_paths.append(path)

    if unknown_paths:
        return full(f"code/CI/unclassified paths require full suite ({unknown_paths[0]})")

    docs_only = not (run_workspace or run_android or run_push_acceptance)
    if docs_only:
        reason = (
            "docs/pages/release-automation fast path skips Gradle & Android emulators"
            if ci_meta_touched
            else "pages/docs-only change skips Gradle & Android emulators"
        )
    elif run_push_acceptance:
        reason = "push-bridge server change runs workspace + AOSP closed-app acceptance"
    else:
        reason = "server/deploy-only change runs workspace tests and skips Android emulators"

    return {
        "run_workspace": run_workspace,
        "run_android": run_android,
        "run_push_acceptance": run_push_acceptance,
        "run_pages_check": run_pages_check,
        "docs_only": docs_only,
        "labels": labels,
        "file_count": len(cleaned),
        "reason": reason,
    }


def git_changed_files(base_sha, head_sha, cwd=None):
    """Read changed file paths between base_sha and head_sha; return None on any error (fail-closed)."""
    if not (base_sha and head_sha and SHA_RE.fullmatch(base_sha) and SHA_RE.fullmatch(head_sha)):
        return None
    if base_sha == ZERO_SHA:
        return None
    try:
        output = subprocess.check_output(
            ["git", "diff", "--name-only", f"{base_sha}..{head_sha}"],
            cwd=cwd,
            text=True,
            stderr=subprocess.PIPE,
        )
    except (subprocess.CalledProcessError, OSError):
        return None
    return [line.strip() for line in output.splitlines() if line.strip()]


def format_step_summary(result, *, event_name):
    """Render a concise GitHub Actions job summary table."""
    def badge(enabled):
        return "RUN" if enabled else "SKIP"

    lines = [
        "### Smart CI Path Routing",
        "",
        f"- **Event**: `{event_name}`",
        f"- **Changed files**: `{result['file_count']}`",
        f"- **Decision**: {result['reason']}",
        "",
        "| Domain | Action |",
        "| --- | --- |",
        "| Validate sources (`docs`, `ci`, `actionlint`, `shellcheck`) | **RUN** |",
        f"| Pages static site integrity (`site/`) | **{badge(result['run_pages_check'])}** |",
        f"| Workspace tests (`server/`, `deploy/`, `verification/`) | **{badge(result['run_workspace'])}** |",
        f"| Android debug/release + sharded emulators | **{badge(result['run_android'])}** |",
        f"| Phone acceptance (AOSP closed-app push) | **{badge(result['run_push_acceptance'])}** |",
        "",
    ]
    return "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event", default=os.environ.get("GITHUB_EVENT_NAME", "pull_request"))
    parser.add_argument("--base", default="")
    parser.add_argument("--head", default="")
    parser.add_argument("--release", action="store_true")
    parser.add_argument("--pr-labels-json", default="[]", help="JSON array of PR label names")
    parser.add_argument("--files", nargs="*", help="Explicit changed file list (overrides git diff)")
    parser.add_argument("--github-output", type=Path, default=None)
    parser.add_argument("--step-summary", type=Path, default=None)
    parser.add_argument("--labels-json", action="store_true")
    args = parser.parse_args(argv)

    if args.files is not None:
        paths = args.files
    else:
        paths = git_changed_files(args.base, args.head) or []

    try:
        pr_labels = json.loads(args.pr_labels_json) if args.pr_labels_json else []
    except json.JSONDecodeError:
        pr_labels = []
    if not isinstance(pr_labels, list):
        pr_labels = []

    result = classify_files(paths, event_name=args.event, release_mode=args.release, pr_labels=pr_labels)

    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as handle:
            for key in ("run_workspace", "run_android", "run_push_acceptance", "run_pages_check", "docs_only"):
                handle.write(f"{key}={'true' if result[key] else 'false'}\n")
            handle.write(f"labels={json.dumps(result['labels'])}\n")

    if args.step_summary:
        with args.step_summary.open("a", encoding="utf-8") as handle:
            handle.write(format_step_summary(result, event_name=args.event))

    if args.labels_json:
        print(json.dumps(result["labels"]))
    else:
        print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
