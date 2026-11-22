"""Bounded requests to the profile operator's Overpass endpoint."""

import asyncio
import json
import logging
import math
import time
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from urllib.parse import urlsplit

from aiohttp import ClientError, ClientSession, ClientTimeout, DummyCookieJar

from .records import RADIUS_M, places

try:
    from server.logging_util import get_logger, safe_reason
except ImportError:
    def get_logger(component):
        return logging.getLogger(f"hermes.mobile.{component}")
    def safe_reason(exc):
        return type(exc).__name__

log = get_logger("nearby")


MAX_BYTES = 4 * 1024 * 1024


class Unavailable(Exception):
    def __init__(self, retry_after=None):
        self.retry_after = retry_after


def endpoint(value):
    try:
        url = urlsplit(value or "")
        scheme_ok = url.scheme == "https" or (url.scheme == "http" and url.hostname in ("127.0.0.1", "::1"))
        if (
            not scheme_ok
            or not url.hostname
            or url.username is not None
            or url.password is not None
            or url.query
            or url.fragment
            or not url.path.startswith("/")
            or url.netloc.endswith(":")
            or url.port == 0
            or any(c.isspace() or ord(c) < 32 or c == "\\" for c in value)
        ):
            return None
        return value
    except (ValueError, UnicodeError):
        return None


def retry_delay(value):
    if not value:
        return 0
    if value.isascii() and value.isdigit() and len(value) <= 9:
        return int(value)
    try:
        date = parsedate_to_datetime(value)
        return max(0, math.ceil((date - datetime.now(timezone.utc)).total_seconds()))
    except (ValueError, TypeError, OverflowError):
        return 0


async def bounded_bytes(stream, limit):
    body = bytearray()
    async for chunk in stream.iter_chunked(min(limit + 1, 64 * 1024)):
        if len(body) + len(chunk) > limit:
            raise ValueError("Content exceeds the configured bound")
        body.extend(chunk)
    return body


class Overpass:
    def __init__(self, endpoint_url):
        self.url = endpoint(endpoint_url)
        self.busy = asyncio.Lock()
        self.resume_at = 0.0

    async def search(self, lat, lon):
        if not self.url:
            log.warning("nearby_provider_unconfigured")
            raise Unavailable()
        remaining = self.resume_at - time.monotonic()
        if remaining > 0 or self.busy.locked():
            log.warning("nearby_provider_throttled retry_after=%d", max(1, math.ceil(remaining)))
            raise Unavailable(max(1, math.ceil(remaining)))
        query = (
            f'[out:json][timeout:12][maxsize:12582912];nwr["amenity"="restaurant"]["name"]'
            f"(around:{RADIUS_M},{lat:.2f},{lon:.2f});out center;"
        )
        async with self.busy:
            try:
                async with ClientSession(
                    timeout=ClientTimeout(total=20),
                    cookie_jar=DummyCookieJar(),
                    headers={"User-Agent": "Hermes-Mobile/1.0 (github.com/LLM-4-People/hermes-mobile)"},
                ) as client:
                    async with client.post(self.url, data={"data": query}, allow_redirects=False) as response:
                        if response.status != 200:
                            delay = (
                                retry_delay(response.headers.get("Retry-After"))
                                if response.status in (429, 503)
                                else 0
                            )
                            self.resume_at = time.monotonic() + delay
                            log.warning("nearby_upstream_status status=%d retry_after=%d", response.status, delay)
                            raise Unavailable(delay or None)
                        raw = await bounded_bytes(response.content, MAX_BYTES)
                return places(json.loads(raw), lat, lon)
            except (ClientError, TimeoutError, ValueError, RecursionError) as exc:
                log.warning("nearby_upstream_failed reason=%s", safe_reason(exc))
                raise Unavailable() from exc
