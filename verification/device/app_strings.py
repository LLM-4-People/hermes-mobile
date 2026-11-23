"""Single Source of Truth loader for Android XML string resources during device verification.

Reads app/src/main/res/values/*.xml (French default) and app/src/main/res/values-en/*.xml (English)
according to the connected device's actual resolved locale—never forcing a test locale.
"""

from functools import lru_cache
from pathlib import Path
import re
from xml.etree import ElementTree

APP = "org.llm4people.hermes"
REPO_ROOT = Path(__file__).resolve().parents[2]
RES_DIR = REPO_ROOT / "app" / "src" / "main" / "res"
SUPPORTED_LANGS = ("fr", "en")

_BRACKET_LIST = re.compile(r"\[([^\]]*)\]")
_BCP47_TAG = re.compile(r"[A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{2,8})*")
_ESCAPE_RE = re.compile(r"\\([\'\"\\nt])")
_PRINTF_RE = re.compile(r"%(?:(\d+)\$)?[sdf]")


def _unescape_android_xml(raw: str) -> str:
    """Decode Android string resource escapes and unquoted surrounding whitespace."""
    text = raw.strip()
    if len(text) >= 2 and text[0] == '"' and text[-1] == '"':
        text = text[1:-1]
    else:
        text = re.sub(r"\s+", " ", text)

    def repl(match: re.Match[str]) -> str:
        ch = match.group(1)
        if ch == "n":
            return "\n"
        if ch == "t":
            return "\t"
        return ch

    return _ESCAPE_RE.sub(repl, text)


def parse_locale_tags(raw: str) -> list[str]:
    """Extract ordered BCP-47 locale tags from adb locale/getprop output."""
    if not isinstance(raw, str):
        return []
    text = raw.strip()
    if not text:
        return []
    bracket = _BRACKET_LIST.search(text)
    if bracket is not None:
        text = bracket.group(1).strip()
        if not text:
            return []
    return _BCP47_TAG.findall(text)


def resolve_lang(tags: list[str], *, default: str = "en") -> str:
    """Resolve Android's effective app language ('en' or 'fr') for the given BCP-47 tag list.

    Mirrors AppLanguage.resolveTag(Mode.AUTO, tags): picks the first supported language ('fr' or 'en'),
    falling back to 'fr' (Android's res/values/ default) only when non-empty unsupported tags are present,
    or `default` when no locale tags were supplied.
    """
    seen_any = False
    for raw in tags:
        base = raw.replace("_", "-").split("-", 1)[0].lower()
        if not base:
            continue
        seen_any = True
        if base in SUPPORTED_LANGS:
            return base
    return "fr" if seen_any else default


def detect_device_lang(device, *, package: str = APP, default: str = "en") -> str:
    """Detect the effective language ('en' or 'fr') Hermes Mobile renders on `device`."""
    if device is None or not hasattr(device, "shell"):
        return default

    def probe(*args: str) -> str:
        try:
            out = device.shell(*args)
        except Exception:
            return ""
        return out if isinstance(out, str) else ""

    # 1. Per-app locale override (Android 13+ LocaleManager) takes highest precedence when non-empty.
    app_locales = probe("cmd", "locale", "get-app-locales", package)
    if "[" in app_locales:
        app_tags = parse_locale_tags(app_locales)
        if app_tags:
            return resolve_lang(app_tags, default=default)

    # 2. System locale properties in Android priority order.
    tags: list[str] = []
    for prop in (
        "persist.sys.locale",
        "ro.product.locale",
        "persist.sys.language",
        "ro.product.locale.language",
    ):
        tags.extend(parse_locale_tags(probe("getprop", prop)))
    return resolve_lang(tags, default=default)


@lru_cache(maxsize=8)
def _load_resources(lang: str, res_dir: Path = RES_DIR) -> dict[str, str]:
    if lang not in SUPPORTED_LANGS:
        raise ValueError(f"Unsupported app language: {lang!r}")
    base_dir = res_dir / "values"
    merged: dict[str, str] = {}

    def read_folder(folder: Path) -> None:
        if not folder.is_dir():
            return
        for xml_file in sorted(folder.glob("*.xml")):
            root = ElementTree.parse(xml_file).getroot()
            for element in root.findall("string"):
                name = element.attrib.get("name")
                if not name:
                    continue
                raw = "".join(element.itertext())
                merged[name] = _unescape_android_xml(raw)

    read_folder(base_dir)
    if lang != "fr":
        read_folder(res_dir / f"values-{lang}")
    return merged


class AppStrings:
    """Read-only lookup table backed directly by app/src/main/res/values{,-en}/*.xml."""

    def __init__(self, lang: str, *, res_dir: Path = RES_DIR):
        self.lang = resolve_lang([lang], default="en")
        self._table = _load_resources(self.lang, res_dir)

    @classmethod
    def for_lang(cls, lang: str, *, res_dir: Path = RES_DIR) -> "AppStrings":
        return cls(lang, res_dir=res_dir)

    @classmethod
    def for_device(cls, device, *, package: str = APP, default: str = "en", res_dir: Path = RES_DIR) -> "AppStrings":
        return cls(detect_device_lang(device, package=package, default=default), res_dir=res_dir)

    def __getitem__(self, key: str) -> str:
        return self._table[key]

    def __contains__(self, key: str) -> bool:
        return key in self._table

    def get(self, key: str, fallback: str | None = None) -> str:
        if key in self._table:
            return self._table[key]
        if fallback is not None:
            return fallback
        raise KeyError(key)

    def fmt(self, key: str, *args) -> str:
        template = self._table[key]
        positional = iter(args)

        def repl(match: re.Match[str]) -> str:
            idx = match.group(1)
            if idx is not None:
                return str(args[int(idx) - 1])
            return str(next(positional))

        return _PRINTF_RE.sub(repl, template)
