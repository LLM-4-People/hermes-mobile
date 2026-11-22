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

from .config import (
    DEFAULT_CATEGORY,
    DEFAULT_LIMIT,
    DEFAULT_RADIUS_M,
    HTTP_TIMEOUT_S,
    MAX_RESPONSE_BYTES,
    USER_AGENT,
    overpass_query,
)
from .records import places


MAX_BYTES = MAX_RESPONSE_BYTES
LOGGER = logging.getLogger("hermes.mobile.nearby")


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

    async def search(self, lat, lon, category=DEFAULT_CATEGORY, radius_m=DEFAULT_RADIUS_M, limit=DEFAULT_LIMIT):
        if not self.url:
            LOGGER.warning("nearby.provider_unconfigured")
            raise Unavailable()
        remaining = self.resume_at - time.monotonic()
        if remaining > 0 or self.busy.locked():
            LOGGER.warning("nearby.provider_throttled")
            raise Unavailable(max(1, math.ceil(remaining)))
        query = overpass_query(category, radius_m, lat, lon)
        async with self.busy:
            try:
                async with ClientSession(
                    timeout=ClientTimeout(total=HTTP_TIMEOUT_S),
                    cookie_jar=DummyCookieJar(),
                    headers={"User-Agent": USER_AGENT},
                ) as client:
                    async with client.post(self.url, data={"data": query}, allow_redirects=False) as response:
                        if response.status != 200:
                            delay = (
                                retry_delay(response.headers.get("Retry-After"))
                                if response.status in (429, 503)
                                else 0
                            )
                            self.resume_at = time.monotonic() + delay
                            LOGGER.warning("nearby.upstream_status status=%s", response.status)
                            raise Unavailable(delay or None)
                        raw = await bounded_bytes(response.content, MAX_BYTES)
                return places(json.loads(raw), lat, lon, category=category, radius_m=radius_m, limit=limit)
            except (ClientError, TimeoutError, ValueError, RecursionError) as exc:
                LOGGER.warning("nearby.upstream_failed error=%s", type(exc).__name__)
                raise Unavailable() from exc
