"""Check two real Hermes runtime boundaries over locally trusted TLS in a disposable Compose project."""
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

from accounts import CADDY_IMAGE, account_map, provision, stack, write_file


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
    def __init__(self, directory, compose, accounts):
        self.root = directory
        self.compose = compose
        self.accounts = accounts
        self.ca = self.root / "root.crt"
        self.port = int(self.command("port", "https", "443").rsplit(":", 1)[1])
        self.keys = {name: (directory / "accounts" / name / "gateway.env").read_text()
                     .strip().split("=", 1)[1] for name in accounts}

    def command(self, *args):
        return subprocess.run([*self.compose, *args], capture_output=True, check=True, text=True).stdout.strip()

    def cert_ready(self):
        self.command("cp", "https:/data/caddy/pki/authorities/local/root.crt", str(self.ca))
        return self.ca.exists()

    def request(self, name, key, method, path, data=None):
        host = self.accounts[name]
        with socket.create_connection(("127.0.0.1", self.port), timeout=4) as sock:
            tls = ssl.create_default_context(cafile=self.ca)
            with tls.wrap_socket(sock, server_hostname=host) as wire:
                connection = http.client.HTTPConnection(host)
                connection.sock = wire
                connection.request(method, path, json.dumps(data) if data else None, {
                    "Authorization": "Bearer " + key, "Content-Type": "application/json"})
                response = connection.getresponse()
                status, body = response.status, response.read()
                connection.close()
        return status, body

    def gateways_ready(self):
        return all(self.request(name, key, "GET", "/v1/capabilities")[0] == 200
                   for name, key in self.keys.items())

    def check(self, condition, label):
        if not condition:
            raise AssertionError(label)
        print("PASS", label, flush=True)

    def run(self):
        wait_for(self.cert_ready, 45)
        wait_for(self.gateways_ready, 150)
        self.check(True, "both gateways accepted their own account key over verified TLS")
        for name, other in [("north", "south"), ("south", "north")]:
            key = self.keys[name]
            status, _ = self.request(other, key, "GET", "/v1/capabilities")
            self.check(status == 401, f"{other} rejected {name}'s key")
            status, _ = self.request(name, key, "GET", "/p/other/v1/capabilities")
            self.check(status == 404, f"{name} gateway exposed only its unprefixed runtime")
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True, help="Same immutable Hermes image as the deployment")
    parser.add_argument("--push", action="store_true", help="Verify both push bridges and their own reminder ledgers")
    parser.add_argument("--proxy-image", default=CADDY_IMAGE)
    parser.add_argument("--plugin-dir", type=Path, help="Use a staged mobile file-artifact plugin")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="hermes-mobile-smoke-") as temp:
        root = Path(temp)
        accounts = account_map(["north=north.localhost", "south=south.localhost"])
        pushes = set(accounts) if args.push else set()
        config, caddy = stack(accounts, pushes, args.image, args.proxy_image, True, 0, plugin=args.plugin_dir)
        config["name"] = provision(root, accounts, set())
        if args.push:
            push_probe.prepare(root, accounts)
        write_file(root / "Caddyfile", caddy)
        write_file(root / "compose.json", json.dumps(config))
        compose = ["docker", "compose", "-f", str(root / "compose.json")]
        try:
            for name in accounts:
                subprocess.run([*compose, "run", "--rm", "-T", "gateway-" + name,
                                "plugins", "enable", "mobile_artifacts"], check=True,
                               stdout=subprocess.DEVNULL)
            subprocess.run([*compose, "up", "-d"], check=True)
            probe = Probe(root, compose, accounts)
            probe.run()
            if args.push:
                push_probe.verify(probe, wait_for)
        finally:
            subprocess.run([*compose, "down", "-v", "--timeout", "10"], check=True)


if __name__ == "__main__":
    main()
