"""Configuration shared by the HTTP boundary, ledger reader and sender."""
from dataclasses import dataclass
import hashlib
import os
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    home: Path
    state: Path
    profile_key: str
    firebase: dict[str, str]
    port: int
    poll_seconds: int
    retention: int
    retry_max: int
    send_timeout: int
    reconcile_seconds: int = 600

    @property
    def run_scope(self) -> str:
        # A dedicated (non-multiplexed) Hermes API process uses its default auth namespace.
        return hashlib.sha256(("default\0" + self.profile_key).encode()).hexdigest()

    @classmethod
    def load(cls):
        def number(name, default, *, maximum=None):
            value = int(os.environ.get(name, default))
            if value <= 0 or (maximum is not None and value > maximum):
                raise ValueError(f"Invalid setting {name}")
            return value

        def required(name):
            value = os.environ.get(name, "").strip()
            if not value:
                raise ValueError(f"Missing setting {name}")
            return value

        home = Path(required("HERMES_HOME")).expanduser().resolve(strict=True)
        state = Path(required("MOBILE_PUSH_STATE")).expanduser().resolve()
        key = required("API_SERVER_KEY")
        if len(key) < 16 or not key.isascii() or any(c.isspace() for c in key):
            raise ValueError("Set a usable API_SERVER_KEY")
        return cls(home, state, key, {
            "projectId": required("MOBILE_FIREBASE_PROJECT_ID"),
            "gcmSenderId": required("MOBILE_FIREBASE_SENDER_ID"),
            "applicationId": required("MOBILE_FIREBASE_APP_ID"),
            "apiKey": required("MOBILE_FIREBASE_API_KEY"),
        }, number("MOBILE_PUSH_PORT", 8766, maximum=65535),
            number("MOBILE_PUSH_POLL_SECONDS", 2),
            number("MOBILE_PUSH_RETENTION_SECONDS", 604800),
            number("MOBILE_PUSH_RETRY_MAX_SECONDS", 3600),
            number("MOBILE_PUSH_SEND_TIMEOUT_SECONDS", 10),
            number("MOBILE_PUSH_RECONCILE_SECONDS", 600))
