"""Fast static integrity check for the GitHub Pages site under site/."""

from html.parser import HTMLParser
from pathlib import Path
import sys
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parents[1]
REQUIRED_FILES = (
    Path(".nojekyll"),
    Path("index.html"),
    Path("docs/index.html"),
    Path("walkthrough/index.html"),
)
EXTERNAL_SCHEMES = ("http", "https", "mailto", "tel", "data", "javascript")


class _AssetParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.refs = []
        self.missing_alt = []

    def handle_starttag(self, tag, attrs):
        attr_map = dict(attrs)
        if tag == "img":
            src = attr_map.get("src")
            if not src or not src.strip():
                self.missing_alt.append("img with empty src")
            if "alt" not in attr_map:
                self.missing_alt.append(f"img missing alt ({src})")
        for key in ("href", "src"):
            val = attr_map.get(key)
            if val is not None:
                self.refs.append((tag, key, val.strip()))


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

    for html_file in html_files:
        parser = _AssetParser()
        parser.feed(html_file.read_text(encoding="utf-8"))
        rel_html = html_file.relative_to(site_root).as_posix()
        for issue in parser.missing_alt:
            problems.append(f"site/{rel_html}: {issue}")
        for tag, attr, raw in parser.refs:
            if not raw or raw.startswith("#"):
                continue
            parts = urlsplit(raw)
            if parts.scheme in EXTERNAL_SCHEMES:
                continue
            if parts.scheme:
                problems.append(f"site/{rel_html}: unsupported URL scheme in <{tag} {attr}={raw!r}>")
                continue
            ref_path = parts.path
            if not ref_path:
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
