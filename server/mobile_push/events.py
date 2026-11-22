from dataclasses import dataclass
import hashlib
import json
import re


APPROVAL = "approval_requested"
QUESTION = "question_requested"
DISMISS_QUESTION = "question_settled"
DISMISS_APPROVAL = "approval_settled"
PROMPT_KINDS = (APPROVAL, QUESTION)
WITHDRAWAL_KINDS = (DISMISS_APPROVAL, DISMISS_QUESTION)

DELIVERY_KINDS = (
    "notice",
    "card",
    "file",
    "ui_patch",
    "widget_update",
    "device_action",
    "reminder_result",
    "briefing",
)
URGENCY_LEVELS = ("normal", "high", "silent")
_URGENCY_ALIASES = {"urgent": "high"}
DEFAULT_URGENCY = "normal"
MAX_DELIVERY_TITLE_CHARS = 240
MAX_DELIVERY_PAYLOAD_BYTES = 131_072
MAX_LINK_ID_CHARS = 256
DELIVERY_ID_RE = re.compile(r"^(?:[a-f0-9]{32}|dlv_[a-f0-9]{16})$")
_ALLOWED_DELIVERY_FIELDS = frozenset({
    "kind",
    "title",
    "payload",
    "urgency",
    "session_id",
    "run_id",
    "job_id",
    "delivery_id",
})


def opaque_profile_hint(scope: str) -> str:
    """Derive a stable, non-reversible, PII-free opaque hint for one profile scope."""
    if not isinstance(scope, str) or not scope:
        raise ValueError("Invalid profile scope")
    return hashlib.sha256(("profile-hint-v1\0" + scope).encode("utf-8")).hexdigest()[:16]


def _has_control_chars(text: str, *, allow_newline: bool = False) -> bool:
    for ch in text:
        code = ord(ch)
        if allow_newline and ch in ("\n", "\t"):
            continue
        if code < 32 or code == 127:
            return True
    return False


def _optional_link(value, name: str) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise ValueError(f"Invalid {name}")
    trimmed = value.strip()
    if not trimmed or len(trimmed) > MAX_LINK_ID_CHARS or _has_control_chars(trimmed):
        raise ValueError(f"Invalid {name}")
    return trimmed


def validate_delivery_input(data) -> dict:
    """Validate and normalize a typed delivery creation request (Single Source of Truth)."""
    if not isinstance(data, dict):
        raise ValueError("Invalid delivery body")
    unknown = set(data.keys()) - _ALLOWED_DELIVERY_FIELDS
    if unknown:
        raise ValueError("Unexpected delivery fields")
    kind = data.get("kind")
    if not isinstance(kind, str) or kind not in DELIVERY_KINDS:
        raise ValueError("Unsupported delivery kind")
    urgency = data.get("urgency", DEFAULT_URGENCY)
    if isinstance(urgency, str):
        urgency = _URGENCY_ALIASES.get(urgency, urgency)
    if not isinstance(urgency, str) or urgency not in URGENCY_LEVELS:
        raise ValueError("Unsupported delivery urgency")
    title = data.get("title")
    if not isinstance(title, str):
        raise ValueError("Delivery title required")
    title = title.strip()
    if not title or len(title) > MAX_DELIVERY_TITLE_CHARS or _has_control_chars(title):
        raise ValueError("Invalid delivery title")
    payload = data.get("payload", {})
    if payload is None:
        payload = {}
    if not isinstance(payload, dict):
        raise ValueError("Delivery payload must be an object")
    try:
        encoded = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    except (TypeError, ValueError) as exc:
        raise ValueError("Delivery payload must be JSON-serializable") from exc
    if len(encoded) > MAX_DELIVERY_PAYLOAD_BYTES:
        raise ValueError("Delivery payload exceeds byte limit")
    delivery_id = data.get("delivery_id")
    if delivery_id is not None:
        if not isinstance(delivery_id, str) or not DELIVERY_ID_RE.fullmatch(delivery_id):
            raise ValueError("Invalid delivery_id")
    return {
        "delivery_id": delivery_id,
        "kind": kind,
        "urgency": urgency,
        "title": title,
        "payload": payload,
        "payload_json": encoded.decode("utf-8"),
        "session_id": _optional_link(data.get("session_id"), "session_id"),
        "run_id": _optional_link(data.get("run_id"), "run_id"),
        "job_id": _optional_link(data.get("job_id"), "job_id"),
    }


@dataclass(frozen=True)
class Event:
    event_id: str
    kind: str
    created_at: float
    run_id: str | None = None
    session_id: str | None = None
    job_id: str | None = None
    urgency: str | None = None
    profile_hint_opaque: str | None = None

    @classmethod
    def make(cls, source, kind, created_at, **links):
        identity = json.dumps([kind, *source], separators=(",", ":"))
        event_id = hashlib.sha256(identity.encode()).hexdigest()[:32]
        return cls(event_id, kind, created_at, **links)


@dataclass(frozen=True)
class Delivery:
    delivery_id: str
    kind: str
    urgency: str
    profile_hint_opaque: str
    title: str
    payload: dict
    created_at: float
    session_id: str | None = None
    run_id: str | None = None
    job_id: str | None = None
    acked_at: float | None = None

    def to_dict(self) -> dict:
        return {
            "delivery_id": self.delivery_id,
            "event_id": self.delivery_id,
            "kind": self.kind,
            "urgency": self.urgency,
            "profile_hint_opaque": self.profile_hint_opaque,
            "title": self.title,
            "payload": self.payload,
            "created_at": self.created_at,
            "session_id": self.session_id,
            "run_id": self.run_id,
            "job_id": self.job_id,
            "acked_at": self.acked_at,
        }


@dataclass(frozen=True)
class Observation:
    updated_at: float
    events: tuple[Event, ...]
    # When supplied, the observed run owns the complete set of pending prompts.
    run_id: str | None = None
