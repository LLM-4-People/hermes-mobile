"""Run the complete installed Android framework suite and verify each result."""

from collections import defaultdict
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import xml.etree.ElementTree as ET

RUNNER = "org.llm4people.hermes.test/android.test.InstrumentationTestRunner"
SHARD_RE = re.compile(r"^([0-9]+)/([0-9]+)$")
DEFAULT_DISCOVERY_TIMEOUT_SECONDS = 120
DEFAULT_EXECUTION_TIMEOUT_SECONDS = 600


def shard_cases(cases, shard_spec):
    """Deterministically partition packaged test cases by class using LPT load balancing.

    Accepts empty/None (no sharding) or '<index>/<total>' with 0 <= index < total.
    Guarantees complete coverage (union == cases) and disjoint shards across 0..total-1.
    """
    if not shard_spec:
        return set(cases)
    match = SHARD_RE.fullmatch(str(shard_spec).strip())
    if not match:
        raise ValueError(f"Invalid HERMES_NATIVE_SHARD {shard_spec!r} (expected '<index>/<total>')")
    index, total = int(match.group(1)), int(match.group(2))
    if total < 1 or index < 0 or index >= total:
        raise ValueError(f"Out-of-range HERMES_NATIVE_SHARD {shard_spec!r}")
    if total == 1:
        return set(cases)

    by_class = defaultdict(list)
    for cls, method in cases:
        by_class[cls].append((cls, method))
    ordered_classes = sorted(by_class.items(), key=lambda item: (-len(item[1]), item[0]))
    buckets = [set() for _ in range(total)]
    loads = [0] * total
    for _, methods in ordered_classes:
        target = min(range(total), key=lambda i: (loads[i], i))
        buckets[target].update(methods)
        loads[target] += len(methods)
    selected = buckets[index]
    if not selected:
        raise ValueError(f"Shard {shard_spec!r} received zero packaged Android framework tests")
    return selected


def packaged_cases(dexdump):
    """Collect JUnit 3 tests, including inherited methods, across the APK's DEX files."""
    classes = {package.get("name") + "." + cls.get("name"): cls
               for package in ET.fromstring(dexdump).findall("package")
               for cls in package.findall("class")}
    cases = set()
    for owner, cls in classes.items():
        if cls.get("visibility") != "public" or cls.get("abstract") == "true":
            continue
        methods = set()
        parent = owner
        while parent in classes:
            ancestor = classes[parent]
            for method in ancestor.findall("method"):
                name = method.get("name")
                if (name.startswith("test") and method.get("return") == "void"
                        and method.get("visibility") == "public" and method.get("static") == "false"
                        and method.find("parameter") is None):
                    methods.add(name)
            parent = ancestor.get("extends")
        if parent == "junit.framework.TestCase" or re.fullmatch(r"android\.test\.\w*TestCase\d*", parent or ""):
            cases.update((owner, method) for method in methods)
    if not cases:
        raise ValueError("No packaged Android framework test methods found")
    return cases


def successful_cases(output):
    """Read the raw status bundles emitted by the framework InstrumentationTestRunner."""
    fields = {}
    started = set()
    passed = set()
    totals = set()
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, _, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            fields[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.removeprefix("INSTRUMENTATION_STATUS_CODE: "))
            if "class" in fields or "test" in fields:
                case = (fields["class"], fields["test"])
                totals.add(int(fields["numtests"]))
                if fields.get("id") != "InstrumentationTestRunner" or not all(case):
                    raise ValueError("Invalid framework test identity")
                if code == 1 and case not in started:
                    started.add(case)
                elif code == 0 and case in started and case not in passed:
                    passed.add(case)
                else:
                    raise ValueError(f"{case[0]}#{case[1]} returned unexpected instrumentation status {code}")
            elif code != 0 or fields.keys() != {"stream"}:
                raise ValueError(f"Unidentified instrumentation status {code}")
            fields = {}

    summary = re.findall(r"^OK \((\d+) tests?\)$", output, re.MULTILINE)
    result_codes = re.findall(r"^INSTRUMENTATION_CODE: (.*)$", output, re.MULTILINE)
    if (not passed or started != passed or totals != {len(passed)} or fields
            or summary != [str(len(passed))] or result_codes != ["-1"]
            or re.search(r"^(?:INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|FAILURES!!!|"
                         r"Test run aborted|INSTRUMENTATION_RESULT: (?:shortMsg|longMsg)=)", output, re.MULTILINE)):
        raise ValueError(f"Android framework suite did not finish cleanly ({len(passed)}/{len(started)} passed)")
    return passed


def crash_summary(log):
    """Keep only exception types and frame names from this test app's fatal process."""
    summary = []
    for crash in re.split(r"(?m)^FATAL EXCEPTION:", log)[1:]:
        if not re.search(r"(?m)^Process: org\.llm4people\.hermes(?:\.test)?, PID: \d+$", crash):
            continue
        for exception in re.findall(r"(?m)^(?:Caused by: )?([\w.$]+(?:Exception|Error))(?=[:\s]|$)", crash):
            summary.append(exception)
        frames = re.findall(r"(?m)^\s*at ([\w.$]+)\(", crash)
        summary.extend(frames[:8])
    return "\n".join(summary)


