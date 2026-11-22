"""Private loopback Hermes runtime for the mobile acceptance suite."""

import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, Request, build_opener


PINNED_REVISION = "3c43393e5dc075fa216d1b2cd9d60894dc0faf78"
LOOPBACK = "127.0.0.1"


def check_source(source, revision):
    def git(*args):
        return subprocess.check_output(["git", "-C", source, *args], text=True).strip()

    if git("rev-parse", "HEAD") != revision or git("status", "--porcelain"):
        raise ValueError(f"Expected a clean Hermes source checkout at {revision}")
    for filename in ("gateway/run.py", "tests/fakes/fake_llm_provider.py"):
        if not (source / filename).is_file():
            raise ValueError(f"Hermes source checkout missing {filename}")


def unused_loopback_port():
    with socket.socket() as reservation:
        reservation.bind((LOOPBACK, 0))
        return reservation.getsockname()[1]


class Gateway:
    def __init__(self, root, source, provider_url, *, extra_providers=None):
        from hermes_cli.config_defaults import DEFAULT_CONFIG
        from tests.fakes.fake_llm_provider import write_hermes_home

        self.python = sys.executable
        if sys.prefix == sys.base_prefix and os.environ.get("__HERMES_ACTIVATED"):
            # The fork's launcher puts its dependencies on the base Python's
            # PYTHONPATH. Start the isolated child with that checkout's venv.
            from pm.environments import (
                committed_venv,
                running_from_selected_environment,
                venv_python,
            )

            environment = committed_venv(source)
            if environment and running_from_selected_environment(source):
                self.python = venv_python(environment)
        self.port = unused_loopback_port()
        self.endpoint = f"http://{LOOPBACK}:{self.port}"
        self.key = secrets.token_urlsafe(32)
        self.http = build_opener(ProxyHandler({}))
        self.workspace = root / "workspace"
        self.workspace.mkdir(parents=True, mode=0o700)
        self.root = root
        self.source = source
        self.process = None
        home = root / "home"
        runtime = home / ".hermes"
        home.mkdir(parents=True, mode=0o700)
        providers = {
            "custom": {"request_timeout_seconds": 45, "stale_timeout_seconds": 30},
            **(extra_providers or {}),
        }
        write_hermes_home(
            runtime,
            provider_url,
            extra_config=(
                f"_config_version: {DEFAULT_CONFIG['_config_version']}\n"
                "updates:\n  check: false\n"
                # Exercise the app's unnamed-thread fallback with real user messages.
                "auxiliary:\n  title_generation:\n    enabled: false\n"
                "compression:\n  enabled: false\n"
                "memory:\n  memory_enabled: false\n  user_profile_enabled: false\n"
                f"terminal:\n  backend: local\n  cwd: {json.dumps(str(self.workspace))}\n"
                "platform_toolsets:\n  api_server: [file]\n"
                f"providers: {json.dumps(providers)}\n"
            ),
        )
        with (runtime / ".env").open("a", encoding="utf-8") as config:
            config.write(
                f"API_SERVER_ENABLED=true\nAPI_SERVER_KEY={self.key}\nAPI_SERVER_PORT={self.port}\n"
            )
        (runtime / ".env").chmod(0o600)

        # A clean environment avoids importing desktop credentials, PM runtime overrides,
        # provider tokens, service-manager registration, or the host's gateway lock.
        self.env = {
            "PATH": os.defpath,
            "HOME": str(home),
            "HERMES_HOME": str(runtime),
            "HERMES_GATEWAY_LOCK_DIR": str(root / "locks"),
            "HERMES_GATEWAY_MAX_STARTS": "0",
            "PYTHONPATH": os.pathsep.join(
                (str(Path(__file__).parent / "offline"), str(source))
            ),
            "PYTHONUNBUFFERED": "1",
            "PYTHONDONTWRITEBYTECODE": "1",
            "LANG": "C.UTF-8",
            "TZ": "UTC",
            "NO_COLOR": "1",
            "GIT_CONFIG_NOSYSTEM": "1",
            "HF_HUB_OFFLINE": "1",
            "TRANSFORMERS_OFFLINE": "1",
        }

    def _json_response(self, path, *, method="GET", body=None, headers=None, bearer=True):
        auth = {"Authorization": f"Bearer {self.key}"} if bearer else {}
        payload = None if body is None else json.dumps(body).encode()
        request = Request(
            self.endpoint + path,
            data=payload,
            method=method,
            headers={
                "Content-Type": "application/json",
                **auth,
                **(headers or {}),
            },
        )
        try:
            response = self.http.open(request, timeout=15)
        except HTTPError as failure:
            response = failure
        with response:
            return response.status, json.load(response)

    def request(self, path, *, method="GET", body=None, expected=200, headers=None, bearer=True):
        status, value = self._json_response(path, method=method, body=body, headers=headers, bearer=bearer)
        if status != expected:
            raise AssertionError(f"{method} {path}: expected {expected}, got {status}: {value}")
        return value

    def _capabilities_ready(self):
        status, data = self._json_response("/v1/capabilities")
        if (status == 503 and isinstance(data, dict) and data.get("error") == "gateway_not_ready"
                and data.get("state") == "starting"):
            return False
        if status != 200 or not isinstance(data, dict) or not data.get("features"):
            raise RuntimeError(f"Invalid gateway capabilities response during startup (HTTP {status})")
        return True

    def events(self, run_id, last_seq=None):
        headers = {"Authorization": f"Bearer {self.key}", "Accept": "text/event-stream"}
        if last_seq is not None:
            headers["Last-Event-ID"] = str(last_seq)
        request = Request(f"{self.endpoint}/v1/runs/{run_id}/events", headers=headers)
        with self.http.open(request, timeout=45) as response:
            if response.headers.get_content_type() != "text/event-stream":
                raise AssertionError("Run event response is not an SSE stream")
            return [
                json.loads(line[5:]) for line in response if line.startswith(b"data:")
            ]

    def __enter__(self):
        try:
            self.start()
            return self
        except BaseException:
            self.__exit__(*sys.exc_info())
            raise

    def __exit__(self, kind, value, traceback):
        if kind is not None:
            log = self.root / "gateway.log"
            if log.exists():
                print(
                    log.read_text(errors="replace")[-3000:].replace(
                        self.key, "[redacted]"
                    ),
                    file=sys.stderr,
                )
        try:
            self.stop()
        except Exception as failure:
            if value is None:
                raise
            value.add_note(f"Gateway cleanup also failed: {failure}")

    def start(self):
        import psutil

        if self.process is not None:
            raise RuntimeError("Fixture gateway already started")
        with (self.root / "gateway.log").open("ab") as log:
            self.process = subprocess.Popen(
                [self.python, "-m", "gateway.run"],
                env=self.env,
                cwd=self.root,
                stdin=subprocess.DEVNULL,
                stdout=log,
                stderr=subprocess.STDOUT,
            )
        owner = psutil.Process(self.process.pid)
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if (status := self.process.poll()) is not None:
                self.process = None
                raise RuntimeError(f"Gateway exited with status {status}")
            # An unrelated test may claim the ephemeral port while this gateway boots.
            # Send the disposable account token only after our process owns its listener.
            if not any(c.status == psutil.CONN_LISTEN and c.laddr == (LOOPBACK, self.port)
                       for c in owner.net_connections(kind="tcp")):
                time.sleep(0.2)
                continue
            try:
                if self._capabilities_ready():
                    return
            except URLError:
                pass
            time.sleep(0.2)
        raise TimeoutError("Gateway did not accept requests within 90 seconds")

    def stop(self):
        if self.process is None:
            return
        if self.process.poll() is None:
            subprocess.run(
                [
                    self.python,
                    "-c",
                    "import sys; from gateway.status import write_planned_stop_marker;"
                    "sys.exit(0 if write_planned_stop_marker(int(sys.argv[1])) else 1)",
                    str(self.process.pid),
                ],
                cwd=self.root,
                env=self.env,
                check=True,
                timeout=20,
            )
            self.process.wait(timeout=60)
        code = self.process.returncode
        self.process = None
        if code != 0:
            raise RuntimeError(f"Gateway exited with status {code}")
