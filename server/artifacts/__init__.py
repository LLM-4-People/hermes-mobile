"""Plugin entry point for the Hermes API-server adapter on a dedicated profile endpoint."""
import asyncio
from contextlib import closing
from pathlib import Path
from aiohttp import web

from . import store


def mount(app, adapter):
    from hermes_constants import get_hermes_home
    register_routes(app, adapter._check_auth, get_hermes_home)


def register(ctx):
    ctx.register_platform_handler('api_server', mount)
    ctx.register_skill('publish', Path(__file__).with_name('SKILL.md'),
                       description='Publish a completed workspace file in this mobile discussion.')


def register_routes(app, authorize, profile_home):
    def scope(request):
        refusal = authorize(request)
        if refusal is not None:
            raise web.HTTPUnauthorized(headers={'Cache-Control': 'no-store'})
        return profile_home(), request.match_info['session']

    async def listing(request):
        home, session = scope(request)
        try:
            before = int(request.query.get('before', 2**63 - 1))
            limit = int(request.query.get('limit', 50))
            if not 1 <= limit <= 100 or not 1 <= before <= 2**63 - 1:
                raise ValueError
            data = await asyncio.to_thread(store.inventory, home, session, before, limit)
        except ValueError:
            raise web.HTTPBadRequest() from None
        return web.json_response(data, headers={'Cache-Control': 'no-store'})

    async def content(request):
        home, session = scope(request)
        try:
            conn, blob, row = await asyncio.to_thread(store.download, home, session, request.match_info['id'])
        except (ValueError, FileNotFoundError):
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
