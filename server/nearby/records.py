"""Select named restaurants with provenance and straight-line distances from a coarse center."""

import math
import re
from datetime import datetime
from ipaddress import ip_address
from urllib.parse import urlsplit


RADIUS_M = 1500
LIMIT = 12
CONTROL = re.compile(r"[\x00-\x1f\x7f-\x9f\u2028\u2029\ud800-\udfff]")
DATASET_DATE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")


class InvalidDataset(ValueError):
    pass


def coordinate(value, bound):
    return type(value) in (int, float) and -bound <= value <= bound


def accepted_center(data):
    if not isinstance(data, dict) or set(data) != {"latitude", "longitude"}:
        raise ValueError
    lat, lon = data["latitude"], data["longitude"]
    if not (coordinate(lat, 90) and coordinate(lon, 180) and lat == round(lat, 2) and lon == round(lon, 2)):
        raise ValueError
    return lat, lon


def text(value, limit):
    if not isinstance(value, str) or len(value) > limit or CONTROL.search(value):
        return ""
    return value.strip()


def website(value):
    value = text(value, 2048)
    if not value or any(c.isspace() for c in value) or "\\" in value:
        return ""
    try:
        url = urlsplit(value)
        host = url.hostname or ""
        if (
            url.scheme != "https"
            or url.username is not None
            or url.password is not None
            or url.port not in (None, 443)
            or "." not in host
            or host.endswith((".local", ".localhost"))
        ):
            return ""
        try:
            if not ip_address(host).is_global:
                return ""
        except ValueError:
            labels = host.encode("idna").decode("ascii").split(".")
            if (
                not all(re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", s) for s in labels)
                or labels[-1].isdigit()
            ):
                return ""
        return value
    except (ValueError, UnicodeError):
        return ""


def distance_m(lat, lon, place_lat, place_lon):
    rad = math.radians
    h = (
        math.sin(rad(place_lat - lat) / 2) ** 2
        + math.cos(rad(lat)) * math.cos(rad(place_lat)) * math.sin(rad(place_lon - lon) / 2) ** 2
    )
    return 12_742_000 * math.asin(math.sqrt(min(1, h)))


def restaurant(element, lat, lon):
    if not isinstance(element, dict):
        return None
    kind, osm_id, tags = element.get("type"), element.get("id"), element.get("tags")
    if kind not in ("node", "way", "relation") or type(osm_id) is not int or not 0 < osm_id <= 2**63 - 1:
        return None
    if (
        not isinstance(tags, dict)
        or tags.get("amenity") != "restaurant"
        or not (name := text(tags.get("name"), 256))
    ):
        return None
    point = element if kind == "node" else element.get("center")
    if (
        not isinstance(point, dict)
        or not coordinate(point.get("lat"), 90)
        or not coordinate(point.get("lon"), 180)
    ):
        return None
    distance = distance_m(lat, lon, point["lat"], point["lon"])
    if distance > RADIUS_M:
        return None
    row = {
        "osm_type": kind,
        "osm_id": osm_id,
        "name": name,
        "latitude": point["lat"],
        "longitude": point["lon"],
        "distance_m": round(distance),
    }
    street = text(tags.get("addr:street"), 160)
    address = text(tags.get("addr:full"), 320) or (
        " ".join(filter(None, (text(tags.get("addr:housenumber"), 32), street))) if street else ""
    )
    optional = {
        "address": address,
        "cuisine": text(tags.get("cuisine"), 256),
        "website": website(tags.get("website")) or website(tags.get("contact:website")),
    }
    row.update((key, value) for key, value in optional.items() if value)
    return distance, row


def places(data, lat, lon):
    if not isinstance(data, dict) or "remark" in data or not isinstance(data.get("elements"), list):
        raise InvalidDataset
    source = data.get("osm3s")
    stamp = source.get("timestamp_osm_base") if isinstance(source, dict) else None
    if not isinstance(stamp, str) or not DATASET_DATE.fullmatch(stamp):
        raise InvalidDataset
    try:
        datetime.strptime(stamp, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError as exc:
        raise InvalidDataset from exc
    ordered = sorted(
        (pair for e in data["elements"] if (pair := restaurant(e, lat, lon)) is not None),
        key=lambda p: (p[0], p[1]["osm_type"], p[1]["osm_id"]),
    )
    unique = {}
    for _, row in ordered:
        unique.setdefault((row["osm_type"], row["osm_id"]), row)
        if len(unique) == LIMIT:
            break
    return {
        "source": "OpenStreetMap",
        "attribution": "© OpenStreetMap contributors",
        "source_date": stamp,
        "radius_m": RADIUS_M,
        "places": list(unique.values()),
    }
