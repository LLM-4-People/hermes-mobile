#!/usr/bin/env python3
"""Fail-closed writing quality & single-language purity gate for docs, site, and Android strings.

Rejects Wikipedia "Signs of AI Writing" clichés/filler, sycophantic openers, robotic wrap-ups,
em-dash overuse, and mixed-language leakage across single-language EN/FR surfaces while ignoring
code blocks, inline code, URLs, proper nouns, and explicit language-switcher links.
"""

from html.parser import HTMLParser
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]

# English Wikipedia "Signs of AI Writing" single-word / hyphenated clichés.
EN_SLOP_WORDS = re.compile(
    r"\b("
    r"delve|delves|delving|delved|"
    r"tapestry|tapestries|"
    r"testament|"
    r"pivotal|"
    r"beacon|beacons|"
    r"foster|fosters|fostering|fostered|"
    r"elevate|elevates|elevating|elevated|"
    r"seamlessly|seamless|"
    r"robust|"
    r"holistic|"
    r"game[- ]changer|game[- ]changing|"
    r"comprehensive|"
    r"leverage|leverages|leveraging|leveraged|"
    r"empower|empowers|empowering|empowered|"
    r"realm|realms|"
    r"intricate|"
    r"embark|embarks|embarking|embarked|"
    r"cutting[- ]edge|"
    r"streamline|streamlines|streamlining|streamlined"
    r")\b",
    re.IGNORECASE,
)

# Hype-metaphor "unlock" (excludes literal device/profile cryptographic unlocking).
EN_HYPE_UNLOCK = re.compile(
    r"\bunlock(?:s|ing|ed)?\s+(?:the\s+|your\s+)?(?:full\s+|true\s+|new\s+|endless\s+|hidden\s+)?"
    r"(?:potential|power|possibilities|productivity|insights|value|capabilities|features|efficiency|experience|future|creativity|next\s+level)\b",
    re.IGNORECASE,
)

# English sycophantic openers, negation-reframe clichés, and robotic wrap-ups.
EN_SLOP_PHRASES = re.compile(
    r"(?:"
    r"\b(?:great|excellent|wonderful)\s+question\b|"
    r"^\s*(?:certainly|absolutely|of course)\s*!|"
    r"\b(?:i(?:'|’)d\s+be\s+)?happy\s+to\s+help\b|"
    r"\bnot\s+(?:just|only|merely)\b[^.!?\n]{1,90}\b(?:it(?:'|’)s|but\s+also|but\s+rather)\b|"
    r"\bin\s+conclusion\b|"
    r"(?:^|[.!?]\s+)to\s+summarize\b[,:]?|"
    r"\ball\s+in\s+all\b|"
    r"\bfeel\s+free\s+to\b|"
    r"\blet\s+me\s+know\s+if\s+you\b|"
    r"^\s*(?:moreover|furthermore)\s*,"
    r")",
    re.IGNORECASE | re.MULTILINE,
)

# French AI-slop clichés, sycophantic openers, and robotic wrap-ups.
FR_SLOP_PATTERNS = re.compile(
    r"(?:"
    r"\btapisseries?\b|"
    r"\bpaysage\s+numérique\b|"
    r"\bincontournables?\b|"
    r"\bsynergies?\b|"
    r"\bholistiques?\b|"
    r"\brévolutionnaires?\b|"
    r"\bpropuls(?:er|e|ez|ant)\b|"
    r"\bplong(?:er|ez)\s+dans\b|"
    r"\bsans\s+couture\b|"
    r"\bce\s+n(?:'|’)est\s+pas\s+seulement\b|"
    r"\bexcellente\s+question\b|"
    r"\bn(?:'|’)hésitez\s+pas\s+(?:si|à\s+me\s+demander)\b|"
    r"\bravi\s+de\s+vous\s+aider\b|"
    r"\ben\s+conclusion\b"
    r")",
    re.IGNORECASE,
)

# Mixed-language detectors for single-language pages/resources.
FR_LEAK_ON_EN = re.compile(
    r"\b("
    r"Activité|Cerveau|Compagnon|Mémoire|Savoir-faire|Rappels|Nouvelle|"
    r"Vérifier|Envoyer|Autoriser|Refuser|Télécharger|Brouillon|Épingler|"
    r"Ouvrir|Préparer|Retirer|Joindre|Masquer|Afficher|Compétences|"
    r"Écarts|Proposée|Épinglée|Indisponible|Livraisons|Heures calmes|"
    r"Mises à jour|Diagnostic de connexion|Modèles quotidiens|Palette de commandes|"
    r"Déverrouiller|Verrouiller|Paramètres|Annuler"
    r")\b"
)

