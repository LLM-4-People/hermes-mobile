"""Shared provider wire semantics for the private event outbox."""
from email.utils import parsedate_to_datetime
from enum import Enum
import time
from .events import DEFAULT_URGENCY, DELIVERY_KINDS, PROMPT_KINDS, URGENCY_LEVELS


class Failure(Enum):
    HTTP = "http_error"
    ORIGIN = "origin_mismatch"
    SUBSCRIPTION = "invalid_subscription"
    DISABLED = "provider_disabled"


class ProviderError(Exception):
    def __init__(self, status=None, retry_after=0, *, reason=Failure.HTTP):
        super().__init__(reason.value)
        self.status = status
        self.reason = reason
        self.retry_after = retry_after


def retry_seconds(header):
    if not header:
        return 0
    try:
        return max(0, int(header))
    except ValueError:
        try:
            return max(0, parsedate_to_datetime(header).timestamp() - time.time())
        except (ValueError, TypeError, OverflowError):
            return 0


def lifetime(item, retention, now):
    remaining = max(0, item["created_at"] + retention - now)
    # Android retains out-of-order dismissal markers longer than a prompt can
    # remain in either transport after its last send attempt.
    return min(remaining, 28 * 86400) if item["kind"] in PROMPT_KINDS else remaining


def opaque_notice(item):
    """Build the strictly allowlisted, zero-leak wire wakeup payload.

    Titles, snippets, file paths, payloads, and session/run IDs MUST NEVER enter
    the push wakeup message sent through FCM or UnifiedPush relays.
    """
    base = {
        "event_id": item["event_id"],
        "installation_id": item["device_id"],
        "kind": item["kind"],
    }
    if item["kind"] in DELIVERY_KINDS:
        urgency = item.get("urgency")
        if urgency not in URGENCY_LEVELS:
            urgency = DEFAULT_URGENCY
        base["urgency"] = urgency
        base["profile_hint_opaque"] = item.get("profile_hint_opaque") or ""
    return base


def fcm_priority(item) -> str:
    urgency = item.get("urgency")
    return "high" if urgency in (None, "high") else "normal"


def webpush_urgency(item) -> str:
    urgency = item.get("urgency")
    if urgency == "silent":
        return "low"
    if urgency == "normal":
        return "normal"
    return "high"
