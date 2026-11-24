"""Fail-closed static integrity, zero-CLS, dual-theme, and language-strict check for site/."""

from html.parser import HTMLParser
from pathlib import Path
import sys
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[1]
REQUIRED_FILES = (
    Path(".nojekyll"),
    Path("index.html"),
    Path("docs/index.html"),
    Path("walkthrough/index.html"),
    Path("en/index.html"),
    Path("fr/index.html"),
    Path("en/docs/index.html"),
    Path("fr/docs/index.html"),
    Path("en/walkthrough/index.html"),
    Path("fr/walkthrough/index.html"),
)
EXTERNAL_SCHEMES = ("http", "https", "mailto", "tel", "data", "javascript")
IMAGE_ATTRS = ("src", "data-src-light", "data-src-dark")


class _AssetParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.refs = []
        self.img_issues = []
        self.image_refs = []
        self.ids = set()

    def handle_starttag(self, tag, attrs):
        attr_map = dict(attrs)
        elem_id = (attr_map.get("id") or "").strip()
        if elem_id:
            self.ids.add(elem_id)
        if tag == "img":
            src = (attr_map.get("src") or "").strip()
            if not src:
                self.img_issues.append("img with empty src")
            if "alt" not in attr_map:
                self.img_issues.append(f"img missing alt ({src})")
            for dim in ("width", "height"):
                val = (attr_map.get(dim) or "").strip()
                if not val.isdigit() or int(val) <= 0:
                    self.img_issues.append(f"img missing positive integer {dim} ({src})")
            for key in IMAGE_ATTRS:
                raw = (attr_map.get(key) or "").strip()
                if raw:
                    self.image_refs.append((tag, key, raw))
        elif tag == "button":
            for key in ("data-src-light", "data-src-dark"):
                raw = (attr_map.get(key) or "").strip()
                if raw:
                    self.refs.append((tag, key, raw))
                    self.image_refs.append((tag, key, raw))
        elif tag == "meta":
            prop = (attr_map.get("property") or attr_map.get("name") or "").strip()
            if prop in ("og:image", "twitter:image"):
                content = (attr_map.get("content") or "").strip()
                if content:
                    self.image_refs.append((tag, prop, content))
        for key in ("href", "src", "data-src-light", "data-src-dark"):
            if tag == "button" and key in ("data-src-light", "data-src-dark"):
                continue
            val = attr_map.get(key)
            if val is not None:
                self.refs.append((tag, key, val.strip()))


def _check_language_assets(rel_html, image_refs):
    problems = []
    if rel_html.startswith("en/"):
        for tag, attr, raw in image_refs:
            lower = raw.lower()
            if "-fr." in lower or "/fr/" in lower or "/screens/phone-" in lower or "/screens/tablet-" in lower:
                problems.append(
                    f"site/{rel_html}: English page references non-English image <{tag} {attr}={raw!r}>"
                )
    elif rel_html.startswith("fr/"):
        for tag, attr, raw in image_refs:
            lower = raw.lower()
            if (
                "-en." in lower
                or "/en/" in lower
                or lower.endswith("marketing/hero-studio.png")
                or lower.endswith("marketing/privacy-architecture.png")
            ):
                problems.append(
                    f"site/{rel_html}: French page references English-only image <{tag} {attr}={raw!r}>"
                )
    return problems


def validate_site(site_dir):
    """Return a list of problems found in site_dir (empty list means clean)."""
    site_root = Path(site_dir).resolve()
    problems = []
    if not site_root.is_dir():
        return [f"Missing site directory: {site_root}"]

    for rel in REQUIRED_FILES:
        target = site_root / rel
        if not target.is_file():
            problems.append(f"Missing required site file: site/{rel.as_posix()}")

    html_files = sorted(site_root.rglob("*.html"))
    if not html_files:
        problems.append("No HTML files found under site/")
        return problems

    parsed_pages = {}
    for html_file in html_files:
        content = html_file.read_text(encoding="utf-8")
        rel_html = html_file.relative_to(site_root).as_posix()
        if "\u2014" in content:
            problems.append(f"site/{rel_html}: contains em-dash character (U+2014)")
        parser = _AssetParser()
        parser.feed(content)
        for issue in parser.img_issues:
            problems.append(f"site/{rel_html}: {issue}")
        problems.extend(_check_language_assets(rel_html, parser.image_refs))
        parsed_pages[html_file.resolve()] = (rel_html, parser)

    for html_file in html_files:
        resolved_html = html_file.resolve()
        rel_html, parser = parsed_pages[resolved_html]
        for tag, attr, raw in parser.refs:
            if not raw:
                continue
            parts = urlsplit(raw)
            if parts.scheme in EXTERNAL_SCHEMES:
                continue
            if parts.scheme:
                problems.append(f"site/{rel_html}: unsupported URL scheme in <{tag} {attr}={raw!r}>")
                continue
            fragment = unquote(parts.fragment)
            ref_path = parts.path
            if not ref_path:
                if fragment and fragment not in parser.ids:
                    problems.append(
                        f"site/{rel_html}: broken anchor fragment <{tag} {attr}={raw!r}> (missing id={fragment!r})"
                    )
                continue
            if ref_path.startswith("/"):
                candidate = (site_root / ref_path.lstrip("/")).resolve()
            else:
                candidate = (html_file.parent / ref_path).resolve()
            try:
                candidate.relative_to(site_root)
            except ValueError:
                problems.append(f"site/{rel_html}: reference escapes site/ root: {raw!r}")
                continue
            if candidate.is_dir():
                candidate = candidate / "index.html"
            if not candidate.is_file():
                problems.append(f"site/{rel_html}: broken local reference <{tag} {attr}={raw!r}>")
                continue
            if fragment:
                target_entry = parsed_pages.get(candidate.resolve())
                if target_entry is not None and fragment not in target_entry[1].ids:
                    problems.append(
                        f"site/{rel_html}: broken cross-page anchor fragment <{tag} {attr}={raw!r}> "
                        f"(missing id={fragment!r} in site/{target_entry[0]})"
                    )
    return problems


def main(argv=None):
    site_dir = Path(argv[0]) if argv else (ROOT / "site")
    problems = validate_site(site_dir)
    for problem in problems:
        print(problem, file=sys.stderr)
    if not problems:
        print(f"Pages site integrity check passed ({site_dir}).")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