EN_LEAK_ON_FR = re.compile(
    r"\b("
    r"Skip to content|Read Documentation|Download Signed APK|View Live Captures|"
    r"Click here|Get started|Show more|Try again|Allow once|Unlock profile|"
    r"Connection doctor|Everyday workflows|Quickstart|Explore Security Model|"
    r"Live Device Gallery|Filter documentation|No matching sections|"
    r"Please enter|Could not reach|Are you sure"
    r")\b",
    re.IGNORECASE,
)

FENCED_CODE = re.compile(r"```[\s\S]*?```")
INLINE_CODE = re.compile(r"`[^`\n]+`")
HTML_COMMENT = re.compile(r"<!--[\s\S]*?-->")
MD_LINK_TARGET = re.compile(r"\]\([^)\n]+\)")
RAW_URL = re.compile(r"https?://\S+")
EXPLICIT_LANG_SWITCH_LINE = re.compile(
    r"(?:Documentation en français|English README|Site français|Portail français|Version française|English version)",
    re.IGNORECASE,
)


def strip_markdown_code(text):
    """Remove fenced code, inline code, comments, and link targets while preserving line numbers."""
    def _keep_newlines(match):
        return "\n" * match.group(0).count("\n")

    cleaned = FENCED_CODE.sub(_keep_newlines, text)
    cleaned = HTML_COMMENT.sub(_keep_newlines, cleaned)
    cleaned = INLINE_CODE.sub(" ", cleaned)
    cleaned = MD_LINK_TARGET.sub("]", cleaned)
    cleaned = RAW_URL.sub(" ", cleaned)
    return cleaned


class _HtmlProseExtractor(HTMLParser):
    """Extract visible prose, title, description, and alt text, skipping code and lang-switch links."""

    def __init__(self, page_lang):
        super().__init__()
        self.page_lang = page_lang
        self.skip_depth = 0
        self.chunks = []

    def handle_starttag(self, tag, attrs):
        attr_map = dict(attrs)
        if self.skip_depth > 0:
            if tag not in ("meta", "link", "img", "br", "hr", "input"):
                self.skip_depth += 1
            return

        lang_attr = (attr_map.get("lang") or "").strip().lower()
        is_lang_switch = "data-lang-switch" in attr_map or (
            lang_attr and not lang_attr.startswith(self.page_lang) and tag != "html"
        )
        if tag in ("script", "style", "code", "pre") or is_lang_switch:
            if tag not in ("meta", "link", "img", "br", "hr", "input"):
                self.skip_depth = 1
            return

        if tag == "meta" and (attr_map.get("name") or "").lower() == "description":
            content = (attr_map.get("content") or "").strip()
            if content:
                self.chunks.append((self.getpos()[0], content))
        elif tag == "img":
            alt = (attr_map.get("alt") or "").strip()
            if alt:
                self.chunks.append((self.getpos()[0], alt))

    def handle_endtag(self, tag):
        if self.skip_depth > 0:
            self.skip_depth -= 1

    def handle_data(self, data):
        if self.skip_depth > 0:
            return
        text = data.strip()
        if text:
            self.chunks.append((self.getpos()[0], text))


