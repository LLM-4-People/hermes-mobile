#!/usr/bin/env python3
"""Fail-closed writing quality, sentence-case, and single-language purity gate.

Enforces:
- Strict sentence case on Markdown headings, HTML headings/titles/buttons/captions, and Android UI
  strings/plurals/arrays (allowing true proper nouns, technical acronyms, and CamelCase identifiers).
- Prohibited Wikipedia "Signs of AI Writing" clichés/filler, hype metaphors, negative parallelisms,
  sycophantic openers, robotic wrap-ups, and em-dash clutter (English + French).
- Single-language purity across English and French docs, GitHub Pages, and Android resources.
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
    r"supercharge|supercharges|supercharging|supercharged|"
    r"bespoke|"
    r"next[- ]level|"
    r"groundbreaking|"
    r"transformative|"
    r"synergy|synergies|"
    r"vibrant|"
    r"unleash|unleashes|unleashing|unleashed|"
    r"effortless|effortlessly|"
    r"orchestrate|orchestrates|orchestrating|orchestrated|"
    r"reimagine|reimagines|reimagining|reimagined|"
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

# English sycophantic openers, negative parallelisms, and robotic wrap-ups / sign-offs.
EN_SLOP_PHRASES = re.compile(
    r"(?:"
    r"\b(?:great|excellent|wonderful)\s+question\b|"
    r"^\s*(?:certainly|absolutely|of course)\s*!|"
    r"\b(?:i(?:'|’)d\s+be\s+)?happy\s+to\s+help\b|"
    r"\bnot\s+(?:just|only|merely)\b[^.!?\n]{1,90}\b(?:it(?:'|’)s|but\s+also|but\s+rather)\b|"
    r"\bmore\s+than\s+just\s+a\b|"
    r"\bin\s+conclusion\b|"
    r"(?:^|[.!?]\s+)to\s+summarize\b[,:]?|"
    r"\ball\s+in\s+all\b|"
    r"\bfeel\s+free\s+to\b|"
    r"\bdon(?:'|’)t\s+hesitate\s+to\b|"
    r"\blet\s+me\s+know\s+if\s+you\b|"
    r"\bhope\s+this\s+helps\b|"
    r"\bhappy\s+coding\b|"
    r"^\s*(?:moreover|furthermore)\s*,"
    r")",
    re.IGNORECASE | re.MULTILINE,
)

# French AI-slop clichés, hype verbs, negative parallelisms, and robotic wrap-ups.
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
    r"\bdécupl(?:er|e|ez|ant|é|ée|és|ées)\b|"
    r"\bsublim(?:er|e|ez|ant|é|ée|és|ées)\b|"
    r"\bsur[- ]mesure\b|"
    r"\bécosystèmes?\b|"
    r"\bsans\s+effort\b|"
    r"\bà\s+la\s+pointe\b|"
    r"\bnouvelle\s+ère\b|"
    r"\bclé\s+en\s+main\b|"
    r"\bau\s+cœur\s+de\b|"
    r"\bce\s+n(?:'|’)est\s+pas\s+(?:seulement|juste|qu(?:'|’)un)\b|"
    r"\bnon\s+seulement\b[^.!?\n]{1,90}\bmais\s+aussi\b|"
    r"\bexcellente\s+question\b|"
    r"\bn(?:'|’)hésitez\s+pas\b|"
    r"\bravi\s+de\s+vous\s+aider\b|"
    r"\ben\s+conclusion\b|"
    r"\bpour\s+conclure\b|"
    r"\ben\s+résumé\b|"
    r"\ben\s+somme\b"
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
    r"Skip to content|Read documentation|Download signed APK|View live captures|"
    r"Click here|Get started|Show more|Try again|Allow once|Unlock profile|"
    r"Connection doctor|Everyday workflows|Quickstart|Explore security model|"
    r"Live device gallery|Filter documentation|No matching sections|"
    r"Please enter|Could not reach|Are you sure"
    r")\b",
    re.IGNORECASE,
)

FENCED_CODE = re.compile(r"```[\s\S]*?```")
INLINE_CODE = re.compile(r"`[^`\n]+`")
HTML_COMMENT = re.compile(r"<!--[\s\S]*?-->")
MD_LINK = re.compile(r"\[([^\]]+)\]\([^)\n]+\)")
MD_LINK_TARGET = re.compile(r"\]\([^)\n]+\)")
RAW_URL = re.compile(r"https?://\S+")
TEMPLATE_TOKEN = re.compile(r"\{\{\s*[a-z0-9_]+\s*\}\}")
ANDROID_FMT = re.compile(r"%(?:\d+\$)?[sdfoxegc%]")
MD_HEADING = re.compile(r"^(#{1,6})\s+(.+?)\s*#*\s*$")
EXPLICIT_LANG_SWITCH_LINE = re.compile(
    r"(?:Documentation en français|English README|Site français|Portail français|Version française|English version)",
    re.IGNORECASE,
)

# Multi-word proper nouns & brand/OS feature names allowed with internal capitals.
PROPER_PHRASES = (
    "Hermes Mobile",
    "LLM-4-People",
    "GitHub Pages",
    "GitHub Releases",
    "GitHub Actions",
    "Google Play",
    "Play Store",
    "Android Keystore",
    "Android Studio",
    "Android Auto",
    "Android TV",
    "Android Security",
    "Class 3",
    "Class-3",
    "Quick Settings",
    "Pixel 9",
    "Pixel 8",
    "Pixel 7",
    "Pixel Tablet",
    "Pixel Fold",
    "Material You",
    "Web Push",
)

# Single-word true proper nouns, brands, languages, platforms, and standard technical nouns.
PROPER_WORDS = {
    "Hermes",
    "LLM-4-People",
    "Android",
    "Google",
    "Pixel",
    "Samsung",
    "Linux",
    "Landlock",
    "Windows",
    "Docker",
    "Tailscale",
    "WireGuard",
    "Caddy",
    "Nginx",
    "Firebase",
    "Keystore",
    "Passkey",
    "Passkeys",
    "SQLite",
    "Python",
    "Java",
    "Kotlin",
    "Gradle",
    "Markdown",
    "Signal",
    "Anthropic",
    "Claude",
    "Gemini",
    "Telegram",
    "Matrix",
    "Nextcloud",
    "Ntfy",
    "Atelier",
    "Wi-Fi",
    "Bluetooth",
    "Chromium",
    "Firefox",
    "F-Droid",
    "Wikipedia",
    "Ubuntu",
    "Debian",
    "Fedora",
    "Termux",
    "Apache",
    "Bash",
    "Cron",
    "Ctrl",
    "Cmd",
    "Alt",
    "Shift",
    "Esc",
    "Enter",
    "Space",
    "Tab",
    "France",
    "Canada",
    "Europe",
    "Paris",
    "Montréal",
    "Montreal",
    "Quebec",
    "Québec",
}

EN_ONLY_PROPER_WORDS = {
    "I",
    "I'm",
    "I've",
    "I'll",
    "I'd",
    "I’m",
    "I’ve",
    "I’ll",
    "I’d",
    "English",
    "French",
    "Français",
    "Monday",
    "Tuesday",
    "Wednesday",
    "Thursday",
    "Friday",
    "Saturday",
    "Sunday",
    "January",
    "February",
    "March",
    "April",
    "May",
    "June",
    "July",
    "August",
    "September",
    "October",
    "November",
    "December",
}

FR_ONLY_PROPER_WORDS = {
    "Français",
    "English",
    "Cerveau",
}

FR_ELISION = re.compile(r"^(?:[cdjlmnst]|qu)['’](.+)$", re.IGNORECASE)
CLAUSE_SPLIT = re.compile(r"(?:[.!?](?:\s+|$)|[—–·|•→\n\t]|:\s+|\s+/\s+)")
WORD_TOKEN = re.compile(r"[A-Za-zÀ-ÿ0-9][A-Za-zÀ-ÿ0-9'’+\-/]*")
SENTENCE_CASE_HTML_TAGS = {"title", "h1", "h2", "h3", "h4", "button", "figcaption"}


def _is_camelcase_identifier(token):
    """True for single alphanumeric CamelCase/PascalCase identifiers like GitHub, UnifiedPush, TalkBack."""
    if not token.isalnum():
        return False
    has_lower = False
    for ch in token:
        if ch.islower():
            has_lower = True
        elif ch.isupper() and has_lower:
            return True
    return False


def _is_allowed_capitalized_word(word, lang):
    """Return True when a capitalized token is an acronym, CamelCase identifier, or proper noun."""
    # All-uppercase acronyms/codes (API, APK, HTTPS, SHA-256, R8, FTS5, WCAG, 2FA, etc.)
    letters = [c for c in word if c.isalpha()]
    if not letters:
        return True
    if all(c.isupper() for c in letters):
        return True
    if _is_camelcase_identifier(word):
        return True
    if word in PROPER_WORDS:
        return True
    if lang == "en" and word in EN_ONLY_PROPER_WORDS:
        return True
    if lang == "fr" and word in FR_ONLY_PROPER_WORDS:
        return True
    # Possessive English proper nouns (e.g., Hermes's, Android's, Google's).
    for suffix in ("'s", "’s"):
        if word.endswith(suffix):
            return _is_allowed_capitalized_word(word[:-2], lang)
    # French elisions (e.g., d'Android, l'Atelier, d'Hermes).
    if lang == "fr":
        m = FR_ELISION.match(word)
        if m:
            return _is_allowed_capitalized_word(m.group(1), lang)
    return False


def check_sentence_case(text, lang):
    """Return offending Title-Case words in a heading, button, caption, or UI label."""
    if not text or not text.strip():
        return []
    cleaned = INLINE_CODE.sub(" ", text)
    cleaned = TEMPLATE_TOKEN.sub(" ", cleaned)
    cleaned = RAW_URL.sub(" ", cleaned)
    cleaned = MD_LINK.sub(r"\1", cleaned)
    cleaned = ANDROID_FMT.sub(" ", cleaned)
    cleaned = cleaned.replace("*", "").replace("_", "")

    offenders = []
    for segment in CLAUSE_SPLIT.split(cleaned):
        seg = segment.strip()
        if not seg:
            continue
        # Mask allowed multi-word proper phrases in this segment before word inspection.
        masked_spans = []
        for phrase in PROPER_PHRASES:
            start = 0
            while True:
                idx = seg.find(phrase, start)
                if idx < 0:
                    break
                end = idx + len(phrase)
                left_ok = idx == 0 or not seg[idx - 1].isalnum()
                right_ok = end == len(seg) or not seg[end].isalnum()
                if left_ok and right_ok:
                    masked_spans.append((idx, end))
                start = end

        def _in_masked(pos, end_pos):
            return any(a <= pos and end_pos <= b for a, b in masked_spans)

        seen_alpha_word = False
        for m in WORD_TOKEN.finditer(seg):
            token = m.group(0)
            if not any(c.isalpha() for c in token):
                continue
            # Skip file paths / slash routes / key combos already formatted cleanly.
            if "/" in token and token.startswith("/"):
                seen_alpha_word = True
                continue
            is_first = not seen_alpha_word
            seen_alpha_word = True

            if _in_masked(m.start(), m.end()):
                continue

            if is_first:
                # Even on the first word, reject Title-Case hyphenated compounds like "End-to-End".
                if "-" in token and not _is_allowed_capitalized_word(token, lang):
                    parts = token.split("-")
                    for part in parts[1:]:
                        if part and part[0].isupper() and any(c.islower() for c in part[1:]):
                            if not _is_allowed_capitalized_word(part, lang):
                                offenders.append(token)
                                break
                continue

            # Check whole token first (handles Wi-Fi, F-Droid, Ctrl+K, CamelCase, acronyms, proper nouns).
            if _is_allowed_capitalized_word(token, lang):
                continue

            # Check slash-separated segments first (e.g. LLM-4-People/hermes-agent), then hyphen/plus parts.
            slash_parts = token.split("/")
            flagged = False
            for sp in slash_parts:
                if not sp or _is_allowed_capitalized_word(sp, lang):
                    continue
                for part in re.split(r"[-+]", sp):
                    if not part:
                        continue
                    if part[0].isupper() and any(c.islower() for c in part[1:]):
                        if not _is_allowed_capitalized_word(part, lang):
                            offenders.append(token)
                            flagged = True
                            break
                if flagged:
                    break
    return offenders


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
    """Extract visible prose plus heading/button/title/caption elements for sentence-case checks."""

    def __init__(self, page_lang):
        super().__init__()
        self.page_lang = page_lang
        self.skip_depth = 0
        self.chunks = []
        self.sentence_case_items = []
        self._sc_stack = []

    def handle_starttag(self, tag, attrs):
        attr_map = dict(attrs)
        void_tag = tag in ("meta", "link", "img", "br", "hr", "input")
        if self.skip_depth > 0:
            if not void_tag:
                self.skip_depth += 1
            return

        lang_attr = (attr_map.get("lang") or "").strip().lower()
        is_lang_switch = "data-lang-switch" in attr_map or (
            lang_attr and not lang_attr.startswith(self.page_lang) and tag != "html"
        )
        if tag in ("script", "style", "code", "pre") or is_lang_switch:
            if not void_tag:
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

        classes = set((attr_map.get("class") or "").split())
        if not void_tag and (tag in SENTENCE_CASE_HTML_TAGS or "btn" in classes):
            self._sc_stack.append([tag, self.getpos()[0], []])

    def handle_endtag(self, tag):
        if self.skip_depth > 0:
            self.skip_depth -= 1
            return
        if self._sc_stack and self._sc_stack[-1][0] == tag:
            closed_tag, line_no, parts = self._sc_stack.pop()
            joined = " ".join(p.strip() for p in parts if p.strip())
            if joined:
                self.sentence_case_items.append((line_no, closed_tag, joined))

    def handle_data(self, data):
        if self.skip_depth > 0:
            return
        text = data.strip()
        if text:
            self.chunks.append((self.getpos()[0], text))
            for item in self._sc_stack:
                item[2].append(text)


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
    raw = path.read_text(encoding="utf-8")
    cleaned = strip_markdown_code(raw)
    lines = list(enumerate(cleaned.splitlines(), 1))
    issues = check_text_lines(rel, lines, lang, check_em_dash=True)
    # Check sentence case on headings (outside fenced code blocks).
    no_fenced = FENCED_CODE.sub(lambda m: "\n" * m.group(0).count("\n"), raw)
    no_fenced = HTML_COMMENT.sub(lambda m: "\n" * m.group(0).count("\n"), no_fenced)
    for line_no, raw_line in enumerate(no_fenced.splitlines(), 1):
        m = MD_HEADING.match(raw_line)
        if not m:
            continue
        heading = m.group(2).strip()
        if EXPLICIT_LANG_SWITCH_LINE.search(heading):
            continue
        offenders = check_sentence_case(heading, lang)
        if offenders:
            issues.append(
                f"{rel}:{line_no}: Title Case in heading {heading!r} (offending: {', '.join(offenders)}; use sentence case)"
            )
    return issues


def check_html_file(path, root, lang):
    rel = path.relative_to(root).as_posix()
    extractor = _HtmlProseExtractor(lang)
    extractor.feed(path.read_text(encoding="utf-8"))
    issues = check_text_lines(rel, extractor.chunks, lang, check_em_dash=True)
    for line_no, tag, text in extractor.sentence_case_items:
        if EXPLICIT_LANG_SWITCH_LINE.search(text):
            continue
        offenders = check_sentence_case(text, lang)
        if offenders:
            issues.append(
                f"{rel}:{line_no}: Title Case in <{tag}> {text!r} (offending: {', '.join(offenders)}; use sentence case)"
            )
    return issues


def check_android_strings_file(path, root, lang):
    rel = path.relative_to(root).as_posix()
    issues = []
    try:
        tree = ET.parse(path)
    except ET.ParseError as err:
        return [f"{rel}: invalid XML ({err})"]
    entries = []
    named_entries = []
    idx = 0
    root_el = tree.getroot()
    for el in root_el.findall("string"):
        if el.attrib.get("translatable") == "false":
            continue
        text = "".join(el.itertext()).strip()
        if text:
            idx += 1
            entries.append((idx, text))
            named_entries.append((el.attrib.get("name", f"string#{idx}"), text))
    for container_tag in ("plurals", "string-array"):
        for container in root_el.findall(container_tag):
            if container.attrib.get("translatable") == "false":
                continue
            cname = container.attrib.get("name", container_tag)
            for item in container.findall("item"):
                if item.attrib.get("translatable") == "false":
                    continue
                text = "".join(item.itertext()).strip()
                if text:
                    idx += 1
                    entries.append((idx, text))
                    named_entries.append((f"{cname}/item", text))
    # Individual UI strings are short; check clichés & mixed-language per string (skip doc-level em-dash density).
    for item_issue in check_text_lines(rel, entries, lang, check_em_dash=False):
        issues.append(item_issue)
    for key_name, text in named_entries:
        offenders = check_sentence_case(text, lang)
        if offenders:
            issues.append(
                f"{rel}:{key_name}: Title Case in Android resource {text!r} (offending: {', '.join(offenders)}; use sentence case)"
            )
    return issues


def check_repo(repo_root=ROOT, include_android_strings=True):
    """Scan all managed English/French docs, doc sources, GitHub Pages, and Android string resources."""
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
        sources_dir = docs_dir / "sources"
        if sources_dir.is_dir():
            for p in sorted(sources_dir.rglob("*.md.in")):
                rel_src = p.relative_to(sources_dir).as_posix()
                src_lang = "fr" if rel_src.startswith("docs/fr/") else "en"
                problems.extend(check_markdown_file(p, root, src_lang))

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
        print(f"Writing quality, sentence case & language purity check passed ({target}).")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
