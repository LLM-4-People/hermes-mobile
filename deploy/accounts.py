"""Render isolated Hermes account services and a hostname-routed HTTPS ingress."""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import tempfile

CADDY_IMAGE = "caddy@sha256:003a97808e781aa9f7e75cc2f43d34d461099031faf5e20faefb9f61126836f0"
IMAGE_DIGEST = re.compile(r"(?:[a-z0-9./_:-]+@)?sha256:[0-9a-f]{64}")
DNS_LABEL = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
SERVER = Path(__file__).resolve().parents[1] / "server"
API_PORT = 8642
PUSH_PORT = 8766


def account_map(items):
    accounts = {}
    for item in items:
        name, separator, domain = item.partition("=")
        domain = domain.lower()
        if not separator or not DNS_LABEL.fullmatch(name) or len(name) > 32:
            raise ValueError("Use a short lowercase account ID followed by = and its hostname")
        if len(domain) > 253 or "." not in domain or not all(DNS_LABEL.fullmatch(p) for p in domain.split(".")):
            raise ValueError("Each account needs a fully qualified DNS hostname")
        if name in accounts or domain in accounts.values():
            raise ValueError("Each account needs its own ID and hostname")
        accounts[name] = domain
    return accounts


def write_file(path, data, *, preserve=False):
    if preserve and path.exists():
        return
    with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, delete=False) as stream:
        temp = Path(stream.name)
        try:
            stream.write(data)
            stream.flush()
            if preserve:
                # A concurrent renderer either installs a complete value or keeps the first.
                try:
                    os.link(temp, path)
                except FileExistsError:
                    pass
            else:
                os.replace(temp, path)
        finally:
            temp.unlink(missing_ok=True)


def file_env(path):
    # Preserve literal dollar signs in credentials. Compose must not expand these values.
    return {"path": str(path), "format": "raw"}


def gateway(name, image, plugin):
    return {
        "image": image,
        "pull_policy": "never" if image.startswith("sha256:") else "missing",
        "restart": "unless-stopped",
        "command": ["gateway", "run"],
        "env_file": [file_env(f"./accounts/{name}/provider.env"), file_env(f"./accounts/{name}/gateway.env")],
        "environment": {
            "API_SERVER_ENABLED": "true", "API_SERVER_HOST": "0.0.0.0", "API_SERVER_PORT": str(API_PORT),
            "GATEWAY_MULTIPLEX_PROFILES": "false", "TERMINAL_CWD": "/opt/data/workspace",
        },
        "security_opt": ["no-new-privileges:true"],
        "volumes": [
            f"home-{name}:/opt/data",
            {"type": "bind", "source": str(plugin), "target": "/opt/data/plugins/mobile_artifacts",
             "read_only": True, "bind": {"create_host_path": False}},
        ],
        "networks": [f"user-{name}"],
    }


def push_transports(root, names):
    """Find accounts needing a Firebase secret mount. The server validates provider settings."""
    result = {}
    for name in names:
        directory = root / "accounts" / name
        config = directory / "push.env"
        if not config.is_file():
            raise ValueError(f"Configure accounts/{name}/push.env before enabling push")
        entries = (line.partition("=") for line in config.read_text().splitlines())
        result[name] = any(key.strip().startswith("MOBILE_FIREBASE_") and bool(value.strip())
                           for key, _, value in entries)
        if result[name] and not (directory / "firebase.json").is_file():
            raise ValueError(f"Firebase requires accounts/{name}/firebase.json")
    return result


def push(name, firebase):
    service = {
        "build": {"context": str(SERVER)},
        "restart": "unless-stopped",
        "user": "10000:10000",
        "read_only": True,
        "tmpfs": ["/tmp:rw,nosuid,nodev,noexec"],
        "cap_drop": ["ALL"],
        "security_opt": ["no-new-privileges:true"],
        "env_file": [file_env(f"./accounts/{name}/push.env"), file_env(f"./accounts/{name}/gateway.env")],
        "environment": {
            "MOBILE_PUSH_BIND": "0.0.0.0", "MOBILE_PUSH_PORT": str(PUSH_PORT),
            "HERMES_HOME": "/opt/data", "MOBILE_PUSH_STATE": "/var/lib/hermes-mobile-push",
        },
        "volumes": [f"home-{name}:/opt/data:ro", f"outbox-{name}:/var/lib/hermes-mobile-push"],
        "networks": [f"user-{name}"],
    }
    if firebase:
        path = "/run/credentials/firebase.json"
        service["environment"]["GOOGLE_APPLICATION_CREDENTIALS"] = path
        service["volumes"].append({"type": "bind", "source": f"./accounts/{name}/firebase.json",
                                   "target": path, "read_only": True, "bind": {"create_host_path": False}})
    return service


