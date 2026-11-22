"""Select named nearby OpenStreetMap places with provenance and straight-line distances."""

import math
import re
import unicodedata
from datetime import datetime
from ipaddress import ip_address
from urllib.parse import urlsplit

from .config import (
    DEFAULT_CATEGORY,
    DEFAULT_LIMIT,
    DEFAULT_RADIUS_M,
    InvalidCategory,
    matches_category,
    validate_category,
    validate_limit,
    validate_radius,
)


RADIUS_M = DEFAULT_RADIUS_M
LIMIT = DEFAULT_LIMIT
ALLOWED_REQUEST_KEYS = frozenset({"latitude", "longitude", "category", "radius_m", "limit"})
OSM_CREDIT = "© OpenStreetMap contributors"
OVERPASS_CREDIT = "The data included in this document is from www.openstreetmap.org. The data is made available under ODbL."
CONTROL = re.compile(r"[\x00-\x1f\x7f-\x9f\u2028\u2029\ud800-\udfff]")
# Android's VisibleText treats isolated joiners and Unicode variation selectors as blank.
BLANK_LABEL = re.compile(r"[\s\u200c\u200d\u180b-\u180d\u180f\ufe00-\ufe0f\U000e0100-\U000e01ef]*")
DATASET_DATE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")


class InvalidDataset(ValueError):
    pass


def coordinate(value, bound):
    return type(value) in (int, float) and math.isfinite(value) and -bound <= value <= bound


def accepted_center(data):
    lat, lon, _, _, _ = accepted_request(data)
    return lat, lon


def accepted_request(data):
    if not isinstance(data, dict) or not {"latitude", "longitude"}.issubset(data) or not set(data).issubset(ALLOWED_REQUEST_KEYS):
        raise ValueError("Invalid nearby request keys")
    lat, lon = data["latitude"], data["longitude"]
    if not (coordinate(lat, 90) and coordinate(lon, 180) and lat == round(lat, 2) and lon == round(lon, 2)):
        raise ValueError("Invalid coarse coordinates")
    category = validate_category(data.get("category")) if "category" in data else DEFAULT_CATEGORY
    radius_m = validate_radius(data.get("radius_m")) if "radius_m" in data else DEFAULT_RADIUS_M
    limit = validate_limit(data.get("limit")) if "limit" in data else DEFAULT_LIMIT
    return lat, lon, category, radius_m, limit


def text(value, limit):
    if not isinstance(value, str) or len(value) > limit or CONTROL.search(value):
        return ""
    if BLANK_LABEL.fullmatch(value) or any(
        unicodedata.category(char) == "Cf" and char not in ("\u200c", "\u200d") for char in value
    ):
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


def _place_tags(tags: dict) -> list[str]:
    result = []
    if tags.get("wheelchair") == "yes":
        result.append("wheelchair")
    if tags.get("takeaway") in ("yes", "only"):
        result.append("takeaway")
    if tags.get("internet_access") in ("wlan", "yes", "wifi"):
        result.append("wifi")
    if tags.get("opening_hours") == "24/7":
        result.append("24/7")
    if tags.get("outdoor_seating") == "yes":
        result.append("outdoor_seating")
    return result


def place(element, lat, lon, category=DEFAULT_CATEGORY, radius_m=DEFAULT_RADIUS_M):
    if not isinstance(element, dict):
        raise InvalidDataset
    kind, osm_id, tags = element.get("type"), element.get("id"), element.get("tags", {})
    if kind not in ("node", "way", "relation") or type(osm_id) is not int or not 0 < osm_id <= 2**63 - 1:
        raise InvalidDataset
    point = element if kind == "node" else element.get("center", {})
    if not isinstance(tags, dict) or not isinstance(point, dict):
        raise InvalidDataset
    if any(key in point and not coordinate(point[key], bound) for key, bound in (("lat", 90), ("lon", 180))):
        raise InvalidDataset
    if (not matches_category(tags, category) or not (name := text(tags.get("name"), 256))
            or "lat" not in point or "lon" not in point):
        return None
    distance = distance_m(lat, lon, point["lat"], point["lon"])
    if distance > radius_m:
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
        "opening_hours": text(tags.get("opening_hours"), 160),
        "website": website(tags.get("website")) or website(tags.get("contact:website")),
    }
    row.update((key, value) for key, value in optional.items() if value)
    extra_tags = _place_tags(tags)
    if extra_tags:
        row["tags"] = extra_tags
    return distance, row


def restaurant(element, lat, lon):
    return place(element, lat, lon, category=DEFAULT_CATEGORY, radius_m=DEFAULT_RADIUS_M)


def places(data, lat, lon, category=DEFAULT_CATEGORY, radius_m=DEFAULT_RADIUS_M, limit=DEFAULT_LIMIT):
    category = validate_category(category)
    radius_m = validate_radius(radius_m)
    limit = validate_limit(limit)
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
    credit = source.get("copyright")
    if not isinstance(credit, str):
        raise InvalidDataset
    # Match the entire notice. Another dataset can mention OSM or describe a different license.
    notice = " ".join(credit.split()).casefold().removeprefix("© ").removesuffix(".")
    if notice not in (OVERPASS_CREDIT.casefold().removesuffix("."), "openstreetmap contributors",
                      "openstreetmap contributors, odbl"):
        raise InvalidDataset

    ordered = []
    malformed = 0
    for element in data["elements"]:
        try:
            pair = place(element, lat, lon, category=category, radius_m=radius_m)
        except InvalidDataset:
            malformed += 1
            continue
        if pair is not None:
            ordered.append(pair)
    if malformed and malformed == len(data["elements"]):
        raise InvalidDataset
    ordered.sort(key=lambda p: (p[0], p[1]["osm_type"], p[1]["osm_id"]))
    unique = {}
    for _, row in ordered:
        unique.setdefault((row["osm_type"], row["osm_id"]), row)
        if len(unique) == limit:
            break
    return {
        "source": "OpenStreetMap",
        "attribution": OSM_CREDIT,
        "source_date": stamp,
        "category": category,
        "radius_m": radius_m,
        "limit": limit,
        "places": list(unique.values()),
    }


__all__ = [
    "InvalidCategory",
    "InvalidDataset",
    "LIMIT",
    "OSM_CREDIT",
    "RADIUS_M",
    "accepted_center",
    "accepted_request",
    "place",
    "places",
    "restaurant",
]
