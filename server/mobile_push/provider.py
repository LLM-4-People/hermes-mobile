"""Shared provider wire semantics for the private event outbox."""
from email.utils import parsedate_to_datetime
import time


class ProviderError(Exception):
    def __init__(self, status, retry_after=0):
        super().__init__(f"Push HTTP {status}")
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
    return max(0, int(item["created_at"] + retention - now))


def opaque_notice(item):
    return {"event_id": item["event_id"], "installation_id": item["device_id"], "kind": item["kind"]}