def account_routes(name, domain, with_push, local):
    lines = [domain + " {"]
    if local:
        lines.append("    tls internal")
    lines += [
        "    @profiles path /p /p/*",
        "    handle @profiles {",
        "        respond 404",
        "    }",
        "    @push path /api/mobile/push /api/mobile/push/*",
        "    handle @push {",
        f"        reverse_proxy push-{name}:{PUSH_PORT}" if with_push else "        respond 404",
        "    }",
        "    handle {",
        f"        reverse_proxy gateway-{name}:{API_PORT}",
        "    }", "}",
    ]
    return "\n".join(lines)


def stack(accounts, pushes, image, proxy_image, local, port, *, plugin=None):
    plugin = (plugin or SERVER / "artifacts").resolve()
    if not IMAGE_DIGEST.fullmatch(image) or not IMAGE_DIGEST.fullmatch(proxy_image):
        raise ValueError("Supply immutable registry digests or local sha256 image IDs")
    if not pushes.keys() <= accounts.keys():
        raise ValueError("Each push bridge must belong to a configured account")
    if not 0 <= port <= 65535 or (not local and port != 443):
        raise ValueError("Public ACME requires port 443. Local TLS accepts a chosen port or 0 for an ephemeral port")
    ports = [f"127.0.0.1:{port}:443"] if local else ["80:80", "443:443"]
    volumes = {"proxy-data": {}}
    networks = {f"user-{name}": {} for name in accounts}
    services = {"https": {
        "image": proxy_image, "ports": ports, "restart": "unless-stopped",
        "networks": list(networks), "volumes": [
            {"type": "bind", "source": "./Caddyfile", "target": "/etc/caddy/Caddyfile",
             "read_only": True, "bind": {"create_host_path": False}},
            "proxy-data:/data",
        ],
    }}
    blocks = ["{\n    admin off\n}"]
    for name, domain in accounts.items():
        services["gateway-" + name] = gateway(name, image, plugin)
        volumes["home-" + name] = {}
        if name in pushes:
            services["push-" + name] = push(name, pushes[name])
            volumes["outbox-" + name] = {}
        blocks.append(account_routes(name, domain, name in pushes, local))
    return {"services": services, "volumes": volumes, "networks": networks}, "\n\n".join(blocks) + "\n"


def provision(root, accounts):
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    if root.stat().st_mode & 0o077:
        raise ValueError("Deployment directory must be private (chmod 700)")
    identity = root / "project-id"
    write_file(identity, "hermes-mobile-" + secrets.token_hex(16) + "\n", preserve=True)
    project = identity.read_text().strip()
    if not re.fullmatch(r"hermes-mobile-[0-9a-f]{32}", project):
        raise ValueError("Invalid deployment project-id. Restore its backed-up value")
    keys = set()
    for name in accounts:
        directory = root / "accounts" / name
        directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        write_file(directory / "gateway.env", f"API_SERVER_KEY={secrets.token_hex(32)}\n", preserve=True)
        key_path = directory / "gateway.env"
        key = key_path.read_text().strip()
        if key_path.stat().st_mode & 0o077 or not re.fullmatch(r"API_SERVER_KEY=[0-9a-f]{64}", key):
            raise ValueError(f"Expected a private gateway.env with a 256-bit hex API key for {name}")
        if key in keys:
            raise ValueError("Each configured account must have a unique bearer key")
        keys.add(key)
        write_file(directory / "provider.env", "", preserve=True)
    return project


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="Private deployment directory outside your source tree")
    parser.add_argument("--account", required=True, action="append", metavar="ID=HOSTNAME")
    parser.add_argument("--push", default=[], action="append", metavar="ID")
    parser.add_argument("--image", required=True, help="Immutable Hermes runtime image")
    parser.add_argument("--proxy-image", default=CADDY_IMAGE)
    parser.add_argument("--local", action="store_true", help="Loopback TLS using an internal CA")
    parser.add_argument("--port", type=int, default=443)
    args = parser.parse_args()
    try:
        accounts = account_map(args.account)
        root = args.directory.resolve()
        if not set(args.push) <= accounts.keys():
            raise ValueError("Each push bridge must belong to a configured account")
        pushes = push_transports(root, args.push)
        compose, caddy = stack(accounts, pushes, args.image, args.proxy_image, args.local, args.port)
        compose["name"] = provision(root, accounts)
        write_file(root / "Caddyfile", caddy)
        write_file(root / "compose.json", json.dumps(compose, indent=2) + "\n")
    except (ValueError, OSError) as exc:
        parser.exit(1, f"Deployment configuration error: {exc}\n")
    print(f"Rendered {root / 'compose.json'} for {len(accounts)} account(s).")


if __name__ == "__main__":
    main()
