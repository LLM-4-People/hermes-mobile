"""Check two isolated Hermes profiles behind one single-hostname HTTPS ingress over locally trusted TLS."""
import argparse
import http.client
import json
from pathlib import Path
import socket
import ssl
import subprocess
import tempfile
import time
import uuid

import push_probe

from accounts import (
    CADDY_IMAGE, provision, push_transports, stack, validate_hostname, validate_profiles, write_file,
)


def wait_for(check, seconds):
    deadline = time.monotonic() + seconds
    while True:
        try:
            if check():
                return
        except (OSError, subprocess.CalledProcessError, http.client.HTTPException):
            pass
        if time.monotonic() >= deadline:
            raise RuntimeError("Timed out waiting for " + check.__name__)
        time.sleep(1)


class Probe:
    def __init__(self, directory, compose, host, profiles):
        self.root = directory
        self.compose = compose
        self.host = host
        self.profiles = list(profiles)
        self.ca = self.root / "root.crt"
        self.port = int(self.command("port", "https", "443").rsplit(":", 1)[1])
        self.keys = {name: (directory / "accounts" / name / "gateway.env").read_text()
                     .strip().split("=", 1)[1] for name in self.profiles}

    def command(self, *args):
        return subprocess.run([*self.compose, *args], capture_output=True, check=True, text=True).stdout.strip()

    def cert_ready(self):
        self.command("cp", "https:/data/caddy/pki/authorities/local/root.crt", str(self.ca))
        return self.ca.exists()

    def raw_request(self, key, method, path, data=None):
        with socket.create_connection(("127.0.0.1", self.port), timeout=4) as sock:
            tls = ssl.create_default_context(cafile=self.ca)
            with tls.wrap_socket(sock, server_hostname=self.host) as wire:
                connection = http.client.HTTPConnection(self.host)
                connection.sock = wire
                connection.request(method, path, json.dumps(data) if data else None, {
                    "Authorization": "Bearer " + key, "Content-Type": "application/json"})
                response = connection.getresponse()
                status, body = response.status, response.read()
                connection.close()
        return status, body

    def request(self, name, key, method, path, data=None):
        return self.raw_request(key, method, f"/p/{name}{path}", data=data)

    def gateways_ready(self):
        return all(self.request(name, key, "GET", "/v1/capabilities")[0] == 200
                   for name, key in self.keys.items())

    def check(self, condition, label):
        if not condition:
            raise AssertionError(label)
        print("PASS", label, flush=True)

    def clarify_enabled(self, name):
        status, body = self.request(name, self.keys[name], "GET", "/v1/toolsets")
        if status != 200:
            raise RuntimeError(f"Could not read {name}'s tool inventory ({status})")
        row = next(row for row in json.loads(body)["data"] if row["name"] == "clarify")
        return row["enabled"] and "clarify" in row["tools"]

    def run(self):
        wait_for(self.cert_ready, 45)
        wait_for(self.gateways_ready, 150)
        self.check(True, f"both profiles accepted their own key on single host https://{self.host}")
        self.check(self.raw_request(self.keys["north"], "GET", "/v1/capabilities")[0] == 404,
                   "unprefixed root path returns 404")
        self.check(self.raw_request(self.keys["north"], "GET", "/p/unknown/v1/capabilities")[0] == 404,
                   "unknown profile path returns 404")
        for name, other in [("north", "south"), ("south", "north")]:
            key = self.keys[name]
            status, _ = self.request(other, key, "GET", "/v1/capabilities")
            self.check(status == 401, f"/p/{other} rejected {name}'s key on the same host")
            status, _ = self.request(name, key, "GET", "/p/other/v1/capabilities")
            self.check(status == 404, f"/p/{name} rejected nested /p/ prefix")
            status, body = self.request(name, key, "GET", "/v1/capabilities")
            capabilities = json.loads(body)
            self.check(status == 200 and capabilities["features"].get("run_clarify_response")
                       and capabilities["endpoints"].get("run_clarify") == {
                           "method": "POST", "path": "/v1/runs/{run_id}/clarify",
                       }, f"{name} advertises the canonical clarification response route")
            self.check(self.clarify_enabled(name),
                       f"{name}'s fresh API profile enables the clarify toolset")
            thread = "mobile_" + uuid.uuid4().hex
            status, _ = self.request(name, key, "POST", "/api/sessions", {"id": thread, "title": "Private fixture"})
            self.check(status == 201, f"{name} created a private API thread")
            owner_status, _ = self.request(name, key, "GET", "/api/sessions/" + thread)
            foreign_status, _ = self.request(other, self.keys[other], "GET", "/api/sessions/" + thread)
            self.check((owner_status, foreign_status) == (200, 404), f"{name}'s thread is absent from {other}")
            status, files = self.request(name, key, "GET", "/api/mobile/files/" + thread)
            self.check(status == 200 and isinstance(json.loads(files).get("data"), list),
                       f"{name} can open its authenticated file-artifact catalog")
            marker = "/opt/data/workspace/" + thread
            runtime = ("exec", "-T", "-u", "hermes", "-w", "/opt/data/workspace", "gateway-" + name)
            artifact = "Fixture for " + thread
            self.command(*runtime, "python3", "-c",
                         "import sys; from pathlib import Path; Path(sys.argv[1]).write_text(sys.argv[2])",
                         thread, artifact)
            ident = self.command(*runtime, "python3", "/opt/data/plugins/mobile_artifacts/publish.py",
                                 "--session", thread, thread)
            route = f"/api/mobile/files/{thread}/{ident}"
            status, content = self.request(name, key, "GET", route)
            self.check(status == 200 and content == artifact.encode(), f"{name} retrieved its published file bytes")
            self.check(self.request(other, self.keys[other], "GET", route)[0] == 404,
                       f"{other}'s runtime cannot retrieve {name}'s artifact")
            self.command("exec", "-T", "-u", "hermes", "gateway-" + other, "test", "!", "-e", marker)
            self.check(True, f"{name}'s coding workspace is absent from {other}")

        # Exercise the supported operator controls on the disposable homes, then rerun s6 setup.
        settings = {"north": ("platform_toolsets.api_server", '["file"]'),
                    "south": ("agent.disabled_toolsets", '["clarify"]')}
        for name, (setting, value) in settings.items():
            self.command("exec", "-T", "-u", "hermes", "gateway-" + name, "hermes", "config", "set", setting, value)
        self.command("restart", *("gateway-" + name for name in settings))
        wait_for(self.gateways_ready, 150)
        for name, (setting, _) in settings.items():
            self.check(not self.clarify_enabled(name), f"{name} retained its {setting} override after restart")
            status, _ = self.request(name, self.keys[name], "POST", "/v1/runs", {
                "input": "Operator policy check", "allowed_toolsets": ["clarify"],
            })
            self.check(status == 400, f"{name}'s run cannot re-enable its disabled clarification tool")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True, help="Same immutable LLM-4-People/hermes-agent image as the deployment")
    parser.add_argument("--push", action="store_true", help="Verify both push bridges and their own reminder ledgers")
    parser.add_argument("--proxy-image", default=CADDY_IMAGE)
    parser.add_argument("--plugin-dir", type=Path, help="Use a staged mobile file-artifact plugin")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="hermes-mobile-smoke-") as temp:
        root = Path(temp)
        host = validate_hostname("hermes.localhost")
        profiles = validate_profiles(["north", "south"])
        project = provision(root, profiles)
        if args.push:
            push_probe.prepare(root, profiles)
        pushes = push_transports(root, profiles if args.push else [])
        config, caddy = stack(host, profiles, pushes, args.image, args.proxy_image, True, 0, plugin=args.plugin_dir)
        config["name"] = project
        write_file(root / "Caddyfile", caddy)
        write_file(root / "compose.json", json.dumps(config))
        compose = ["docker", "compose", "-f", str(root / "compose.json")]
        try:
            for name in profiles:
                subprocess.run([*compose, "run", "--rm", "-T", "gateway-" + name,
                                "plugins", "enable", "mobile_artifacts"], check=True,
                               stdout=subprocess.DEVNULL)
            subprocess.run([*compose, "up", "-d"], check=True)
            probe = Probe(root, compose, host, profiles)
            probe.run()
            if args.push:
                push_probe.verify(probe, wait_for)
        finally:
            subprocess.run([*compose, "down", "-v", "--timeout", "10"], check=True)


if __name__ == "__main__":
    main()