def in_flight_cases(output):
    """Return (completed_count, active_cases) from raw InstrumentationTestRunner output for hang diagnostics."""
    fields = {}
    started = []
    finished = set()
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, _, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            fields[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            try:
                code = int(line.removeprefix("INSTRUMENTATION_STATUS_CODE: ").strip())
            except ValueError:
                continue
            if "class" in fields and "test" in fields:
                case = (fields["class"], fields["test"])
                if code == 1:
                    started.append(case)
                else:
                    finished.add(case)
            fields = {}
    active = [case for case in started if case not in finished]
    return len(finished), active


def _timeout_seconds(env_var, default):
    raw = os.environ.get(env_var, "").strip()
    if not raw:
        return default
    value = int(raw)
    if value <= 0:
        raise ValueError(f"{env_var} must be > 0, got {raw!r}")
    return value


def _collect_crash_summary(serial, timeout=15):
    try:
        crash = subprocess.run(
            ["adb", "-s", serial, "logcat", "-d", "-b", "crash", "-v", "raw", "AndroidRuntime:E", "*:S"],
            capture_output=True, text=True, errors="replace", timeout=timeout,
        )
    except (OSError, subprocess.SubprocessError):
        return ""
    return crash_summary(crash.stdout or "")


def timeout_diagnostics(serial):
    """Collect privacy-safe focus, resumed activity, and crash summary when instrumentation hangs."""
    lines = []
    for label, cmd, pattern in (
        ("window_focus", ["adb", "-s", serial, "shell", "dumpsys", "window"], r"mCurrentFocus=[^\r\n]+"),
        (
            "resumed_activity",
            ["adb", "-s", serial, "shell", "dumpsys", "activity", "activities"],
            r"(?:topResumedActivity|mResumedActivity)\s*[:=][^\r\n]+",
        ),
    ):
        try:
            proc = subprocess.run(cmd, capture_output=True, text=True, errors="replace", timeout=15)
        except (OSError, subprocess.SubprocessError):
            continue
        match = re.search(pattern, proc.stdout or "")
        if match:
            lines.append(f"{label}: {match.group(0).strip()}")
    summary = _collect_crash_summary(serial)
    if summary:
        lines.append("crash_summary:\n" + summary)
    return "\n".join(lines)


def run(serial, directory, expected):
    directory.mkdir(parents=True, exist_ok=True)
    classes = ",".join(sorted({cls for cls, _ in expected}))
    discovery_timeout = _timeout_seconds("HERMES_NATIVE_DISCOVERY_TIMEOUT_SECONDS", DEFAULT_DISCOVERY_TIMEOUT_SECONDS)
    execution_timeout = _timeout_seconds("HERMES_NATIVE_TIMEOUT_SECONDS", DEFAULT_EXECUTION_TIMEOUT_SECONDS)
    for name, options, timeout in (
        ("discovery", ["-e", "log", "true"], discovery_timeout),
        ("execution", [], execution_timeout),
    ):
        print(f"Android framework {name} for {len(expected)} packaged tests", flush=True)
        report = directory / f"{name}.txt"
        try:
            with report.open("w") as stream:
                result = subprocess.run(
                    ["adb", "-s", serial, "shell", "am", "instrument", "-w", "-r",
                     "-e", "class", shlex.quote(classes), *options, RUNNER],
                    stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
        except subprocess.TimeoutExpired as error:
            output = report.read_text(errors="replace") if report.exists() else ""
            completed, active = in_flight_cases(output)
            active_desc = ", ".join(f"{cls}#{method}" for cls, method in active) or "none"
            tail = "\n".join(output.splitlines()[-40:])
            if tail:
                print(tail, file=sys.stderr)
            diag = timeout_diagnostics(serial)
            if diag:
                (directory / "timeout-diag.txt").write_text(diag + "\n")
                print(diag, file=sys.stderr)
            print(
                f"Timed out during Android framework {name} after {timeout}s "
                f"(completed {completed}/{len(expected)}, in-flight: {active_desc})",
                file=sys.stderr,
            )
            raise ValueError(
                f"Android framework {name} timed out after {timeout}s "
                f"(completed {completed}/{len(expected)}, in-flight: {active_desc})"
            ) from error
        output = report.read_text(errors="replace")
        try:
            result.check_returncode()
            actual = successful_cases(output)
            if actual != expected:
                raise ValueError(f"Packaged and {name} test sets differ: missing {sorted(expected - actual)}, "
                                 f"unexpected {sorted(actual - expected)}")
        except (ValueError, KeyError, subprocess.CalledProcessError):
            print(output, file=sys.stderr)
            if "INSTRUMENTATION_RESULT: shortMsg=Process crashed." in output:
                summary = _collect_crash_summary(serial)
                if summary:
                    (directory / "crash.txt").write_text(summary + "\n")
                    print(summary, file=sys.stderr)
            raise
    print(f"Android framework instrumentation passed all {len(expected)} packaged tests.")


if __name__ == "__main__":
    try:
        inventory = subprocess.check_output([sys.argv[3], "-l", "xml", sys.argv[4]])
        shard_spec = sys.argv[5] if len(sys.argv) > 5 else os.environ.get("HERMES_NATIVE_SHARD", "")
        expected = shard_cases(packaged_cases(inventory), shard_spec)
        run(sys.argv[1], Path(sys.argv[2]), expected)
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode) from error
