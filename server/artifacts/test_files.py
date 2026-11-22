import hashlib
import logging
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from . import register_routes, store


class FileRoutesTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.home = Path(tmp.name)
        self.work = self.home / 'workspace'
        self.work.mkdir()
        self.body = bytes(range(256)) * 1300
        (self.work / 'résultat.bin').write_bytes(self.body)
        self.ident = self.publish()
        app = web.Application()
        register_routes(app, lambda r: None if r.headers.get('Authorization') == 'Bearer fixture'
                        else web.Response(status=401), lambda: self.home, lambda home, session: (session,))
        self.client = TestClient(TestServer(app))
        await self.client.start_server()
        self.addAsyncCleanup(self.client.close)

    def publish(self):
        return store.publish(self.home, 's1', self.work, 'résultat.bin')

    async def get(self, suffix=''):
        return await self.client.get('/api/mobile/files/' + suffix, headers={'Authorization': 'Bearer fixture'})

    async def test_binary_integrity_session_separation_and_auth(self):
        anonymous = await self.client.get('/api/mobile/files/s1')
        self.assertEqual(anonymous.status, 401)
        self.assertEqual((await self.client.get('/api/mobile/files/s1/' + self.ident)).status, 401)
        listing = await (await self.get('s1')).json()
        self.assertEqual(listing['max_artifact_bytes'], store.DEFAULT_LIMIT)
        self.assertEqual(listing['data'][0]['sha256'], hashlib.sha256(self.body).hexdigest())
        self.assertEqual(listing['data'][0]['name'], 'résultat.bin')
        self.assertIsNone(listing['next'])
        reply = await self.get('s1/' + self.ident)
        self.assertEqual(reply.headers['Content-Disposition'], 'attachment')
        self.assertEqual(await reply.read(), self.body)
        self.assertEqual((await self.get('s2/' + self.ident)).status, 404)
        self.assertEqual((await (await self.get('s2')).json())['data'], [])

    async def test_cursor_is_stable_across_insertions(self):
        newest = self.publish()
        page = await (await self.get('s1?limit=1')).json()
        self.assertEqual(page['data'][0]['id'], newest)
        self.publish()
        older = await (await self.get(f's1?limit=1&before={page["next"]}')).json()
        self.assertEqual([i['id'] for i in older['data']], [self.ident])
        self.assertIsNone(older['next'])
        self.assertEqual((await self.get('s1?limit=0')).status, 400)

    async def test_oversize_or_indirect_source_is_never_published(self):
        (self.work / 'link').symlink_to(self.work / 'résultat.bin')
        (self.work / 'folder').symlink_to(self.work)
        os.mkfifo(self.work / 'pipe')
        for path in ('../secret', '/tmp/abs', 'link', 'folder/résultat.bin', 'pipe'):
            with self.subTest(path=path), self.assertRaises((ValueError, OSError)):
                store.publish(self.home, 's1', self.work, path)
        with patch.dict(os.environ, {'MOBILE_ARTIFACT_MAX_BYTES': '10'}), self.assertRaises(ValueError):
            self.publish()
        self.assertEqual(len((await (await self.get('s1')).json())['data']), 1)

    async def test_empty_file_round_trip_and_profile_boundary(self):
        (self.work / 'empty.txt').touch()
        empty = store.publish(self.home, 's1', self.work, 'empty.txt')
        self.assertEqual(await (await self.get('s1/' + empty)).read(), b'')
        other = self.home / 'other-profile'
        other.mkdir()
        with self.assertLogs('hermes.mobile.artifacts', logging.WARNING) as logs:
            self.assertEqual((await self.client.get('/api/mobile/files/s1')).status, 401)
            self.assertEqual((await self.get('s1?limit=0')).status, 400)
            self.assertEqual((await self.get('s2/' + self.ident)).status, 404)
        joined = '\n'.join(logs.output)
        self.assertIn('artifacts_request_rejected', joined)
        self.assertIn('artifacts_listing_rejected', joined)
        self.assertIn('artifacts_download_rejected', joined)
        self.assertNotIn('Bearer fixture', joined)
        with patch.dict(os.environ, {'MOBILE_ARTIFACT_MAX_BYTES': '1024'}):
            bounded = await (await self.get('s1')).json()
            self.assertEqual(bounded['max_artifact_bytes'], 1024)
        self.assertEqual(store.inventory(other, ('s1',), store.decode_cursor(None), 10)['data'], [])
        with self.assertRaises(FileNotFoundError):
            store.download(other, ('s1',), empty)
