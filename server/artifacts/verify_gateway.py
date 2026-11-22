"""Run inside the fork's Python environment to verify discovery, auth and real binary delivery."""
import asyncio
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile


async def verify(home):
    from aiohttp import ClientSession
    from gateway.config import PlatformConfig
    from gateway.platforms.api_server import APIServerAdapter
    from hermes_cli.plugins import discover_plugins, get_plugin_manager

    plugins = home / 'plugins' / 'mobile_artifacts'
    shutil.copytree(Path(__file__).parent, plugins, ignore=shutil.ignore_patterns('__pycache__'))
    (home / 'config.yaml').write_text('plugins:\n  enabled: [mobile_artifacts]\n', encoding='utf-8')
    key = secrets.token_hex(24)
    os.environ['API_SERVER_KEY'] = key
    discover_plugins()
    assert get_plugin_manager().find_plugin_skill('mobile_artifacts:publish') == plugins / 'SKILL.md'
    adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={'key': key, 'port': 0}))
    assert await adapter.connect()
    try:
        addr = adapter._site._server.sockets[0].getsockname()
        workspace = home / 'workspace'
        workspace.mkdir()
        payload = bytes(range(256)) * 499
        (workspace / 'report.bin').write_bytes(payload)
        cmd = [sys.executable, str(plugins / 'publish.py'), '--session', 'fixture', 'report.bin']
        ident = subprocess.check_output(cmd, cwd=workspace, text=True).strip()
        listing = f'http://127.0.0.1:{addr[1]}/api/mobile/files/fixture'
        async with ClientSession() as client:
            assert (await client.get(listing)).status == 401
            headers = {'Authorization': 'Bearer ' + key}
            async with client.get(listing, headers=headers) as result:
                assert result.status == 200, result.status
                assert (await result.json())['data'][0]['id'] == ident
            async with client.get(listing + '/' + ident, headers=headers) as result:
                assert result.status == 200 and await result.read() == payload
        print('Native gateway plugin authentication, listing and binary round trip passed.')
    finally:
        await adapter.disconnect()


if __name__ == '__main__':
    with tempfile.TemporaryDirectory() as tmp:
        os.environ['HERMES_HOME'] = tmp
        asyncio.run(verify(Path(tmp)))
