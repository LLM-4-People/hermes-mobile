"""Plugin entry point for the Hermes API-server adapter on a dedicated profile endpoint."""
import asyncio
from contextlib import closing
import logging
from pathlib import Path
from aiohttp import web

from . import store
from .lineage import session_segments

try:
    from server.logging_util import get_logger, safe_reason
except ImportError:
    def get_logger(component):
        return logging.getLogger(f"hermes.mobile.{component}")
    def safe_reason(exc):
        return type(exc).__name__

log = get_logger("artifacts")


def mount(app, adapter):
    from hermes_constants import get_hermes_home
    register_routes(app, adapter._check_auth, get_hermes_home)


def register(ctx):
    ctx.register_platform_handler('api_server', mount)
    ctx.register_skill('publish', Path(__file__).with_name('SKILL.md'),
                       description='Publish a completed workspace file in this mobile discussion.')


def register_routes(app, authorize, profile_home, resolve=session_segments):
    def scope(request):
        refusal = authorize(request)
        if refusal is not None:
            log.warning("artifacts_request_rejected status=401 reason=unauthorized")
            raise web.HTTPUnauthorized(headers={'Cache-Control': 'no-store'})
        return profile_home(), request.match_info['session']

    async def listing(request):
        home, session = scope(request)
        try:
            before = store.decode_cursor(request.query.get('before'))
            limit = int(request.query.get('limit', 50))
            if not 1 <= limit <= 100:
                raise ValueError
            segments = await asyncio.to_thread(resolve, home, session)
            data = await asyncio.to_thread(store.inventory, home, segments, before, limit)
        except ValueError as exc:
            log.warning("artifacts_listing_rejected status=400 reason=%s", safe_reason(exc))
            raise web.HTTPBadRequest() from None
        return web.json_response(data, headers={'Cache-Control': 'no-store'})

    async def content(request):
        home, session = scope(request)
        try:
            segments = await asyncio.to_thread(resolve, home, session)
            conn, blob, row = await asyncio.to_thread(store.download, home, segments, request.match_info['id'])
        except (ValueError, FileNotFoundError) as exc:
            log.warning("artifacts_download_rejected status=404 reason=%s", safe_reason(exc))
            raise web.HTTPNotFound() from None
        with closing(conn), closing(blob):
            reply = web.StreamResponse(headers={
                'Content-Type': 'application/octet-stream', 'Content-Disposition': 'attachment',
                'Content-Length': str(row['size']), 'Cache-Control': 'no-store',
                'X-Content-Type-Options': 'nosniff'})
            await reply.prepare(request)
            while chunk := await asyncio.to_thread(blob.read, store.BLOCK):
                await reply.write(chunk)
            await reply.write_eof()
            return reply

    path = '/api/mobile/files/{session}'
    app.router.add_get(path, listing, allow_head=False)
    app.router.add_get(path + '/{id}', content, allow_head=False)
