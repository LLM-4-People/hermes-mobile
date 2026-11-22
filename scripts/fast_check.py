#!/usr/bin/env python3
"""Parallel local and CI verification runner for docs, Python test suites, and optional Gradle checks."""

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import os
from pathlib import Path
import subprocess
import sys
import time


ROOT = Path(__file__).resolve().parents[1]


def build_suites(server_python, workspace_only=False, with_gradle=False):
    py = sys.executable
    server_py = server_python or py
    suites = [
        ("server-tests", [server_py, "-B", "-m", "unittest", "discover", "-s", "server/tests"]),
        ("server-artifacts", [server_py, "-B", "-m", "unittest", "server.artifacts.test_files"]),
        ("deploy", [py, "-B", "-m", "unittest", "discover", "-s", "deploy"]),
        ("verification-device", [py, "-B", "-m", "unittest", "discover", "-s", "verification/device"]),
        ("verification-unifiedpush", [py, "-B", "-m", "unittest", "discover", "-s", "verification/unifiedpush", "-t", "."]),
        ("verification-phone-actions", [py, "-B", "-m", "unittest", "verification.test_phone_actions"]),
    ]
    if not workspace_only:
        suites.insert(0, ("docs", [py, "-B", "scripts/docs.py", "--check"]))
        suites.insert(1, ("ci", [py, "-B", "-m", "unittest", "discover", "-s", "ci", "-p", "test_*.py"]))
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
    parser.add_argument("--workspace-only", action="store_true", help="Run only the workspace job's Python suites")
    parser.add_argument("--with-gradle", action="store_true", help="Also run Gradle build, lint, and host unit tests")
    parser.add_argument("--jobs", type=int, default=min(6, max(2, os.cpu_count() or 2)), help="Parallel worker count")
    args = parser.parse_args(argv)

    suites = build_suites(args.server_python, workspace_only=args.workspace_only, with_gradle=args.with_gradle)
    wall_start = time.monotonic()
    failed = []
    results = []
    with ThreadPoolExecutor(max_workers=max(1, args.jobs)) as pool:
        futures = {pool.submit(run_one, name, cmd): name for name, cmd in suites}
        for future in as_completed(futures):
            name, code, elapsed, stdout, stderr = future.result()
            status = "PASS" if code == 0 else f"FAIL({code})"
            results.append((name, status, elapsed))
            print(f"[{status:7s}] {name:28s} {elapsed:5.2f}s", flush=True)
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
