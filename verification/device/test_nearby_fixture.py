import json
import unittest
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from verification.nearby_fixture import overpass, PLACES, SOURCE_DATE


class NearbyFixtureTest(unittest.TestCase):
    query = '[out:json][timeout:12][maxsize:12582912];nwr["amenity"="restaurant"]["name"](around:1500,45.51,-73.57);out center;'

    def test_real_overpass_post_records_the_coarse_center_and_returns_unsorted_data(self):
        with overpass() as (url, observed, errors):
            with urlopen(url, urlencode({"data": self.query}).encode()) as reply:
                payload = json.load(reply)
            self.assertEqual(observed, [((45.51, -73.57), payload)])
            self.assertFalse(errors)
            self.assertEqual(payload["osm3s"]["timestamp_osm_base"], SOURCE_DATE)
            self.assertEqual([item["tags"]["name"] for item in payload["elements"]],
                             [place[2] for place in reversed(PLACES)])
            self.assertEqual(payload["elements"][0]["center"], {"lat": 45.519, "lon": -73.57})
            self.assertEqual(payload["elements"][-1]["lat"], 45.511)

    def test_credential_forwarding_precise_coordinates_and_coordinate_in_url_fail(self):
        with overpass() as (url, observed, errors):
            for path, query, headers in ((url, self.query, {"Authorization": "Bearer synthetic"}),
                                         (url, self.query.replace("45.51", "45.5199"), {}),
                                         (url + "?lat=45.51", self.query, {})):
                with self.subTest(path=path, headers=headers), self.assertRaises(HTTPError) as rejected:
                    with urlopen(Request(path, urlencode({"data": query}).encode(), headers=headers)):
                        pass
                with rejected.exception:
                    self.assertEqual(rejected.exception.code, 400)
            self.assertFalse(observed)
            self.assertEqual(len(errors), 3)
