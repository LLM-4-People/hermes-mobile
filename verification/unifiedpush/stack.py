"""Real loopback transports for an Android UnifiedPush acceptance run."""

from contextlib import contextmanager
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import time
from urllib.error import URLError
from urllib.parse import urlsplit
from urllib.request import ProxyHandler, Request, build_opener

from deploy.accounts import CADDY_IMAGE
from verification.gateway_fixture import unused_loopback_port

LOCAL_HTTP = build_opener(ProxyHandler({}))


def eventually(description, check, timeout=90):
    limit = time.monotonic() + timeout
    while time.monotonic() < limit:
        result = check()
        if result:
            return result
        time.sleep(0.4)
    raise AssertionError(f"Timed out waiting for {description}")


@contextmanager
def serve(command, directory, env=None):
    with (directory / "service.log").open("ab") as log:
        proc = subprocess.Popen(
            command, cwd=directory, env=env, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT
        )
        try:
            yield proc
        finally:
            if proc.poll() is None:
                # Both official servers and aiohttp handle an interrupt by draining requests.
                proc.send_signal(signal.SIGINT)
                proc.wait(timeout=45)


def json_request(url, bearer=None):
    headers = {"Authorization": f"Bearer {bearer}"} if bearer else {}
    with LOCAL_HTTP.open(Request(url, headers=headers), timeout=5) as response:
        return json.load(response)


def wait_http(proc, url, predicate, bearer=None):
    def ready():
        if proc.poll() is not None:
            raise RuntimeError(f"{Path(proc.args[0]).name} exited with {proc.returncode}")
        try:
            return predicate(json_request(url, bearer))
        except (URLError, socket.timeout):
            return False

    eventually("loopback service health", ready)


def caddy_binary(directory):
    # Extract the binary from the same immutable image as the deployment.
    create = subprocess.check_output(["docker", "create", CADDY_IMAGE], text=True).strip()
    try:
        path = directory / "caddy"
        subprocess.run(["docker", "cp", create + ":/usr/bin/caddy", path], check=True)
        return path
    finally:
        subprocess.run(["docker", "rm", create], check=True, stdout=subprocess.DEVNULL)


@contextmanager
def ntfy(directory, executable):
    endpoint = f"http://127.0.0.1:{unused_loopback_port()}"
    config = directory / "server.yml"
    config.write_text(
        f'base-url: "{endpoint}"\nlisten-http: "{urlsplit(endpoint).netloc}"\n'
        f'cache-file: "{directory / "messages.sqlite"}"\nweb-root: disable\n'
    )
    with serve([executable, "serve", "--config", config], directory) as proc:
        wait_http(proc, endpoint + "/v1/health", lambda obj: obj.get("healthy"))
        yield endpoint


@contextmanager
def bridge(directory, gateway, ntfy_endpoint, push_python, project):
    port = unused_loopback_port()
    state = directory / "push-state"
    env = {
        "PATH": os.defpath,
        "HOME": str(directory),
        "PYTHONPATH": str(project),
        "MOBILE_PUSH_BIND": "127.0.0.1",
        "MOBILE_PUSH_PORT": str(port),
        "MOBILE_PUSH_STATE": str(state),
        "MOBILE_PUSH_POLL_SECONDS": "1",
        "MOBILE_UNIFIEDPUSH_ORIGIN": ntfy_endpoint,
        "MOBILE_VAPID_SUBJECT": "mailto:fixture@example.invalid",
        "HERMES_HOME": gateway.env["HERMES_HOME"],
        "API_SERVER_KEY": gateway.key,
    }
    with serve([push_python, "-m", "server.mobile_push"], directory, env) as proc:
        base = f"http://127.0.0.1:{port}"
        wait_http(
            proc, base + "/api/mobile/push", lambda obj: "unifiedpush" in obj.get("providers", []), gateway.key
        )
        yield base, state / "mobile-push.sqlite3"


@contextmanager
def proxy(directory, gateway, bridge_endpoint):
    from deploy.accounts import account_routes

    url = f"http://127.0.0.1:{unused_loopback_port()}"
    routes = account_routes(
        "fixture",
        url,
        True,
        False,
        gateway_upstream=urlsplit(gateway.endpoint).netloc,
        push_upstream=urlsplit(bridge_endpoint).netloc,
    )
    config = directory / "Caddyfile"
    config.write_text("{\n  admin off\n  default_bind 127.0.0.1\n  persist_config off\n}\n" + routes)
    with serve(
        [caddy_binary(directory), "run", "--config", config],
        directory,
        {
            "HOME": str(directory),
            "PATH": os.defpath,
        },
    ) as proc:
        wait_http(proc, url + "/v1/capabilities", lambda obj: bool(obj.get("features")), gateway.key)
        wait_http(
            proc, url + "/api/mobile/push", lambda obj: "unifiedpush" in obj.get("providers", []), gateway.key
        )
        yield url
