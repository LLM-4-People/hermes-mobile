"""Single source of truth for Generalized Nearby Explorer configuration and OSM category allowlist."""

from dataclasses import dataclass
import os
from typing import Callable


OVERPASS_URL_ENV = "HERMES_MOBILE_OVERPASS_URL"
DEFAULT_CATEGORY = "restaurant"
MIN_RADIUS_M = 500
MAX_RADIUS_M = 5000
MIN_LIMIT = 6
MAX_LIMIT = 24
OVERPASS_TIMEOUT_S = 12
HTTP_TIMEOUT_S = 20
OVERPASS_MAXSIZE_BYTES = 12_582_912
MAX_RESPONSE_BYTES = 4 * 1024 * 1024
MAX_REQUEST_BYTES = 256
USER_AGENT = "Hermes-Mobile/1.0 (github.com/LLM-4-People/hermes-mobile)"


def _bounded_env_int(name: str, default: int, low: int, high: int) -> int:
    raw = os.environ.get(name, "").strip()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        return default
    return value if low <= value <= high else default


DEFAULT_RADIUS_M = _bounded_env_int(
    "HERMES_MOBILE_NEARBY_DEFAULT_RADIUS_M", 1500, MIN_RADIUS_M, MAX_RADIUS_M
)
DEFAULT_LIMIT = _bounded_env_int(
    "HERMES_MOBILE_NEARBY_DEFAULT_LIMIT", 12, MIN_LIMIT, MAX_LIMIT
)


@dataclass(frozen=True)
class CategorySpec:
    slug: str
    selectors: tuple[tuple[str, str], ...]
    matcher: Callable[[dict], bool]


def _match_any(*pairs: tuple[str, str]) -> Callable[[dict], bool]:
    def _check(tags: dict) -> bool:
        return any(tags.get(key) == value for key, value in pairs)

    return _check


CATEGORIES: dict[str, CategorySpec] = {
    "restaurant": CategorySpec(
        "restaurant",
        (("amenity", "restaurant"),),
        _match_any(("amenity", "restaurant")),
    ),
    "cafe": CategorySpec(
        "cafe",
        (("amenity", "cafe"),),
        _match_any(("amenity", "cafe")),
    ),
    "bakery": CategorySpec(
        "bakery",
        (("shop", "bakery"),),
        _match_any(("shop", "bakery")),
    ),
    "pharmacy": CategorySpec(
        "pharmacy",
        (("amenity", "pharmacy"),),
        _match_any(("amenity", "pharmacy")),
    ),
    "transit": CategorySpec(
        "transit",
        (
            ("public_transport", "station"),
            ("railway", "station"),
            ("railway", "subway_entrance"),
            ("highway", "bus_stop"),
        ),
        _match_any(
            ("public_transport", "station"),
            ("railway", "station"),
            ("railway", "subway_entrance"),
            ("highway", "bus_stop"),
        ),
    ),
    "park": CategorySpec(
        "park",
        (("leisure", "park"),),
        _match_any(("leisure", "park")),
    ),
    "grocery": CategorySpec(
        "grocery",
        (
            ("shop", "supermarket"),
            ("shop", "convenience"),
            ("shop", "greengrocer"),
        ),
        _match_any(
            ("shop", "supermarket"),
            ("shop", "convenience"),
            ("shop", "greengrocer"),
        ),
    ),
    "bookshop": CategorySpec(
        "bookshop",
        (("shop", "books"),),
        _match_any(("shop", "books")),
    ),
    "coworking": CategorySpec(
        "coworking",
        (("amenity", "coworking_space"),),
        _match_any(("amenity", "coworking_space")),
    ),
}


class InvalidCategory(ValueError):
    """Raised when a requested nearby category is not in the OSM allowlist."""


def validate_category(value) -> str:
    if not isinstance(value, str) or value not in CATEGORIES:
        raise InvalidCategory("Unsupported nearby category")
    return value


def validate_radius(value) -> int:
    if type(value) is not int or not (MIN_RADIUS_M <= value <= MAX_RADIUS_M):
        raise ValueError("Radius out of bounds")
    return value


def validate_limit(value) -> int:
    if type(value) is not int or not (MIN_LIMIT <= value <= MAX_LIMIT):
        raise ValueError("Limit out of bounds")
    return value


def matches_category(tags: dict, category: str) -> bool:
    spec = CATEGORIES.get(category)
    return spec is not None and spec.matcher(tags)


def overpass_query(category: str, radius_m: int, lat: float, lon: float) -> str:
    spec = CATEGORIES[validate_category(category)]
    radius = validate_radius(radius_m)
    header = f"[out:json][timeout:{OVERPASS_TIMEOUT_S}][maxsize:{OVERPASS_MAXSIZE_BYTES}];"
    clauses = [
        f'nwr["{key}"="{val}"]["name"](around:{radius},{lat:.2f},{lon:.2f});'
        for key, val in spec.selectors
    ]
    if len(clauses) == 1:
        return f"{header}{clauses[0]}out center;"
    return f"{header}({''.join(clauses)});out center;"
