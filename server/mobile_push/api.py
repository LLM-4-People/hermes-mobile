import asyncio
import hmac
import json
import re
import time
import uuid
from aiohttp import web
from .events import DELIVERY_ID_RE, opaque_profile_hint
from .store import Forbidden, TokenConflict

try:
    from server.logging_util import get_logger, safe_reason
except ImportError:
    from logging_util import get_logger, safe_reason

log = get_logger("push")


BASE = "/api/mobile/push"
DELIVERIES_BASE = "/api/mobile/deliveries"
DEVICE_MAX_BODY_BYTES = 8192
DELIVERY_MAX_BODY_BYTES = 147_456
KEY = re.compile("[a-f0-9]{64}")
TOKEN = re.compile("[!-~]{1,4096}")


def bearer(request):
    prefix, _, value = request.headers.get("Authorization", "").partition(" ")
    if prefix.lower() != "bearer" or not value or not value.isascii() or len(value) > 8192:
        raise web.HTTPUnauthorized()
    return value


def registration(settings, data):
    if not isinstance(data, dict):
        raise ValueError("Invalid enrollment")
    key = data.get("management_key")
    if key is not None and (not isinstance(key, str) or not KEY.fullmatch(key)):
        raise ValueError("Invalid management key")
    provider = data.get("provider", "fcm")
    fields = data.keys() - {"management_key", "provider"}
    if provider == "fcm" and settings.firebase:
        token = data.get("token")
        if fields != {"token"} or not isinstance(token, str) or not TOKEN.fullmatch(token):
            raise ValueError("Invalid FCM registration")
        return {"management_key": key, "token": token}
    if provider == "unifiedpush" and settings.webpush:
        if not fields and key:
            return {"management_key": key, "reserve": True}
        if fields == {"subscription"}:
            endpoint, keys = settings.webpush.target(data["subscription"])
            return {"management_key": key, "token": endpoint, "webpush_keys": keys, "provider": provider}
    raise ValueError("Unsupported registration")


def build_app(settings, store, changed=None):
    @web.middleware
    async def private_response(request, handler):
        try:
            response = await handler(request)
        except web.HTTPException as exc:
            if exc.status >= 400:
                log.warning("push_http_rejected status=%d reason=%s", exc.status, safe_reason(exc))
            exc.headers["Cache-Control"] = "no-store"
            raise
        response.headers["Cache-Control"] = "no-store"
        return response

    app = web.Application(client_max_size=DELIVERY_MAX_BODY_BYTES, middlewares=[private_response])
    profile_hint = opaque_profile_hint(settings.run_scope)

    def authorize(request):
        if not hmac.compare_digest(bearer(request), settings.profile_key):
            raise web.HTTPUnauthorized()

    async def discover(request):
        authorize(request)
        return web.json_response(settings.discovery())

    async def device(request):
        credential = bearer(request)
        profile = hmac.compare_digest(credential, settings.profile_key)
        if not profile and not KEY.fullmatch(credential):
            raise web.HTTPUnauthorized()
        ident = request.match_info["id"]
        try:
            parsed = uuid.UUID(ident)
        except ValueError:
            raise web.HTTPBadRequest() from None
        if parsed.version != 4 or str(parsed) != ident:
            raise web.HTTPBadRequest()
        binding = {}
        if request.method == "PUT":
            if request.content_type != "application/json":
                raise web.HTTPUnsupportedMediaType()
            raw = await request.read()
            if len(raw) > DEVICE_MAX_BODY_BYTES:
                raise web.HTTPRequestEntityTooLarge(max_size=DEVICE_MAX_BODY_BYTES, actual_size=len(raw))
            try:
                binding = registration(settings, json.loads(raw.decode("utf-8")))
            except (ValueError, TypeError, UnicodeDecodeError):
                raise web.HTTPBadRequest() from None
        try:
            await asyncio.to_thread(store.device, ident, credential, profile=profile, now=time.time(), **binding)
        except Forbidden:
            raise web.HTTPUnauthorized() from None
        except TokenConflict:
            raise web.HTTPConflict() from None
        return web.Response(status=204)

    async def events(request):
        authorize(request)
        ident = request.match_info.get("id")
        if ident is not None:
            if not DELIVERY_ID_RE.fullmatch(ident):
                raise web.HTTPNotFound()
            result = await asyncio.to_thread(store.events, event_id=ident)
            if result is None:
                raise web.HTTPNotFound()
        else:
            try:
                after = int(request.query.get("after", "0"))
                limit = int(request.query.get("limit", "50"))
            except ValueError:
                raise web.HTTPBadRequest() from None
            if after < 0 or after > 2**63 - 1 or not 1 <= limit <= 100:
                raise web.HTTPBadRequest()
            result = await asyncio.to_thread(store.events, after=after, limit=limit)
        return web.json_response(result)

    async def create_delivery(request):
        authorize(request)
        if request.content_type != "application/json":
            raise web.HTTPUnsupportedMediaType()
        try:
            body = await request.json()
        except (ValueError, TypeError, UnicodeDecodeError):
            raise web.HTTPBadRequest() from None
        try:
            result, created = await asyncio.to_thread(
                store.create_delivery, body, profile_hint_opaque=profile_hint, now=time.time()
            )
        except (ValueError, TypeError):
            raise web.HTTPBadRequest() from None
        if created and changed is not None:
            changed.set()
        return web.json_response(result, status=201 if created else 200)

    async def deliveries(request):
        authorize(request)
        ident = request.match_info.get("id")
        if ident is not None:
            if not DELIVERY_ID_RE.fullmatch(ident):
                raise web.HTTPNotFound()
            result = await asyncio.to_thread(store.get_delivery, ident)
            if result is None:
                raise web.HTTPNotFound()
            return web.json_response(result)
        try:
            after = int(request.query.get("after", "0"))
            limit = int(request.query.get("limit", "50"))
            since_raw = request.query.get("since")
            since = float(since_raw) if since_raw is not None else None
        except ValueError:
            raise web.HTTPBadRequest() from None
        if after < 0 or after > 2**63 - 1 or not 1 <= limit <= 100:
            raise web.HTTPBadRequest()
        if since is not None and (since < 0 or since != since):
            raise web.HTTPBadRequest()
        include_raw = request.query.get("include_acked", "true").lower()
        if include_raw not in {"true", "false"}:
            raise web.HTTPBadRequest()
        result = await asyncio.to_thread(
            store.list_deliveries, after=after, limit=limit, include_acked=(include_raw == "true"), since=since
        )
        return web.json_response(result)

    async def ack_delivery(request):
        authorize(request)
        ident = request.match_info["id"]
        if not DELIVERY_ID_RE.fullmatch(ident):
            raise web.HTTPNotFound()
        result = await asyncio.to_thread(store.ack_delivery, ident, now=time.time())
        if result is None:
            raise web.HTTPNotFound()
        return web.json_response(result)

    app.router.add_get(BASE, discover)
    app.router.add_put(BASE + "/devices/{id}", device)
    app.router.add_delete(BASE + "/devices/{id}", device)
    app.router.add_get(BASE + "/events", events)
    app.router.add_get(BASE + "/events/{id}", events)
    for prefix in (BASE + "/deliveries", DELIVERIES_BASE):
        app.router.add_post(prefix, create_delivery)
        app.router.add_get(prefix, deliveries)
        app.router.add_get(prefix + "/{id}", deliveries)
        app.router.add_post(prefix + "/{id}/ack", ack_delivery)
    return app
