"""Check the production Hermes plugin entry point against a dated OSM capture on loopback."""

import asyncio
import json
import os
import runpy
from pathlib import Path
import secrets
import shutil
import tempfile

from aiohttp import ClientSession, web
from aiohttp.test_utils import TestServer


ROOT = Path(__file__).resolve().parents[1]


async def verify(home):
    import hermes_constants
    from gateway.config import PlatformConfig
    from gateway.platforms.api_server import APIServerAdapter
    from hermes_cli.plugins import discover_plugins

    capture = (ROOT / "server/tests/fixtures/montreal-restaurants.json").read_bytes()
    queries = []

    async def interpreter(request):
        queries.append((request.path_qs, (await request.post())["data"]))
        return web.Response(body=capture, content_type="application/json")

    source = web.Application()
    source.router.add_post("/interpreter", interpreter)
    async with TestServer(source) as upstream:
        os.environ["HERMES_MOBILE_OVERPASS_URL"] = str(upstream.make_url("/interpreter"))
        key = secrets.token_hex(24)
        os.environ["API_SERVER_KEY"] = key
        shutil.copytree(
            ROOT / "server/nearby", home / "plugins/mobile_nearby", ignore=shutil.ignore_patterns("__pycache__")
        )
        seed = runpy.run_path(str(ROOT / "deploy/seed_account.py"))["seed_account"]
        seed(home, Path(hermes_constants.__file__).with_name("cli-config.yaml.example"))
        discover_plugins()
        adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={"key": key, "port": 0}))
        assert await adapter.connect()
        try:
            port = adapter._site._server.sockets[0].getsockname()[1]
            url = f"http://127.0.0.1:{port}/api/mobile/places/nearby"
            async with ClientSession() as client:
                for token, expected in (("another-profile", 401), (key, 200)):
                    async with client.post(
                        url,
                        headers={"Authorization": "Bearer " + token},
                        json={"latitude": 45.51, "longitude": -73.57},
                    ) as reply:
                        assert reply.status == expected, reply.status
                        if expected == 200:
                            records = await reply.json()
                assert len(queries) == 1 and queries[0][0] == "/interpreter"
                assert [p["osm_id"] for p in records["places"][:2]] == [11280151110, 6732094290]
                print(
                    json.dumps(
                        {
                            "source_date": records["source_date"],
                            "count": len(records["places"]),
                            "first": records["places"][0],
                        },
                        ensure_ascii=False,
                    )
                )
        finally:
            await adapter.disconnect()


if __name__ == "__main__":
    with tempfile.TemporaryDirectory() as directory:
        os.environ["HERMES_HOME"] = directory
        asyncio.run(verify(Path(directory)))
