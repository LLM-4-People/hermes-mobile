"""Bound shutdown of one fixture-owned Popen and close its redirected streams."""

import subprocess

from verification.device.run import cleanup_all


ESCALATION_SECONDS = 5


def close_child(process, graceful):
    """Attempt graceful shutdown, then terminate this child and wait for a bounded interval."""
    def request_exit():
        if process.poll() is None:
            try:
                graceful()
            except subprocess.TimeoutExpired:
                pass

    def terminate():
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=ESCALATION_SECONDS)
            except subprocess.TimeoutExpired:
                raise RuntimeError(f"Owned fixture process {process.pid} is still running "
                                   f"{ESCALATION_SECONDS:g}s after terminate()") from None

    streams = (process.stdin, process.stdout, process.stderr)
    cleanup_all(request_exit, terminate, *(stream.close for stream in streams if stream is not None))
