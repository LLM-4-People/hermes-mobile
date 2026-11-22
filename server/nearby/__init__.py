"""Profile-local authenticated generalized nearby place lookups for Hermes Mobile."""

import json
import logging
import os

from aiohttp import web

from .config import InvalidCategory, MAX_REQUEST_BYTES, OVERPASS_URL_ENV
from .provider import Overpass, Unavailable, bounded_bytes
from .records import accepted_request


LOGGER = logging.getLogger("hermes.mobile.nearby")


def register(ctx):
    ctx.register_platform_handler("api_server", mount)


def mount(app, adapter):
    provider = Overpass(os.environ.get(OVERPASS_URL_ENV))
    register_routes(app, adapter._check_auth, provider)


def register_routes(app, authorize, provider):
    def reply(data, status=200, extra=None):
        return web.json_response(data, status=status, headers={"Cache-Control": "no-store", **(extra or {})})

    async def nearby(request):
        if authorize(request) is not None:
            LOGGER.warning("nearby.unauthorized")
            return reply({"error": "unauthorized"}, 401)
        if request.query or request.content_type != "application/json":
            LOGGER.warning("nearby.invalid_content")
            return reply({"error": "invalid_area"}, 400)
        try:
            payload = json.loads(await bounded_bytes(request.content, MAX_REQUEST_BYTES))
            lat, lon, category, radius_m, limit = accepted_request(payload)
        except InvalidCategory:
            LOGGER.warning("nearby.invalid_category")
            return reply({"error": "invalid_category"}, 400)
        except (ValueError, RecursionError):
            LOGGER.warning("nearby.invalid_area")
            return reply({"error": "invalid_area"}, 400)
        try:
            result = await provider.search(lat, lon, category=category, radius_m=radius_m, limit=limit)
        except Unavailable as exc:
            headers = {"Retry-After": str(exc.retry_after)} if exc.retry_after is not None else {}
            return reply({"error": "places_unavailable"}, 503, headers)
        return reply(result)

    app.router.add_post("/api/mobile/places/nearby", nearby)
