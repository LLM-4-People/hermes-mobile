"""Local Overpass interpreter with three synthetic restaurants near the emulator's public-city fix."""

from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import re
from threading import Thread
from urllib.parse import parse_qs


# The emulator is explicitly placed at Montréal's Place des Arts. Android moves
# approximate fixes onto a coarse grid, so place each synthetic record relative to
# the rounded center actually requested through the gateway's real provider.
MONTREAL = (45.5089, -73.5663)
SOURCE_DATE = "2026-11-22T07:27:46Z"
PLACES = (
    ("node", 900000000001, "Cantine témoin des Érables", 0.001, "vegetarian"),
    ("way", 900000000002, "Bistro témoin du Square", -0.004, "italian"),
    ("relation", 900000000003, "Table témoin des Arts", 0.009, "regional"),
)


def response_for(query):
    match = re.fullmatch(
        r'\[out:json\]\[timeout:\d+\]\[maxsize:\d+\];'
        r'nwr\["amenity"="restaurant"\]\["name"\]'
        r'\(around:1500,(-?\d+\.\d{2}),(-?\d+\.\d{2})\);out center;', query)
    if not match:
        raise ValueError("Expected the restaurant query and two-decimal coordinates in the POST body")
    latitude, longitude = map(float, match.groups())
    if abs(latitude - MONTREAL[0]) > 0.08 or abs(longitude - MONTREAL[1]) > 0.10:
        raise ValueError("Expected an approximate fix near the emulator's public Montréal landmark")
    elements = []
    for kind, osm_id, name, delta, cuisine in reversed(PLACES):
        point = {"lat": round(latitude + delta, 5), "lon": longitude}
        elements.append({
            "type": kind, "id": osm_id, **(point if kind == "node" else {"center": point}),
            "tags": {"amenity": "restaurant", "name": name, "cuisine": cuisine},
        })
    return (latitude, longitude), {"version": 0.6, "osm3s": {"timestamp_osm_base": SOURCE_DATE, "copyright": "© OpenStreetMap contributors"},
                                   "elements": elements}


@contextmanager
def overpass():
    requests = []
    errors = []

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            try:
                if self.path != "/api/interpreter":
                    raise ValueError("Unexpected Overpass path or URL query")
                if self.headers.get("Authorization") or self.headers.get("Cookie"):
                    raise ValueError("Profile credentials reached the fixture")
                if self.headers.get_content_type() != "application/x-www-form-urlencoded":
                    raise ValueError("Expected a form-encoded Overpass request")
                body = self.rfile.read(int(self.headers["Content-Length"])).decode()
                fields = parse_qs(body, strict_parsing=True)
                if set(fields) != {"data"} or len(fields["data"]) != 1:
                    raise ValueError("Unexpected Overpass form fields")
                center, payload = response_for(fields["data"][0])
            except (KeyError, TypeError, ValueError) as error:
                errors.append(str(error))
                self.send_error(400, "Unexpected request to the synthetic restaurant source")
                return
            requests.append((center, payload))
            data = json.dumps(payload, ensure_ascii=False).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *args):
            pass

    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
        worker = Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            yield f"http://127.0.0.1:{server.server_port}/api/interpreter", requests, errors
        finally:
            server.shutdown()
            worker.join()