def check_text_lines(rel_path, lines, lang, check_em_dash=True):
    """Validate (line_no, text) tuples for AI slop, em-dash density, and mixed-language leaks."""
    issues = []
    total_words = 0
    total_em_dashes = 0

    for line_no, raw_line in lines:
        line = raw_line.strip()
        if not line:
            continue
        is_switcher = bool(EXPLICIT_LANG_SWITCH_LINE.search(line))
        total_words += len(re.findall(r"[A-Za-zÀ-ÿ0-9']+", line))
        em_count = line.count("—")
        total_em_dashes += em_count

        if check_em_dash and em_count >= 3:
            issues.append(f"{rel_path}:{line_no}: em-dash overuse ({em_count} in one line/paragraph)")

        if lang == "en":
            for pattern, label in (
                (EN_SLOP_WORDS, "AI cliché"),
                (EN_HYPE_UNLOCK, "AI hype cliché"),
                (EN_SLOP_PHRASES, "AI filler phrase"),
            ):
                m = pattern.search(line)
                if m:
                    issues.append(f"{rel_path}:{line_no}: {label} {m.group(0)!r}")
            if not is_switcher:
                m = FR_LEAK_ON_EN.search(line)
                if m:
                    issues.append(f"{rel_path}:{line_no}: French leak in English page {m.group(0)!r}")
        elif lang == "fr":
            m = FR_SLOP_PATTERNS.search(line)
            if m:
                issues.append(f"{rel_path}:{line_no}: French AI cliché {m.group(0)!r}")
            if not is_switcher:
                m = EN_LEAK_ON_FR.search(line)
                if m:
                    issues.append(f"{rel_path}:{line_no}: English leak in French page {m.group(0)!r}")

    if check_em_dash and total_words >= 120 and (total_em_dashes * 100.0 / total_words) > 2.5:
        issues.append(
            f"{rel_path}: em-dash density too high ({total_em_dashes} in {total_words} words; max 2.5/100w)"
        )
    return issues


def check_markdown_file(path, root, lang):
    rel = path.relative_to(root).as_posix()
    cleaned = strip_markdown_code(path.read_text(encoding="utf-8"))
    lines = list(enumerate(cleaned.splitlines(), 1))
    return check_text_lines(rel, lines, lang, check_em_dash=True)


def check_html_file(path, root, lang):
    rel = path.relative_to(root).as_posix()
    extractor = _HtmlProseExtractor(lang)
    extractor.feed(path.read_text(encoding="utf-8"))
    return check_text_lines(rel, extractor.chunks, lang, check_em_dash=True)


def check_android_strings_file(path, root, lang):
    rel = path.relative_to(root).as_posix()
    issues = []
    try:
        tree = ET.parse(path)
    except ET.ParseError as err:
        return [f"{rel}: invalid XML ({err})"]
    entries = []
    for idx, el in enumerate(tree.getroot().findall("string"), 1):
        if el.attrib.get("translatable") == "false":
            continue
        text = (el.text or "").strip()
        if text:
            entries.append((idx, text))
    # Individual UI strings are short; check clichés & mixed-language per string (skip doc-level em-dash density).
    for item_issue in check_text_lines(rel, entries, lang, check_em_dash=False):
        issues.append(item_issue)
    return issues


def check_repo(repo_root=ROOT, include_android_strings=True):
    """Scan all managed English/French docs, GitHub Pages, and Android string resources."""
    root = Path(repo_root).resolve()
    problems = []

    en_root_docs = ("README.md", "PRODUCT.md", "SECURITY.md", "DESIGN.md", "CONTRIBUTING.md")
    for name in en_root_docs:
        p = root / name
        if p.is_file():
            problems.extend(check_markdown_file(p, root, "en"))

    docs_dir = root / "docs"
    if docs_dir.is_dir():
        for p in sorted(docs_dir.glob("*.md")):
            problems.extend(check_markdown_file(p, root, "en"))
        fr_docs = docs_dir / "fr"
        if fr_docs.is_dir():
            for p in sorted(fr_docs.rglob("*.md")):
                problems.extend(check_markdown_file(p, root, "fr"))

    site_en = root / "site/en"
    if site_en.is_dir():
        for p in sorted(site_en.rglob("*.html")):
            problems.extend(check_html_file(p, root, "en"))

    site_fr = root / "site/fr"
    if site_fr.is_dir():
        for p in sorted(site_fr.rglob("*.html")):
            problems.extend(check_html_file(p, root, "fr"))

    if include_android_strings:
        res_dir = root / "app/src/main/res"
        values_en = res_dir / "values-en"
        values_fr = res_dir / "values"
        if values_en.is_dir():
            for p in sorted(values_en.glob("*.xml")):
                problems.extend(check_android_strings_file(p, root, "en"))
        if values_fr.is_dir():
            for p in sorted(values_fr.glob("*.xml")):
                problems.extend(check_android_strings_file(p, root, "fr"))

    return problems


def main(argv=None):
    args = argv if argv is not None else sys.argv[1:]
    include_android = "--docs-site-only" not in args
    target = ROOT
    for arg in args:
        if not arg.startswith("-"):
            target = Path(arg)
    problems = check_repo(target, include_android_strings=include_android)
    for problem in problems:
        print(problem, file=sys.stderr)
    if not problems:
        print(f"Writing quality & language purity check passed ({target}).")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
