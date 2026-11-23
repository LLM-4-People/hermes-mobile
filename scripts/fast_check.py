#!/usr/bin/env python3
"""Parallel local and CI verification runner for docs, Python test suites, and optional Gradle checks."""

import argparse
import ast
from concurrent.futures import ThreadPoolExecutor, as_completed
import os
from pathlib import Path
import subprocess
import sys
import time


ROOT = Path(__file__).resolve().parents[1]


def _has_unittest_cases(path):
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    for node in ast.walk(tree):
        if isinstance(node, ast.ClassDef):
            for base in node.bases:
                name = base.attr if isinstance(base, ast.Attribute) else getattr(base, "id", "")
                if "TestCase" in name:
                    return True
    return False


def _discover_files(directory, prefix, python_exe, extra_args=()):
    folder = ROOT / directory
    files = [path for path in sorted(folder.glob("test_*.py")) if _has_unittest_cases(path)]
    return [
        (f"{prefix}:{path.stem}", [python_exe, "-B", "-m", "unittest", "discover", "-s", directory, "-p", path.name, *extra_args])
        for path in files
    ]


def _discover_modules(directory, prefix, python_exe):
    folder = ROOT / directory
    package = directory.replace("/", ".")
    files = [path for path in sorted(folder.glob("test_*.py")) if _has_unittest_cases(path)]
    return [
        (f"{prefix}:{path.stem}", [python_exe, "-B", "-m", "unittest", f"{package}.{path.stem}"])
        for path in files
    ]


def build_suites(server_python, workspace_only=False, ci_only=False, with_gradle=False):
    py = sys.executable
    if ci_only:
        return [
            ("docs", [py, "-B", "scripts/docs.py", "--check"]),
            ("writing-quality", [py, "-B", "ci/check_writing_quality.py"]),
            ("hardcoded-ui-strings", [py, "-B", "ci/check_hardcoded_ui_strings.py"]),
            *_discover_files("ci", "ci", py),
        ]
    server_py = server_python or py
    suites = [
        *_discover_files("server/tests", "server-tests", server_py),
        ("server-artifacts", [server_py, "-B", "-m", "unittest", "server.artifacts.test_files"]),
        ("deploy", [py, "-B", "-m", "unittest", "discover", "-s", "deploy"]),
        ("verification-device", [py, "-B", "-m", "unittest", "discover", "-s", "verification/device"]),
        ("verification-unifiedpush", [py, "-B", "-m", "unittest", "discover", "-s", "verification/unifiedpush", "-t", "."]),
        *_discover_modules("verification", "verification", py),
    ]
    if not workspace_only:
        suites = [
            ("docs", [py, "-B", "scripts/docs.py", "--check"]),
            ("writing-quality", [py, "-B", "ci/check_writing_quality.py"]),
            ("hardcoded-ui-strings", [py, "-B", "ci/check_hardcoded_ui_strings.py"]),
            *_discover_files("ci", "ci", py),
            *suites,
        ]
    if with_gradle:
        suites.append((
            "gradle",
            ["./gradlew", ":app:assembleDebug", ":app:assembleRelease",
             ":app:assembleDebugAndroidTest", ":app:lintDebug", ":app:testDebugUnitTest"],
        ))
    return suites


def run_one(name, command):
    start = time.monotonic()
    result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
    return name, result.returncode, time.monotonic() - start, result.stdout, result.stderr


def main(argv=None):
    parser = argparse.ArgumentParser(description="Run independent Hermes Mobile verification suites in parallel.")
    parser.add_argument("--server-python", default=None, help="Python executable with server/requirements.txt installed")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--workspace-only", action="store_true", help="Run only the workspace job's Python suites")
    mode.add_argument("--ci-only", action="store_true", help="Run only docs --check and ci/ unit tests in parallel")
    parser.add_argument("--with-gradle", action="store_true", help="Also run Gradle build, lint, and host unit tests")
    parser.add_argument("--jobs", type=int, default=min(8, max(2, os.cpu_count() or 2)), help="Parallel worker count")
    args = parser.parse_args(argv)

    suites = build_suites(
        args.server_python,
        workspace_only=args.workspace_only,
        ci_only=args.ci_only,
        with_gradle=args.with_gradle,
    )
    wall_start = time.monotonic()
    failed = []
    results = []
    with ThreadPoolExecutor(max_workers=max(1, args.jobs)) as pool:
        futures = {pool.submit(run_one, name, cmd): name for name, cmd in suites}
        for future in as_completed(futures):
            name, code, elapsed, stdout, stderr = future.result()
            status = "PASS" if code == 0 else f"FAIL({code})"
            results.append((name, status, elapsed))
            print(f"[{status:7s}] {name:38s} {elapsed:5.2f}s", flush=True)
            if code != 0:
                failed.append((name, code, stdout, stderr))

    total = time.monotonic() - wall_start
    print(f"Completed {len(results)} suites in {total:.2f}s wall-clock.", flush=True)
    for name, code, stdout, stderr in failed:
        print(f"\n=== FAILURE: {name} (exit {code}) ===", file=sys.stderr)
        if stdout:
            print(stdout, file=sys.stderr)
        if stderr:
            print(stderr, file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
