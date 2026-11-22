"""Configuration shared by the HTTP boundary, ledger reader and sender."""
from dataclasses import dataclass
import hashlib
import os
from pathlib import Path
from .webpush import WebPushConfig


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
    webpush: WebPushConfig | None = None

    def discovery(self):
        result = {"providers": []}
        if self.firebase:
            result["provider"] = "fcm"
            result["providers"].append("fcm")
            result["firebase"] = self.firebase
        if self.webpush:
            result["providers"].append("unifiedpush")
            result["unifiedpush"] = {"vapidPublicKey": self.webpush.public_key}
        return result

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
        fields = {"projectId": "MOBILE_FIREBASE_PROJECT_ID", "gcmSenderId": "MOBILE_FIREBASE_SENDER_ID",
                  "applicationId": "MOBILE_FIREBASE_APP_ID", "apiKey": "MOBILE_FIREBASE_API_KEY"}
        firebase = {k: required(v) for k, v in fields.items()} if any(os.environ.get(v) for v in fields.values()) else {}
        webpush = None
        if os.environ.get("MOBILE_UNIFIEDPUSH_ORIGIN") or os.environ.get("MOBILE_VAPID_SUBJECT"):
            webpush = WebPushConfig.load(state, required("MOBILE_UNIFIEDPUSH_ORIGIN"), required("MOBILE_VAPID_SUBJECT"))
        if not (firebase or webpush):
            raise ValueError("Configure a Firebase or UnifiedPush transport")
        return cls(home, state, key, firebase, number("MOBILE_PUSH_PORT", 8766, maximum=65535),
            number("MOBILE_PUSH_POLL_SECONDS", 2),
            number("MOBILE_PUSH_RETENTION_SECONDS", 604800),
            number("MOBILE_PUSH_RETRY_MAX_SECONDS", 3600),
            number("MOBILE_PUSH_SEND_TIMEOUT_SECONDS", 10),
            number("MOBILE_PUSH_RECONCILE_SECONDS", 600), webpush)
