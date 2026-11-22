import base64
import json
from pathlib import Path
import tempfile
import time
import unittest

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, utils

from server.mobile_push.webpush import WebPushConfig


def decode(value):
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


class VapidTests(unittest.TestCase):
    def test_signed_authorization_for_canonical_origins_and_contact_uris(self):
        cases = (
            ("HTTPS://[2001:0DB8:0:0::1]:8443", "https://[2001:db8::1]:8443",
             "https://support.example.org/contact/webpush/?service=ntfy"),
            ("https://[::1]:443", "https://[::1]", "https://[2001:db8::2]:9443/support/"),
            ("https://[::1]:8443", "https://[::1]:8443", "mailto:ops@example.org"),
            ("https://push.example.org", "https://push.example.org", "https://support.example.org/contact/"),
            ("https://push.example.org", "https://push.example.org", "mailto:ops@example.org,help@example.org"),
            ("https://Push.Example.org:443", "https://push.example.org", "mailto:ops+push@example.org"),
        )
        with tempfile.TemporaryDirectory() as directory:
            for origin, audience, subject in cases:
                with self.subTest(origin=origin):
                    config = WebPushConfig.load(directory, origin, subject)
                    before = int(time.time())
                    auth = config.authorization()["Authorization"]
                    self.assertTrue(auth.startswith("vapid t="))
                    token, key = auth.removeprefix("vapid t=").split(",k=")
                    self.assertEqual(key, config.public_key)
                    header, payload, signature = token.split(".")
                    self.assertEqual(json.loads(decode(header))["alg"], "ES256")
                    claims = json.loads(decode(payload))
                    self.assertEqual(claims["aud"], audience)
                    self.assertEqual(claims["sub"], subject)
                    self.assertIsInstance(claims["exp"], int)
                    self.assertGreater(claims["exp"], before)
                    self.assertLessEqual(claims["exp"], int(time.time()) + 86400)
                    raw = decode(signature)
                    self.assertEqual(len(raw), 64)
                    proof = utils.encode_dss_signature(int.from_bytes(raw[:32], "big"),
                                                       int.from_bytes(raw[32:], "big"))
                    point = decode(key)
                    self.assertEqual((len(point), point[0]), (65, 4))
                    public = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), point)
                    public.verify(proof, (header + "." + payload).encode("ascii"), ec.ECDSA(hashes.SHA256()))

    def test_configuration_rejects_invalid_contacts_and_audiences(self):
        with tempfile.TemporaryDirectory() as directory:
            for subject in ("", "operator", "mailto:", "mailto:ops", "mailto:ops@@example.org",
                            "mailto:,ops@example.org", "mailto:ops@example.org,",
                            "mailto:ops@example.org,,help@example.org", "mailto:ops@", "mailto:ops%40",
                            "http://support.example.org", "https:///contact", "https://host:65536/contact",
                            "https://name@host/contact", "https://host/bad path", "https://host/%GG",
                            "https://host/x\ny"):
                with self.subTest(subject=subject), self.assertRaises(ValueError):
                    WebPushConfig.load(directory, "https://ntfy.example.org", subject)
            for origin in ("https://[2001:db8::1", "https://[::1]:65536", "https://ntfy.example.org/up",
                           "https://name@ntfy.example.org", "http://ntfy.example.org"):
                with self.subTest(origin=origin), self.assertRaises(ValueError):
                    WebPushConfig.load(directory, origin, "mailto:push@example.org")

    def test_existing_signing_key_must_use_p256(self):
        with tempfile.TemporaryDirectory() as directory:
            key = ec.generate_private_key(ec.SECP384R1())
            path = Path(directory, "vapid.pem")
            path.touch(mode=0o600)
            path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                               serialization.NoEncryption()))
            with self.assertRaisesRegex(ValueError, "P-256"):
                WebPushConfig.load(directory, "https://push.example.org", "mailto:ops@example.org")
