"""Run the complete installed Android framework suite and verify each result."""

from pathlib import Path
import re
import subprocess
import sys

RUNNER = "org.llm4people.hermes.test/android.test.InstrumentationTestRunner"


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


def run(serial, directory):
    directory.mkdir(parents=True, exist_ok=True)
    suites = []
    for name, options in (("discovery", ["-e", "log", "true"]), ("execution", [])):
        report = directory / f"{name}.txt"
        with report.open("w") as stream:
            result = subprocess.run(["adb", "-s", serial, "shell", "am", "instrument", "-w", "-r",
                                     *options, RUNNER], stdout=stream, stderr=subprocess.STDOUT)
        output = report.read_text(errors="replace")
        try:
            result.check_returncode()
            suites.append(successful_cases(output))
        except (ValueError, KeyError, subprocess.CalledProcessError):
            print(output, file=sys.stderr)
            raise
    discovered, executed = suites
    if discovered != executed:
        raise ValueError(f"Suite changed between discovery and execution: missing {sorted(discovered - executed)}, "
                         f"unexpected {sorted(executed - discovered)}")
    print(f"Android framework instrumentation passed all {len(executed)} discovered tests.")


if __name__ == "__main__":
    try:
        run(sys.argv[1], Path(sys.argv[2]))
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode) from error
