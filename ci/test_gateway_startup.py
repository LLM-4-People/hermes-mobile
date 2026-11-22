"""A listening gateway can briefly return its explicit startup state before capabilities."""
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
from pathlib import Path
import sys
import threading
import unittest
from urllib.request import ProxyHandler, build_opener

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "verification"))
from gateway_fixture import Gateway


class GatewayStartupTest(unittest.TestCase):
    def test_only_explicit_starting_response_is_retried(self):
        replies = iter([
            (503, {"error": "gateway_not_ready", "state": "starting"}),
            (200, {"features": {"skills": True}}),
            (401, {"error": "gateway_not_ready", "state": "starting"}),
            (503, {"error": "gateway_not_ready", "state": "stopping"}),
            (503, {"error": "config_unavailable", "state": "starting"}),
            (200, {"features": {}}),
        ])
        class Capabilities(BaseHTTPRequestHandler):
            def do_GET(handler):
                self.assertEqual(handler.path, "/v1/capabilities")
                self.assertEqual(handler.headers["Authorization"], "Bearer fixture")
                code, obj = next(replies)
                handler.send_response(code)
                handler.end_headers()
                handler.wfile.write(json.dumps(obj).encode())

            def log_message(self, *args):
                pass

        with HTTPServer(("127.0.0.1", 0), Capabilities) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                gateway = Gateway.__new__(Gateway)
                gateway.http = build_opener(ProxyHandler({}))
                gateway.endpoint = f"http://127.0.0.1:{server.server_port}"
                gateway.key = "fixture"
                self.assertFalse(gateway._capabilities_ready())
                self.assertTrue(gateway._capabilities_ready())
                for status in (401, 503, 503, 200):
                    with self.assertRaisesRegex(RuntimeError, f"HTTP {status}"):
                        gateway._capabilities_ready()
            finally:
                server.shutdown()
                thread.join()
