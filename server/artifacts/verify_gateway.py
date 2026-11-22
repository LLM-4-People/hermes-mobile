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
    from persistence_assembly import open_persistence, profile_scope
    from persistence.conversation.lineage import CompressionChild
    from persistence.conversation.messages import NewMessage
    from persistence.conversation.sessions import NewSession, SessionLineageKind
    from store import publish as physical_publish

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
        def publish(session):
            cmd = [sys.executable, str(plugins / 'publish.py'), '--session', session, 'report.bin']
            return subprocess.check_output(cmd, cwd=workspace, text=True).strip()

        def compress(db, parent, child):
            db.lineage.publish_compression_child(parent, CompressionChild(session_id=child, source='api_server'),
                [NewMessage(role='user', content='continued work')], require_compression_lease=False)

        with open_persistence(profile_scope(home=home)) as db:
            db.sessions.create(NewSession(session_id='fixture', source='api_server'))
            original = publish('fixture')
            compress(db, 'fixture', 'middle')
            previous = physical_publish(home, 'middle', workspace, 'report.bin')
            compress(db, 'middle', 'tip')
            latest = publish('tip')
            db.sessions.create(NewSession(session_id='fork', source='api_server', parent_session_id='fixture',
                                          lineage_kind=SessionLineageKind.BRANCH))
            branch_file = publish('fork')
            compress(db, 'fork', 'fork-tip')
            branch_tip_file = publish('fork-tip')

        listing = f'http://127.0.0.1:{addr[1]}/api/mobile/files'
        headers = {'Authorization': 'Bearer ' + key}
        async with ClientSession() as client:
            assert (await client.get(listing + '/tip')).status == 401
            for sid in ('fixture', 'middle', 'tip', 'fork', 'fork-tip'):
                expected = [branch_tip_file, branch_file] if 'fork' in sid else [latest, previous, original]
                actual = []
                params = {'limit': '1'}
                while True:
                    async with client.get(f'{listing}/{sid}', headers=headers, params=params) as reply:
                        assert reply.status == 200, reply.status
                        data = await reply.json()
                    actual.extend(row['id'] for row in data['data'])
                    if data['next'] is None:
                        break
                    params['before'] = data['next']
                assert actual == expected, (sid, actual, expected)
                for ident in actual:
                    async with client.get(f'{listing}/{sid}/{ident}', headers=headers) as reply:
                        assert reply.status == 200 and await reply.read() == payload
                foreign = original if 'fork' in sid else branch_file
                assert (await client.get(f'{listing}/{sid}/{foreign}', headers=headers)).status == 404
        print('Native gateway auth, binary transfer, paginated compression lineage, and distinct fork shelves passed.')
    finally:
        await adapter.disconnect()


if __name__ == '__main__':
    with tempfile.TemporaryDirectory() as tmp:
        os.environ['HERMES_HOME'] = tmp
        asyncio.run(verify(Path(tmp)))
