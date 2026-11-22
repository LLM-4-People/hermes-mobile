import secrets
import tempfile
import unittest
import uuid
from aiohttp.test_utils import TestClient, TestServer
from server.mobile_push.api import BASE, build_app
from server.mobile_push.events import Event
from support import fixture


class PushApi(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        root = tempfile.TemporaryDirectory()
        self.addCleanup(root.cleanup)
        self.settings, self.store = fixture(root.name)
        self.client = TestClient(TestServer(build_app(self.settings, self.store)))
        await self.client.start_server()
        self.addAsyncCleanup(self.client.close)
        self.owner = self.settings.profile_key
        self.ident = str(uuid.uuid4())
        self.path = f"{BASE}/devices/{self.ident}"
        self.key = secrets.token_hex(32)
        self.enrol = {"token": "registration-token", "management_key": self.key}

    async def req(self, method, path, key=None, body=None):
        return await self.client.request(method, path, json=body,
                                         headers={"Authorization": "Bearer " + key} if key else {})

    async def test_discovery_and_device_only_rotation_and_removal(self):
        self.assertEqual((await self.req("GET", BASE)).status, 401)
        r = await self.req("GET", BASE, self.owner)
        self.assertEqual(await r.json(), {"provider": "fcm", "firebase": self.settings.firebase})
        self.assertEqual(r.headers["Cache-Control"], "no-store")
        for _ in range(2):
            self.assertEqual((await self.req("PUT", self.path, self.owner, self.enrol)).status, 204)
        self.assertEqual((await self.req("PUT", self.path, self.key, {"token": "new-token"})).status, 204)
        self.assertEqual((await self.req("PUT", self.path, self.key, self.enrol)).status, 401)
        self.assertEqual((await self.req("GET", BASE, self.key)).status, 401)
        self.assertEqual((await self.req("GET", BASE + "/events", self.key)).status, 401)
        other = BASE + "/devices/" + str(uuid.uuid4())
        self.assertEqual((await self.req("DELETE", other, self.key)).status, 401)
        self.assertEqual((await self.req("PUT", other, self.owner,
                                          {"token":"new-token", "management_key": self.key})).status, 409)
        self.assertEqual((await self.req("DELETE", self.path, self.key)).status, 204)
        self.assertEqual((await self.req("DELETE", self.path, self.key)).status, 401)
        self.assertEqual((await self.req("DELETE", self.path, self.owner)).status, 204)

    async def test_rejects_invalid_registration_fields_and_sizes(self):
        for body in [[], {}, {"token": 5}, {"token":"a\nb"}, {"token":"a", "management_key":"wrong"},
                     {"token":"a", "url":"https://example.com"}]:
            with self.subTest(body=body):
                self.assertEqual((await self.req("PUT", self.path, self.owner, body)).status, 400)
        self.assertEqual((await self.req("PUT", self.path, self.owner, {"token": "x"*9000})).status, 413)
        self.assertEqual((await self.req("PUT", self.path, self.owner, {"token":"token"})).status, 401)
        self.assertEqual((await self.req("DELETE", BASE+"/devices/bad", self.owner)).status, 400)

    async def test_list_cursor_and_event_resolution(self):
        events = [Event.make([n], "run_completed", n, session_id="session") for n in (100, 101, 99)]
        self.store.record(events)
        url = BASE + "/events"
        self.assertEqual((await self.req("GET", url, self.key)).status, 401)
        page = await (await self.req("GET", url+"?limit=2", self.owner)).json()
        self.assertTrue(page["has_more"])
        self.assertEqual([e["event_id"] for e in page["events"]], [e.event_id for e in events[:2]])
        tail = await (await self.req("GET", f"{url}?after={page['next_cursor']}", self.owner)).json()
        self.assertFalse(tail["has_more"])
        single = await (await self.req("GET", url + "/" + events[2].event_id, self.owner)).json()
        self.assertEqual(tail["events"], [single])
        for path in ["?limit=-1", "?limit=101", "?after="+str(2**63), "?after=abc"]:
            self.assertEqual((await self.req("GET", url + path, self.owner)).status, 400)
