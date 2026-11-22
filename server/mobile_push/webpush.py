"""Encrypted UnifiedPush delivery to one operator-selected ntfy instance."""
import base64
from dataclasses import dataclass
from ipaddress import IPv6Address
import json
import os
from pathlib import Path
import re
import tempfile
import time

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from py_vapid import Vapid
from pywebpush import WebPusher
import requests

from .provider import Failure, ProviderError, lifetime, opaque_notice, retry_seconds

# ntfy Android's distributor chooses "up" followed by 12 random alphanumeric characters.
TOPIC = re.compile(r"/up[A-Za-z0-9]{12}\?up=1")
URL_KEY = re.compile(r"[a-zA-Z0-9_-]+={0,2}")
ORIGIN = re.compile(r"(https?)://(\[[a-f0-9:.]+\]|[a-z0-9.-]+)(?::([0-9]+))?", re.IGNORECASE | re.ASCII)
DNS_LABEL = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")


class OriginMismatch(ValueError):
    pass


def canonical_origin(value):
    match = ORIGIN.fullmatch(value) if isinstance(value, str) else None
    if not match:
        raise ValueError("Expected a bare HTTPS origin or HTTP 127.0.0.1")
    scheme, host, raw_port = match.groups()
    scheme, host = scheme.lower(), host.lower()
    if host.startswith("["):
        host = f"[{IPv6Address(host[1:-1]).compressed}]"
    else:
        name = host.removesuffix(".")
        if len(name) > 253 or not all(DNS_LABEL.fullmatch(label) for label in name.split(".")):
            raise ValueError("Invalid UnifiedPush host")
    if scheme == "http" and host != "127.0.0.1":
        raise ValueError("Cleartext UnifiedPush requires literal loopback")
    default_port = 443 if scheme == "https" else 80
    port = int(raw_port) if raw_port is not None else default_port
    if not 0 < port <= 65535:
        raise ValueError("Invalid UnifiedPush port")
    return f"{scheme}://{host}" + (f":{port}" if port != default_port else "")


def public_bytes(key):
    return key.public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)


def base64url(data):
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def decode_key(value, size):
    if not isinstance(value, str) or not URL_KEY.fullmatch(value):
        raise ValueError("Malformed Web Push key")
    stripped = value.rstrip("=")
    decoded = base64.urlsafe_b64decode(stripped + "=" * (-len(stripped) % 4))
    if len(decoded) != size or base64url(decoded) != stripped:
        raise ValueError("Malformed Web Push key")
    return decoded


def load_identity(directory):
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    if directory.stat().st_mode & 0o077:
        raise ValueError("MOBILE_PUSH_STATE must be an owner-only directory")
    target = directory / "vapid.pem"
    if not target.exists():
        key = Vapid()
        key.generate_keys()
        # Atomic publication means concurrent startup can never replace a published signer.
        with tempfile.NamedTemporaryFile(dir=directory, prefix=".vapid-") as handle:
            handle.write(key.private_pem())
            handle.flush()
            os.fsync(handle.fileno())
            try:
                os.link(handle.name, target)
            except FileExistsError:
                pass
    if target.is_symlink() or not target.is_file() or target.stat().st_mode & 0o077:
        raise ValueError("Expected an owner-only VAPID private key")
    return Vapid.from_pem(target.read_bytes())


@dataclass(frozen=True)
class WebPushConfig:
    origin: str
    subject: str
    identity: Vapid

    @classmethod
    def load(cls, directory, origin, subject):
        origin = canonical_origin(origin)
        identity = load_identity(Path(directory))
        config = cls(origin, subject, identity)
        config.authorization()  # Validate the configured operator contact and audience at startup.
        return config

    @property
    def public_key(self):
        return base64url(public_bytes(self.identity.public_key))

    def authorization(self):
        return self.identity.sign({"aud": self.origin, "sub": self.subject})

    def target(self, subscription):
        if not isinstance(subscription, dict) or subscription.keys() != {"endpoint", "keys"}:
            raise ValueError("Invalid Web Push subscription")
        endpoint = subscription["endpoint"]
        if not isinstance(endpoint, str):
            raise ValueError("Invalid ntfy subscription address")
        origin, _, name = endpoint.rpartition("/")
        topic = "/" + name
        if not TOPIC.fullmatch(topic):
            raise ValueError("Invalid ntfy subscription path")
        if canonical_origin(origin) != self.origin:
            raise OriginMismatch("Endpoint outside configured ntfy service")
        keys = subscription["keys"]
        if not isinstance(keys, dict) or keys.keys() != {"auth", "p256dh"}:
            raise ValueError("Invalid Web Push key fields")
        point = decode_key(keys["p256dh"], 65)
        ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), point)
        auth = decode_key(keys["auth"], 16)
        return self.origin + topic, {"p256dh": base64url(point), "auth": base64url(auth)}


class WebPushSender:
    def __init__(self, settings):
        self.config = settings.webpush
        self.timeout = settings.send_timeout
        self.retention = settings.retention
        self.session = requests.Session()
        self.session.trust_env = False

    def send(self, item):
        remaining = lifetime(item, self.retention, time.time())
        if remaining == 0:
            return True
        # A local policy change can be reversed. Only a response from the accepted
        # distributor can invalidate a registration and discard all its deliveries.
        try:
            endpoint, keys = self.config.target({"endpoint": item["token"], "keys": json.loads(item["webpush_keys"])})
        except OriginMismatch as exc:
            raise ProviderError(reason=Failure.ORIGIN) from exc
        except (ValueError, TypeError) as exc:
            raise ProviderError(reason=Failure.SUBSCRIPTION) from exc
        sub = {"endpoint": endpoint, "keys": keys}
        data = json.dumps(opaque_notice(item), separators=(",", ":")).encode()
        body = WebPusher(sub).encode(data, content_encoding="aes128gcm")["body"]
        headers = self.config.authorization()
        headers.update({"Content-Encoding": "aes128gcm", "Content-Type": "application/octet-stream",
                        "TTL": str(int(remaining)), "Urgency": "high"})
        with self.session.post(sub["endpoint"], data=body, headers=headers, timeout=self.timeout,
                               allow_redirects=False, stream=True) as response:
            if 200 <= response.status_code < 300:
                return True
            if response.status_code in {404, 410}:
                return False
            raise ProviderError(response.status_code, retry_seconds(response.headers.get("Retry-After")))
