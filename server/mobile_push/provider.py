"""Shared provider wire semantics for the private event outbox."""
from email.utils import parsedate_to_datetime
from enum import Enum
import time
from .events import PROMPT_KINDS


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
    return {"event_id": item["event_id"], "installation_id": item["device_id"], "kind": item["kind"]}
