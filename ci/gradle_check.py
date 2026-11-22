"""Retry a CI build only when Maven Central rate-limits a dependency request."""

import re
import subprocess
import sys
import time


MAVEN_REQUEST = re.compile(r"Could not (?:GET|HEAD) 'https://repo\.maven\.apache\.org/maven2/")


def build(command, delays=(30, 90)):
    for attempt in range(len(delays) + 1):
        rate_limited = False
        central_request = False
        with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, errors="replace") as process:
            for line in process.stdout:
                print(line, end="", flush=True)
                current_request = MAVEN_REQUEST.search(line) is not None
                if (central_request or current_request) and "Received status code 429 from server" in line:
                    rate_limited = True
                central_request = current_request
            status = process.wait()
        if status == 0 or not rate_limited or attempt == len(delays):
            return status
        pause = delays[attempt]
        print(f"Maven Central rate-limited dependency resolution. Retrying in {pause}s.", flush=True)
        time.sleep(pause)


if __name__ == "__main__":
    sys.exit(build(["./gradlew", *sys.argv[1:]]))
