"""The real HTTP plugin with an isolated Overpass interpreter and recorded OSM data."""

import json
import logging
from pathlib import Path
import unittest

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from server.nearby import register_routes
from server.nearby.provider import Overpass, endpoint, retry_delay
from server.nearby.records import places

CAPTURE = Path(__file__).with_name("fixtures") / "montreal-restaurants.json"
PATH = "/api/mobile/places/nearby"
AREA = {"latitude": 45.51, "longitude": -73.57}


class NearbyHTTP(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.dataset = json.loads(CAPTURE.read_text(encoding="utf-8"))
        self.status, self.extra = 200, {}
        self.sent = []

        async def interpreter(request):
            self.sent.append(
                (request.path_qs, request.headers.get("Authorization"), dict(await request.post()))
            )
            return web.json_response(self.dataset, status=self.status, headers=self.extra)

        source = web.Application()
        source.router.add_post("/api/interpreter", interpreter)
        self.upstream = TestServer(source)
        await self.upstream.start_server()
        self.addAsyncCleanup(self.upstream.close)
        self.provider = Overpass(str(self.upstream.make_url("/api/interpreter")))
        self.client = await self.profile("one", self.provider)

    async def profile(self, token, provider):
        def auth(request):
            return (
                None if request.headers.get("Authorization") == "Bearer " + token else web.Response(status=401)
            )

        app = web.Application()
        register_routes(app, auth, provider)
        client = TestClient(TestServer(app))
        await client.start_server()
        self.addAsyncCleanup(client.close)
        return client

    async def lookup(self, data=AREA, token="one", client=None):
        return await (client or self.client).post(PATH, json=data, headers={"Authorization": "Bearer " + token})

    async def test_recorded_montreal_over_real_http(self):
        with self.assertLogs("aiohttp.access", logging.INFO) as logs:
            resp = await self.lookup()
            self.assertEqual(resp.status, 200)
            result = await resp.json()
        self.assertEqual(resp.headers["Cache-Control"], "no-store")
        self.assertNotIn("45.51", str(logs.output))
        self.assertNotIn("-73.57", str(logs.output))
        self.assertEqual(
            self.sent,
            [
                (
                    "/api/interpreter",
                    None,
                    {
                        "data": '[out:json][timeout:12][maxsize:12582912];nwr["amenity"="restaurant"]["name"]'
                        "(around:1500,45.51,-73.57);out center;"
                    },
                )
            ],
        )
        self.assertEqual(result["attribution"], "© OpenStreetMap contributors")
        self.assertEqual(result["source_date"], "2026-11-22T07:27:46Z")
        self.assertEqual(result["category"], "restaurant")
        self.assertEqual(result["radius_m"], 1500)
        self.assertEqual(result["limit"], 12)
        self.assertEqual(len(result["places"]), 12)
        first, second = result["places"][:2]
        self.assertEqual(
            (first["name"], first["osm_type"], first["osm_id"], first["distance_m"]),
            ("Yamu Yamu Sushi", "node", 11280151110, 207),
        )
        self.assertEqual((second["name"], second["distance_m"]), ("Mae Sri Thai", 214))
        self.assertTrue(second["website"].startswith("https://"))
        self.assertNotIn("website", first)
        self.assertEqual(result["places"], sorted(result["places"], key=lambda r: r["distance_m"]))

    async def test_auth_input_and_profile_isolation_before_network(self):
        other = await self.profile("two", Overpass(None))
        with self.assertLogs("hermes.mobile.nearby", logging.WARNING) as logs:
            for token in ("", "two"):
                self.assertEqual((await self.lookup(token=token)).status, 401)
            self.assertEqual((await self.lookup(client=other)).status, 401)
            self.assertEqual((await self.lookup(token="two", client=other)).status, 503)
        joined = "\n".join(logs.output)
        self.assertIn("nearby_request_rejected", joined)
        self.assertIn("nearby_provider_unconfigured", joined)
        self.assertNotIn("45.51", joined)
        self.assertNotIn("-73.57", joined)
        self.assertNotIn("Bearer", joined)
        for data in (
            {**AREA, "latitude": 45.511},
            {**AREA, "longitude": True},
            {**AREA, "latitude": 91},
            {**AREA, "url": "https://example.org"},
            {},
            [],
            {**AREA, "longitude": "-73.57"},
        ):
            self.assertEqual((await self.lookup(data)).status, 400)
        body = await self.client.post(
            PATH, data=b" " * 260, headers={"Authorization": "Bearer one", "Content-Type": "application/json"}
        )
        self.assertEqual(body.status, 400)
        self.assertFalse(self.sent)

    async def test_provider_failure_is_distinct_from_empty_and_respects_retry_after(self):
        self.dataset["elements"] = []
        self.assertEqual((await (await self.lookup()).json())["places"], [])
        self.dataset["remark"] = "runtime timeout"
        self.assertEqual((await self.lookup()).status, 503)
        del self.dataset["remark"]
        del self.dataset["osm3s"]["timestamp_osm_base"]
        self.assertEqual((await self.lookup()).status, 503)
        self.status, self.extra = 429, {"Retry-After": "123"}
        resp = await self.lookup()
        self.assertEqual((resp.status, resp.headers["Retry-After"]), (503, "123"))
        count = len(self.sent)
        self.assertEqual((await self.lookup()).status, 503)
        self.assertEqual(len(self.sent), count)

    async def test_source_must_identify_osm_even_for_an_empty_result(self):
        self.dataset["elements"] = []
        source = self.dataset["osm3s"]
        del source["copyright"]
        for credit in (None, "", "OpenHistoricalMap contributors, CC0.",
                      "© OpenStreetMap contributors, CC0.", "OpenStreetMap contributors and another dataset"):
            with self.subTest(credit=credit):
                if credit is not None:
                    source["copyright"] = credit
                response = await self.lookup()
                self.assertEqual(response.status, 503)
                self.assertEqual(await response.json(), {"error": "places_unavailable"})
        source["copyright"] = "© OpenStreetMap contributors, ODbL"
        response = await self.lookup()
        self.assertEqual(response.status, 200)
        self.assertEqual((await response.json())["places"], [])

    async def test_unreadable_elements_are_distinct_from_ordinary_omissions(self):
        known = self.dataset["elements"][0]
        malformed = [None, {}, {**known, "id": True}, {**known, "lat": "unknown"}, {**known, "tags": []}]
        self.dataset["elements"] = malformed
        response = await self.lookup()
        self.assertEqual(response.status, 503)
        self.assertEqual(await response.json(), {"error": "places_unavailable"})
        excluded = [{**known, "tags": {"amenity": "restaurant"}}, {"type": "way", "id": 9, "tags": known["tags"]},
                    {**known, "lat": 0, "lon": 0}]
        for elements in (excluded, malformed + excluded, []):
            self.dataset["elements"] = elements
            response = await self.lookup()
            self.assertEqual(response.status, 200)
            self.assertEqual((await response.json())["places"], [])
        self.dataset["elements"] = malformed + excluded + [known]
        response = await self.lookup()
        self.assertEqual(response.status, 200)
        self.assertEqual([r["osm_id"] for r in (await response.json())["places"]], [known["id"]])

    async def test_only_visible_names_use_the_twelve_result_slots(self):
        original = list(self.dataset["elements"])
        nearest = {**original[0], "id": 1, "lat": AREA["latitude"], "lon": AREA["longitude"]}
        expected = (await (await self.lookup()).json())["places"]
        invisible = ("\u200b", "\u200c\u200d", "\u180b\u180f", "\ufe0f\U000e0100", "\u202eCafe")
        for name in invisible:
            with self.subTest(name=repr(name)):
                nearest["tags"] = {"amenity": "restaurant", "name": name}
                self.dataset["elements"] = [nearest]
                self.assertEqual((await (await self.lookup()).json())["places"], [])
                self.dataset["elements"] = [nearest, *original]
                self.assertEqual((await (await self.lookup()).json())["places"], expected)
        for name in ("خانه\u200cی ما", "Atelier 👩\u200d🍳", "Café \u2665\ufe0f"):
            nearest["tags"] = {"amenity": "restaurant", "name": name}
            self.dataset["elements"] = [nearest]
            self.assertEqual((await (await self.lookup()).json())["places"][0]["name"], name)

    async def test_unreachable_interpreter_returns_unavailable(self):
        await self.upstream.close()
        response = await self.lookup()
        self.assertEqual(response.status, 503)
        self.assertEqual(await response.json(), {"error": "places_unavailable"})

    async def test_redirect_never_uses_other_endpoint(self):
        self.status, self.extra = 307, {"Location": "/other"}
        self.assertEqual((await self.lookup()).status, 503)
        self.assertEqual(len(self.sent), 1)

    def test_centers_bounds_deduplication_and_optional_fields(self):
        tags = {
            "name": "Example",
            "amenity": "restaurant",
            "website": "http://example.org",
            "cuisine": ["unspecified"],
        }
        entry = {"type": "way", "id": 42, "tags": tags, "center": {"lat": 45.51, "lon": -73.57}}
        self.dataset["elements"] = [
            entry,
            entry,
            {**entry, "type": "relation", "id": 9},
            {**entry, "id": 43, "center": {"lat": 46, "lon": -73}},
            {**entry, "id": 44, "center": {"lat": True, "lon": -73}},
            None,
        ]
        found = places(self.dataset, 45.51, -73.57)["places"]
        self.assertEqual(
            [(r["osm_type"], r["osm_id"], r["distance_m"]) for r in found], [("relation", 9, 0), ("way", 42, 0)]
        )
        self.assertTrue(all("website" not in r and "cuisine" not in r for r in found))
        self.assertEqual(retry_delay("unavailable"), 0)
        for value in (
            None,
            "http://example.org/api",
            "https://user@example.org/query",
            "https://example.org/path?a=1",
        ):
            self.assertIsNone(endpoint(value))

    async def test_generalized_osm_categories_radius_limit_and_tags(self):
        from server.nearby.config import CATEGORIES

        expected_categories = {
            "restaurant",
            "cafe",
            "bakery",
            "pharmacy",
            "transit",
            "park",
            "grocery",
            "bookshop",
            "coworking",
        }
        self.assertEqual(set(CATEGORIES), expected_categories)

        self.dataset["elements"] = [
            {
                "type": "node",
                "id": 101,
                "lat": 45.511,
                "lon": -73.571,
                "tags": {
                    "amenity": "cafe",
                    "name": "Café Olimpico",
                    "wheelchair": "yes",
                    "internet_access": "wlan",
                    "takeaway": "yes",
                    "outdoor_seating": "yes",
                    "opening_hours": "24/7",
                },
            },
            {
                "type": "node",
                "id": 102,
                "lat": 45.535,
                "lon": -73.571,
                "tags": {"amenity": "cafe", "name": "Café Lointain"},
            },
            {
                "type": "node",
                "id": 103,
                "lat": 45.511,
                "lon": -73.571,
                "tags": {"amenity": "pharmacy", "name": "Pharmacie du Parc"},
            },
            {
                "type": "way",
                "id": 104,
                "center": {"lat": 45.512, "lon": -73.572},
                "tags": {"railway": "subway_entrance", "name": "Métro Place-des-Arts"},
            },
            {
                "type": "node",
                "id": 105,
                "lat": 45.512,
                "lon": -73.571,
                "tags": {"shop": "supermarket", "name": "Épicerie Locale"},
            },
            {
                "type": "node",
                "id": 106,
                "lat": 45.512,
                "lon": -73.571,
                "tags": {"shop": "books", "name": "Librairie Saint-Denis"},
            },
            {
                "type": "node",
                "id": 107,
                "lat": 45.512,
                "lon": -73.571,
                "tags": {"amenity": "coworking_space", "name": "Atelier Partagé"},
            },
            {
                "type": "node",
                "id": 108,
                "lat": 45.512,
                "lon": -73.571,
                "tags": {"shop": "bakery", "name": "Boulangerie Automne"},
            },
            {
                "type": "way",
                "id": 109,
                "center": {"lat": 45.512, "lon": -73.571},
                "tags": {"leisure": "park", "name": "Parc Jeanne-Mance"},
            },
        ]

        # Narrow radius (500m) excludes Café Lointain (~2.7km away); wider radius (3500m) includes it.
        resp_narrow = await self.lookup({**AREA, "category": "cafe", "radius_m": 500, "limit": 6})
        self.assertEqual(resp_narrow.status, 200)
        body_narrow = await resp_narrow.json()
        self.assertEqual(body_narrow["category"], "cafe")
        self.assertEqual(body_narrow["radius_m"], 500)
        self.assertEqual(body_narrow["limit"], 6)
        self.assertEqual([p["name"] for p in body_narrow["places"]], ["Café Olimpico"])
        self.assertEqual(
            body_narrow["places"][0]["tags"],
            ["wheelchair", "takeaway", "wifi", "24/7", "outdoor_seating"],
        )

        resp_wide = await self.lookup({**AREA, "category": "cafe", "radius_m": 3500, "limit": 24})
        self.assertEqual(resp_wide.status, 200)
        body_wide = await resp_wide.json()
        self.assertEqual(body_wide["radius_m"], 3500)
        self.assertEqual(body_wide["limit"], 24)
        self.assertEqual([p["name"] for p in body_wide["places"]], ["Café Olimpico", "Café Lointain"])

        # Multi-selector union query categories (transit, grocery) and single-selector categories.
        for category, expected_name in (
            ("pharmacy", "Pharmacie du Parc"),
            ("transit", "Métro Place-des-Arts"),
            ("grocery", "Épicerie Locale"),
            ("bookshop", "Librairie Saint-Denis"),
            ("coworking", "Atelier Partagé"),
            ("bakery", "Boulangerie Automne"),
            ("park", "Parc Jeanne-Mance"),
        ):
            with self.subTest(category=category):
                resp = await self.lookup({**AREA, "category": category, "radius_m": 1500, "limit": 10})
                self.assertEqual(resp.status, 200)
                data = await resp.json()
                self.assertEqual(data["category"], category)
                self.assertEqual([p["name"] for p in data["places"]], [expected_name])

    async def test_invalid_category_and_bounds_return_400_before_network(self):
        with self.assertLogs("hermes.mobile.nearby", logging.WARNING) as logs:
            for bad_category in ("", "bar", "nuclear_plant", "restaurant;drop", 123, None):
                with self.subTest(bad_category=bad_category):
                    resp = await self.lookup({**AREA, "category": bad_category})
                    self.assertEqual(resp.status, 400)
                    self.assertEqual(await resp.json(), {"error": "invalid_category"})
            for bad_payload in (
                {**AREA, "radius_m": 499},
                {**AREA, "radius_m": 5001},
                {**AREA, "radius_m": True},
                {**AREA, "radius_m": "1500"},
                {**AREA, "limit": 5},
                {**AREA, "limit": 25},
                {**AREA, "limit": False},
                {**AREA, "limit": 12.5},
            ):
                with self.subTest(bad_payload=bad_payload):
                    resp = await self.lookup(bad_payload)
                    self.assertEqual(resp.status, 400)
                    self.assertEqual(await resp.json(), {"error": "invalid_area"})
        self.assertFalse(self.sent)
        self.assertTrue(any("nearby.invalid_category" in line for line in logs.output))
        self.assertTrue(any("nearby.invalid_area" in line for line in logs.output))
        self.assertNotIn("45.51", "\n".join(logs.output))
        self.assertNotIn("-73.57", "\n".join(logs.output))


class NearbyFork(unittest.TestCase):
    def test_installed_profile_plugin(self):
        import os
        import subprocess
        import sys

        source = os.environ.get("HERMES_TEST_CHECKOUT")
        if not source:
            self.skipTest("Set HERMES_TEST_CHECKOUT to the fork checkout")
        script = Path(__file__).resolve().parents[2] / "verification/nearby_gateway.py"
        subprocess.run(
            [sys.executable, str(script)], env={**os.environ, "PYTHONPATH": source}, check=True, timeout=60
        )
