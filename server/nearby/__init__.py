"""Profile-local authenticated restaurant lookups for Hermes Mobile."""

import json
import logging
import os

from aiohttp import web

from .provider import Overpass, Unavailable, bounded_bytes
from .records import accepted_center

try:
    from server.logging_util import get_logger, safe_reason
except ImportError:
    def get_logger(component):
        return logging.getLogger(f"hermes.mobile.{component}")
    def safe_reason(exc):
        return type(exc).__name__

log = get_logger("nearby")


def register(ctx):
    ctx.register_platform_handler("api_server", mount)


def mount(app, adapter):
    provider = Overpass(os.environ.get("HERMES_MOBILE_OVERPASS_URL"))
    register_routes(app, adapter._check_auth, provider)


def register_routes(app, authorize, provider):
    def reply(data, status=200, extra=None):
        return web.json_response(data, status=status, headers={"Cache-Control": "no-store", **(extra or {})})

    async def nearby(request):
        if authorize(request) is not None:
            log.warning("nearby_request_rejected status=401 reason=unauthorized")
            return reply({"error": "unauthorized"}, 401)
        if request.query or request.content_type != "application/json":
            log.warning("nearby_request_rejected status=400 reason=invalid_content_or_query")
            return reply({"error": "invalid_area"}, 400)
        try:
            lat, lon = accepted_center(json.loads(await bounded_bytes(request.content, 256)))
        except (ValueError, RecursionError) as exc:
            log.warning("nearby_request_rejected status=400 reason=%s", safe_reason(exc))
            return reply({"error": "invalid_area"}, 400)
        try:
            result = await provider.search(lat, lon)
        except Unavailable as exc:
            log.warning("nearby_request_unavailable status=503 retry_after=%s", exc.retry_after)
            headers = {"Retry-After": str(exc.retry_after)} if exc.retry_after is not None else {}
            return reply({"error": "places_unavailable"}, 503, headers)
        return reply(result)

    app.router.add_post("/api/mobile/places/nearby", nearby)
