import asyncio
import base64
from dataclasses import replace
import json
import os
from pathlib import Path
import secrets
import tempfile
import time
import unittest
import uuid
from unittest.mock import patch

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec, utils
import http_ece

from server.mobile_push.api import BASE, build_app
from server.mobile_push.config import Settings
from server.mobile_push.events import Event
from server.mobile_push.provider import ProviderError
from server.mobile_push.transports import Transports
from server.mobile_push.webpush import WebPushConfig, base64url, public_bytes
from support import fixture


def unpad(value):
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


class UnifiedPush(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        settings, self.store = fixture(directory.name)
        self.requests = []
        self.reply = 201

        async def capture(request):
            self.requests.append((dict(request.headers), await request.read()))
            return web.Response(status=self.reply, headers={"Location": "/unregistered", "Retry-After": "90"})

        distributor = web.Application()
        distributor.router.add_post("/{path}", capture)
        self.server = TestServer(distributor)
        await self.server.start_server()
        self.addAsyncCleanup(self.server.close)
        self.origin = str(self.server.make_url("/")).rstrip("/")
        self.contact = "mailto:push@example.invalid"
        config = WebPushConfig.load(settings.state, self.origin, self.contact)
        self.settings = replace(settings, firebase={}, webpush=config)
        self.client = TestClient(TestServer(build_app(self.settings, self.store)))
        await self.client.start_server()
        self.addAsyncCleanup(self.client.close)
        self.senders = Transports(self.settings)
        self.addCleanup(self.senders.close)
        self.ident = str(uuid.uuid4())
        self.path = BASE + "/devices/" + self.ident
        self.owner = self.settings.profile_key
        self.cap = secrets.token_hex(32)
        self.private = ec.generate_private_key(ec.SECP256R1())
        self.auth = secrets.token_bytes(16)
        self.sub = {"endpoint": self.origin + "/up123456789aBc?up=1",
                    "keys": {"p256dh": base64url(public_bytes(self.private.public_key())), "auth": base64url(self.auth)}}
        self.body = {"provider": "unifiedpush", "subscription": self.sub}
        self.reserve = {"provider": "unifiedpush", "management_key": self.cap}

    async def put(self, credential, body):
        response = await self.client.put(self.path, json=body, headers={"Authorization": "Bearer " + credential})
        await response.read()
        return response.status

    def event(self, ident):
        now = time.time()
        event = Event.make([ident], "run_completed", now, session_id="account-private", run_id=ident)
        self.store.record([event])
        return event, self.store.claim(now + .1, 10)

    async def test_cold_callback_real_ciphertext_and_vapid(self):
        self.assertEqual(await self.put(self.cap, self.reserve), 401)
        self.assertEqual(await self.put(self.owner, self.reserve), 204)
        self.assertIsNone(self.event("before-binding")[1])
        self.assertEqual(await self.put(self.cap, self.body), 204)
        self.assertEqual(await self.put(self.owner, self.reserve), 204)
        event, item = self.event("after-binding")
        self.assertTrue(await asyncio.to_thread(self.senders.send, item))
        headers, ciphertext = self.requests.pop()
        self.assertEqual(headers["Content-Encoding"], "aes128gcm")
        self.assertLessEqual(int(headers["TTL"]), self.settings.retention)
        self.assertGreater(int(headers["TTL"]), 0)
        self.assertNotIn(b"after-binding", ciphertext)
        message = http_ece.decrypt(ciphertext, private_key=self.private, auth_secret=self.auth, version="aes128gcm")
        self.assertEqual(json.loads(message), {"event_id": event.event_id, "installation_id": self.ident,
                                               "kind": "run_completed"})
        token, encoded_key = headers["Authorization"].removeprefix("vapid t=").split(",k=")
        self.assertEqual(encoded_key, self.settings.webpush.public_key)
        header, payload, signature = token.split(".")
        self.assertEqual(json.loads(unpad(payload))["aud"], self.origin)
        raw = unpad(signature)
        proof = utils.encode_dss_signature(int.from_bytes(raw[:32], "big"), int.from_bytes(raw[32:], "big"))
        self.settings.webpush.identity.public_key.verify(proof, (header + "." + payload).encode(), ec.ECDSA(hashes.SHA256()))
        self.assertTrue(await asyncio.to_thread(self.senders.send, dict(item, created_at=0)))
        self.assertFalse(self.requests)

    async def test_origin_key_and_provider_validation(self):
        for endpoint in (self.origin + "/other?up=1", self.origin + "/up123456789aBc?up=1&extra=1",
                         self.origin + ".invalid/up123456789aBc?up=1", "https://elsewhere.invalid/up123456789aBc?up=1"):
            with self.subTest(endpoint=endpoint):
                invalid = dict(self.body, subscription=dict(self.sub, endpoint=endpoint), management_key=self.cap)
                self.assertEqual(await self.put(self.owner, invalid), 400)
        for field in ("p256dh", "auth"):
            wrong = dict(self.sub["keys"], **{field: base64url(bytes(65 if field == "p256dh" else 12))})
            self.assertEqual(await self.put(self.owner, dict(self.body, subscription=dict(self.sub, keys=wrong))), 400)
        self.assertEqual(await self.put(self.owner, {"token": "token", "management_key": self.cap}), 400)
        self.assertFalse(self.requests)

    async def test_rotation_revocation_and_refused_redirect(self):
        self.assertEqual(await self.put(self.owner, dict(self.body, management_key=self.cap)), 204)
        _, original = self.event("first")
        changed = dict(self.sub, keys=dict(self.sub["keys"], auth=base64url(secrets.token_bytes(16))))
        self.assertEqual(await self.put(self.cap, dict(self.body, subscription=changed)), 204)
        self.store.settle(original, unregistered=True)
        item = self.store.claim(time.time() + .5, 10)
        self.assertNotEqual(item["lease"], original["lease"])
        self.reply = 307
        with self.assertRaises(ProviderError) as failure:
            await asyncio.to_thread(self.senders.send, item)
        self.assertEqual(failure.exception.retry_after, 90)
        self.assertEqual(len(self.requests), 1)
        self.reply = 410
        self.assertFalse(await asyncio.to_thread(self.senders.send, item))
        self.store.settle(item, unregistered=True)
        self.assertEqual(await self.put(self.cap, self.body), 401)
        self.assertIsNone(self.event("revoked")[1])

    async def test_discovery_without_google_and_persistent_identity(self):
        env = {"HERMES_HOME": str(self.settings.home), "MOBILE_PUSH_STATE": str(self.settings.state),
               "API_SERVER_KEY": self.owner, "MOBILE_UNIFIEDPUSH_ORIGIN": self.origin,
               "MOBILE_VAPID_SUBJECT": self.contact}
        with patch.dict(os.environ, env, clear=True):
            loaded = Settings.load()
            self.assertEqual(loaded.webpush.public_key, self.settings.webpush.public_key)
            self.assertEqual(loaded.discovery()["providers"], ["unifiedpush"])
            self.assertNotIn("firebase", loaded.discovery())
            with patch.dict(os.environ, MOBILE_FIREBASE_PROJECT_ID="partial"):
                with self.assertRaisesRegex(ValueError, "MOBILE_FIREBASE_SENDER_ID"):
                    Settings.load()
        response = await self.client.get(BASE, headers={"Authorization": "Bearer " + self.owner})
        self.assertEqual(await response.json(), loaded.discovery())
        populated = replace(loaded, firebase={"projectId": "test"})
        self.assertEqual(populated.discovery()["providers"], ["fcm", "unifiedpush"])
        self.assertEqual(Path(loaded.state, "vapid.pem").stat().st_mode & 0o077, 0)
