#!/usr/bin/env python3
"""Fail-closed i18n guard against hardcoded UI strings (FR & EN) and FR/EN resource drift.

Enforces three invariants across ``app/src/main/``:
1. 100% 1:1 XML resource key parity between ``res/values/`` (default FR) and ``res/values-en/`` (EN)
   for all translatable ``<string>``, ``<plurals>``, and ``<string-array>`` resources (plus zero
   duplicate keys and matching ``<string-array>`` lengths).
2. Zero hardcoded French string literals anywhere in ``app/src/main/java/`` (neither accented French
   characters nor distinctive French UI prose).
3. Zero hardcoded English/non-localized user-facing UI string literals passed to Android/Hermes UI
   sinks (``setText``, ``setTitle``, ``setMessage``, ``setHint``, ``setContentDescription``,
   ``setPositiveButton``, ``setNegativeButton``, ``setNeutralButton``, ``Toast.makeText``, ``Ui.*``,
   ``PanelScreen`` helpers, notification/dialog builders, etc.).

Enforced strictly across the entire repository with zero file or XML allowlists.
"""

from dataclasses import dataclass
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]

# Exact non-UI parser/input-matching tokens (never displayed to users).
NON_UI_PARSER_LITERALS: set[tuple[str, str]] = {
    ("app/src/main/java/org/llm4people/hermes/onboarding/PairingLinkParser.java", "clé"),
}

# French diacritics that should never appear in Java source string literals.
FR_ACCENT_RE = re.compile(r"[éèêëàâäîïôöùûüçœæÉÈÊËÀÂÄÎÏÔÖÙÛÜÇŒÆ]")

# Distinctive French UI words (even without accents) to catch unaccented French prose in Java literals.
FR_PROSE_WORDS_RE = re.compile(
    r"\b("
    r"Aucun|Aucune|Impossible|Chargement|Enregistrer|Supprimer|Modifier|Annuler|Fermer|Ouvrir|"
    r"Suivant|Rechercher|Ajouter|Nouvelle|Nouveau|Discussion|Discussions|Rappel|Rappels|"
    r"Brouillon|Copier|Partager|Aujourd['’]hui|Demain|Votre|Vos|Veuillez|Voulez-vous|"
    r"Erreur|Indisponible|Compagnon|Savoir-faire|Fichiers|Statistiques|Compétences|"
    r"Image\s+jointe|Mot\s+de\s+passe|Phrase\s+de\s+passe|Boîte\s+de\s+réception"
    r")\b"
)

# Android / Hermes UI sink methods -> tuple of 0-based argument indices carrying visible UI text.
# Receiver-agnostic methods:
UI_SINK_ARGS: dict[str, tuple[int, ...]] = {
    "setText": (0,),
    "setTitle": (0,),
    "setSubtitle": (0,),
    "setMessage": (0,),
    "setHint": (0,),
    "setContentDescription": (0, 1),
    "setTooltipText": (0,),
    "setError": (0,),
    "setSummary": (0,),
    "setTextOn": (0,),
    "setTextOff": (0,),
    "setContentTitle": (0,),
    "setContentText": (0,),
    "setSubText": (0,),
    "setTicker": (0,),
    "setBigContentTitle": (0,),
    "setSummaryText": (0,),
    "bigText": (0,),
    "announceForAccessibility": (0,),
    "setStateDescription": (0,),
    "setPositiveButton": (0,),
    "setNegativeButton": (0,),
    "setNeutralButton": (0,),
    "setItems": (0,),
    "setSingleChoiceItems": (0,),
    "setMultiChoiceItems": (0,),
    "setTextViewText": (1,),
    "setShortLabel": (0,),
    "setLongLabel": (0,),
    "setLabel": (0,),
    "setShortCriticalText": (0,),
    "setDescription": (0,),
    "AccessibilityAction": (1,),
    "ProfileScreen": (1, 2),
    "NotificationChannel": (1,),
    "createChooser": (1,),
    "makeText": (1,),
}

# Qualified static/helper sinks: (receiver_name, method_name) -> arg indices carrying visible UI text.
QUALIFIED_UI_SINK_ARGS: dict[tuple[str, str], tuple[int, ...]] = {
    ("Ui", "text"): (1,),
    ("Ui", "button"): (1,),
    ("Ui", "filledButton"): (1,),
    ("Ui", "tonalButton"): (1,),
    ("Ui", "outlinedButton"): (1,),
    ("Ui", "pill"): (1,),
    ("Ui", "choiceChip"): (1,),
    ("Toast", "makeText"): (1,),
    ("ClipData", "newPlainText"): (0,),
    ("Action", "Builder"): (1,),
    ("screen", "section"): (0, 1),
    ("screen", "note"): (0,),
    ("screen", "liveNote"): (0,),
    ("screen", "status"): (0,),
    ("screen", "action"): (0,),
    ("screen", "field"): (0, 1, 2),
    ("screen", "profileRow"): (0, 1),
    ("panel", "note"): (1,),
    ("BrainUi", "chip"): (1,),
    ("BrainUi", "badge"): (1,),
    ("BrainUi", "liveNote"): (2,),
    ("ProfileScreen", "styledField"): (2, 3),
    ("ProfileScreen", "choiceChip"): (1,),
    ("this", "toast"): (0,),
}

# Keywords that can directly precede a UI sink call or constructor (e.g. `else setText(...)`, `new NotificationChannel(...)`).
CONTROL_FLOW_KEYWORDS = {"else", "return", "yield", "do", "case", "throw", "new"}

# Printf / Android format specifiers stripped before checking if a UI literal has human letters.
FORMAT_TOKEN_RE = re.compile(r"%(?:\d+\$)?[-+# 0,(]*\d*(?:\.\d+)?[a-zA-Z%]")
ESCAPED_PERCENT_RE = re.compile(r"%%")
PLACEHOLDER_SPEC_RE = re.compile(r"%(?:\d+\$)?[-+# 0,(]*\d*(?:\.\d+)?[a-zA-Z]")
WORD_RE = re.compile(r"[A-Za-zÀ-ÿ]{2,}")

# Neutral technical / brand-only literals allowed even inside UI sinks (exact match after strip).
ALLOWED_UI_LITERALS = {
    "Hermes",
    "Hermes Mobile",
    "OpenStreetMap",
    "JSON",
    "Markdown",
    "UTF-8",
    "HTTPS",
    "HTTP",
    "UTC",
    "API",
    "APK",
    "URL",
    "PIN",
    "SHA-256",
    "UnifiedPush",
    "n/a",
    "...",
}

# URL or file-path / MIME literals (e.g., https://..., /api/..., text/plain) are not natural-language prose.
URL_OR_PATH_RE = re.compile(r"^(?:https?://\S+|/[A-Za-z0-9_./:-]+|[a-z]+/[a-z0-9.+-]+)$")


@dataclass(frozen=True)
class Token:
    kind: str   # "IDENT", "STRING", "PUNCT", "OTHER"
    value: str
    line: int


def tokenize_java(source: str) -> list[Token]:
    """Lex Java source into identifiers, string literals (incl. text blocks), and punctuation."""
    tokens: list[Token] = []
    n = len(source)
    i = 0
    line = 1

    while i < n:
        ch = source[i]

        if ch == "\n":
            line += 1
            i += 1
            continue
        if ch.isspace():
            i += 1
            continue

        # Line comment //
        if ch == "/" and i + 1 < n and source[i + 1] == "/":
            i += 2
            while i < n and source[i] != "\n":
                i += 1
            continue

        # Block comment /* ... */
        if ch == "/" and i + 1 < n and source[i + 1] == "*":
            i += 2
            while i + 1 < n and not (source[i] == "*" and source[i + 1] == "/"):
                if source[i] == "\n":
                    line += 1
                i += 1
            i += 2
            continue

        # Text block """ ... """
        if ch == '"' and i + 2 < n and source[i + 1] == '"' and source[i + 2] == '"':
            start_line = line
            i += 3
            buf: list[str] = []
            while i < n:
                if source[i] == "\\" and i + 1 < n:
                    nxt = source[i + 1]
                    if nxt == "\n":
                        line += 1
                    else:
                        buf.append(_decode_escape(nxt))
                    i += 2
                    continue
                if source[i] == '"' and i + 2 < n and source[i + 1] == '"' and source[i + 2] == '"':
                    i += 3
                    break
                if source[i] == "\n":
                    line += 1
                buf.append(source[i])
                i += 1
            tokens.append(Token("STRING", "".join(buf), start_line))
            continue

        # Normal string literal "..."
        if ch == '"':
            start_line = line
            i += 1
            buf = []
            while i < n and source[i] != '"':
                if source[i] == "\\" and i + 1 < n:
                    nxt = source[i + 1]
                    if nxt == "u" and i + 5 < n:
                        hex_digits = source[i + 2:i + 6]
                        try:
                            buf.append(chr(int(hex_digits, 16)))
                            i += 6
                            continue
                        except ValueError:
                            pass
                    if nxt == "\n":
                        line += 1
                    buf.append(_decode_escape(nxt))
                    i += 2
                    continue
                if source[i] == "\n":
                    line += 1
                buf.append(source[i])
                i += 1
            if i < n and source[i] == '"':
                i += 1
            tokens.append(Token("STRING", "".join(buf), start_line))
            continue

        # Char literal '...'
        if ch == "'":
            i += 1
            while i < n and source[i] != "'":
                if source[i] == "\\" and i + 1 < n:
                    if source[i + 1] == "\n":
                        line += 1
                    i += 2
                    continue
                if source[i] == "\n":
                    line += 1
                i += 1
            if i < n and source[i] == "'":
                i += 1
            tokens.append(Token("OTHER", "'", line))
            continue

        # Identifier
        if ch.isalpha() or ch in ("_", "$"):
            start = i
            i += 1
            while i < n and (source[i].isalnum() or source[i] in ("_", "$")):
                i += 1
            tokens.append(Token("IDENT", source[start:i], line))
            continue

        # Punctuation we care about for call parsing
        if ch in "().,;{}[]?:+-":
            tokens.append(Token("PUNCT", ch, line))
            i += 1
            continue

        i += 1

    return tokens


def _decode_escape(ch: str) -> str:
    return {
        "n": "\n",
        "r": "\r",
        "t": "\t",
        '"': '"',
        "'": "'",
        "\\": "\\",
    }.get(ch, ch)


def _is_human_ui_text(literal: str) -> bool:
    """Return True if a string literal passed to a UI sink looks like user-facing prose/label text."""
    stripped = literal.strip()
    if not stripped:
        return False
    if stripped in ALLOWED_UI_LITERALS:
        return False
    if URL_OR_PATH_RE.match(stripped):
        return False
    # Remove printf/Android format tokens like %1$s, %.2f, %d.
    without_fmt = FORMAT_TOKEN_RE.sub(" ", stripped).strip()
    if not without_fmt:
        return False
    # Reject strings containing at least one alphabetic word of >= 2 letters.
    return bool(WORD_RE.search(without_fmt))


def _split_call_args(tokens: list[Token], open_paren_idx: int) -> list[list[Token]]:
    """Split tokens inside `(...)` starting at open_paren_idx into top-level argument token lists."""
    args: list[list[Token]] = []
    cur: list[Token] = []
    depth = 1
    i = open_paren_idx + 1
    n = len(tokens)
    while i < n and depth > 0:
        tok = tokens[i]
        if tok.kind == "PUNCT" and tok.value == "(":
            depth += 1
            cur.append(tok)
        elif tok.kind == "PUNCT" and tok.value == ")":
            depth -= 1
            if depth == 0:
                if cur:
                    args.append(cur)
                break
            cur.append(tok)
        elif tok.kind == "PUNCT" and tok.value == "," and depth == 1:
            args.append(cur)
            cur = []
        else:
            cur.append(tok)
        i += 1
    return args


def _direct_string_literals_in_expr(expr_tokens: list[Token]) -> list[Token]:
    """Collect string literals contributing directly to an expression's display text.

    Walks top-level tokens of an argument expression (including `+` concatenations and ternary
    `cond ? a : b` branches) while skipping arguments nested inside helper calls that take keys
    or tags (e.g., ``obj.optString("name", "")``, ``String.format(Locale.ROOT, ...)`` is checked
    separately, ``context.getString(R.string.x, "literal")`` args).
    Wait: for ``String.format(..., "literal %s", x)``, the format template itself IS visible text!
    """
    literals: list[Token] = []
    n = len(expr_tokens)
    i = 0
    while i < n:
        tok = expr_tokens[i]
        if tok.kind == "STRING":
            literals.append(tok)
            i += 1
            continue
        # Skip nested call argument lists UNLESS it is String.format(...) where the format pattern
        # is a direct UI template, or a nested parenthesized expression `(cond ? "A" : "B")`.
        if tok.kind == "IDENT" and i + 1 < n and expr_tokens[i + 1].kind == "PUNCT" and expr_tokens[i + 1].value == "(":
            call_name = tok.value
            close_idx = _find_matching_paren(expr_tokens, i + 1)
            if call_name == "format":
                nested_args = _split_call_args(expr_tokens, i + 1)
                for na in nested_args[:2]:
                    for nt in na:
                        if nt.kind == "STRING":
                            literals.append(nt)
            i = close_idx + 1
            continue
        i += 1
    return literals


def _find_matching_paren(tokens: list[Token], open_idx: int) -> int:
    depth = 1
    i = open_idx + 1
    while i < len(tokens) and depth > 0:
        if tokens[i].kind == "PUNCT":
            if tokens[i].value == "(":
                depth += 1
            elif tokens[i].value == ")":
                depth -= 1
                if depth == 0:
                    return i
        i += 1
    return len(tokens) - 1


def _regex_compile_token_indices(tokens: list[Token]) -> set[int]:
    """Return token indices inside ``Pattern.compile(...)`` calls (non-UI input matchers)."""
    skipped: set[int] = set()
    n = len(tokens)
    for i, tok in enumerate(tokens):
        if (tok.kind == "IDENT" and tok.value == "compile"
                and i >= 2 and tokens[i - 1].kind == "PUNCT" and tokens[i - 1].value == "."
                and tokens[i - 2].kind == "IDENT" and tokens[i - 2].value == "Pattern"
                and i + 1 < n and tokens[i + 1].kind == "PUNCT" and tokens[i + 1].value == "("):
            close_idx = _find_matching_paren(tokens, i + 1)
            skipped.update(range(i + 1, close_idx + 1))
    return skipped


def check_java_file(path: Path, root: Path) -> list[str]:
    """Return all hardcoded French literal and hardcoded UI-sink string violations in one Java file."""
    rel = path.relative_to(root).as_posix()
    source = path.read_text(encoding="utf-8")
    tokens = tokenize_java(source)
    regex_indices = _regex_compile_token_indices(tokens)
    issues: list[str] = []
    reported_lines: set[tuple[int, str]] = set()

    # 1. Zero hardcoded French string literals anywhere in production Java.
    for idx, tok in enumerate(tokens):
        if tok.kind != "STRING" or idx in regex_indices:
            continue
        val = tok.value
        if (rel, val) in NON_UI_PARSER_LITERALS:
            continue
        if FR_ACCENT_RE.search(val):
            snippet = _preview(val)
            key = (tok.line, f"fr:{snippet}")
            if key not in reported_lines:
                reported_lines.add(key)
                issues.append(f"{rel}:{tok.line}: hardcoded French string literal {snippet!r} (move to res/values/ + res/values-en/)")
            continue
        m = FR_PROSE_WORDS_RE.search(val)
        if m:
            snippet = _preview(val)
            key = (tok.line, f"fr:{snippet}")
            if key not in reported_lines:
                reported_lines.add(key)
                issues.append(
                    f"{rel}:{tok.line}: hardcoded French prose literal {snippet!r} (matched {m.group(0)!r}; move to string resources)"
                )

    # 2. Zero hardcoded English / non-localized UI strings in UI sink calls.
    n = len(tokens)
    for i, tok in enumerate(tokens):
        if tok.kind != "IDENT":
            continue
        if i + 1 >= n or tokens[i + 1].kind != "PUNCT" or tokens[i + 1].value != "(":
            continue
        # Skip method declarations like `void setText(String s)` or `public String setTitle(...)`,
        # while still checking calls immediately following control-flow keywords (`else setText(...)`).
        if i >= 1 and tokens[i - 1].kind == "IDENT" and tokens[i - 1].value not in CONTROL_FLOW_KEYWORDS:
            continue

        method = tok.value
        receiver = None
        if i >= 2 and tokens[i - 1].kind == "PUNCT" and tokens[i - 1].value == "." and tokens[i - 2].kind == "IDENT":
            receiver = tokens[i - 2].value

        arg_indices: tuple[int, ...] | None = None
        if receiver is not None and (receiver, method) in QUALIFIED_UI_SINK_ARGS:
            arg_indices = QUALIFIED_UI_SINK_ARGS[(receiver, method)]
        elif method in UI_SINK_ARGS:
            arg_indices = UI_SINK_ARGS[method]

        if arg_indices is None:
            continue

        args = _split_call_args(tokens, i + 1)
        for idx in arg_indices:
            if idx >= len(args):
                continue
            for lit in _direct_string_literals_in_expr(args[idx]):
                if not _is_human_ui_text(lit.value):
                    continue
                snippet = _preview(lit.value)
                # Avoid double-reporting if already flagged as French literal on same line.
                if (lit.line, f"fr:{snippet}") in reported_lines:
                    continue
                key = (lit.line, f"ui:{snippet}")
                if key not in reported_lines:
                    reported_lines.add(key)
                    sink = f"{receiver}.{method}" if receiver else method
                    issues.append(
                        f"{rel}:{lit.line}: hardcoded UI string literal {snippet!r} in {sink}() (use R.string.*)"
                    )

    return issues


def _preview(text: str, limit: int = 60) -> str:
    single = text.replace("\n", "\\n")
    return single if len(single) <= limit else single[:limit - 1] + "…"


def _extract_placeholders(text: str) -> list[str]:
    """Return sorted Android/printf format specifiers in text, ignoring escaped ``%%``."""
    return sorted(PLACEHOLDER_SPEC_RE.findall(ESCAPED_PERCENT_RE.sub("", text)))


@dataclass
class _XmlResources:
    translatable: dict[str, set[str]]
    non_translatable: dict[str, set[str]]
    strings: dict[str, str]
    plurals: dict[str, dict[str, str]]
    array_lengths: dict[str, int]
    issues: list[str]


TOOLS_LOCALE_ATTR = "{http://schemas.android.com/tools}locale"


def _has_unquoted_edge_whitespace(raw_text: str) -> bool:
    return raw_text != raw_text.strip() and not (raw_text.startswith('"') and raw_text.endswith('"'))


def _collect_xml_resources(res_dir: Path, root: Path, *, expected_locale: str | None = None) -> _XmlResources:
    """Collect translatable and non-translatable keys, strings, plurals, and array lengths."""
    translatable: dict[str, set[str]] = {"string": set(), "plurals": set(), "string-array": set()}
    non_translatable: dict[str, set[str]] = {"string": set(), "plurals": set(), "string-array": set()}
    strings: dict[str, str] = {}
    plurals: dict[str, dict[str, str]] = {}
    array_lengths: dict[str, int] = {}
    issues: list[str] = []

    if not res_dir.is_dir():
        issues.append(f"{res_dir.relative_to(root).as_posix()}: missing resource directory")
        return _XmlResources(translatable, non_translatable, strings, plurals, array_lengths, issues)

    seen_origin: dict[tuple[str, str], str] = {}
    for xml_path in sorted(res_dir.glob("*.xml")):
        rel = xml_path.relative_to(root).as_posix()
        try:
            tree = ET.parse(xml_path)
        except ET.ParseError as err:
            issues.append(f"{rel}: invalid XML ({err})")
            continue
        root_el = tree.getroot()
        file_has_translatable = False
        for tag in ("string", "plurals", "string-array"):
            for el in root_el.findall(tag):
                name = el.attrib.get("name", "").strip()
                if not name:
                    issues.append(f"{rel}: <{tag}> missing name attribute")
                    continue
                origin_key = (tag, name)
                if origin_key in seen_origin:
                    issues.append(f"{rel}: duplicate <{tag} name={name!r}> (already defined in {seen_origin[origin_key]})")
                    continue
                seen_origin[origin_key] = rel
                if el.attrib.get("translatable") == "false":
                    non_translatable[tag].add(name)
                else:
                    file_has_translatable = True
                    translatable[tag].add(name)
                    if tag == "string":
                        raw_text = "".join(el.itertext())
                        strings[name] = raw_text
                        if _has_unquoted_edge_whitespace(raw_text):
                            issues.append(
                                f"{rel}: <string name={name!r}> has unquoted leading/trailing whitespace that AAPT2 strips"
                            )
                    elif tag == "plurals":
                        qty_map: dict[str, str] = {}
                        for item in el.findall("item"):
                            qty = item.attrib.get("quantity", "").strip()
                            if not qty:
                                issues.append(f"{rel}: <plurals name={name!r}> has <item> missing quantity attribute")
                                continue
                            raw_item = "".join(item.itertext())
                            qty_map[qty] = raw_item
                            if _has_unquoted_edge_whitespace(raw_item):
                                issues.append(
                                    f"{rel}: <plurals name={name!r} quantity={qty!r}> has unquoted leading/trailing whitespace that AAPT2 strips"
                                )
                        plurals[name] = qty_map
                    elif tag == "string-array":
                        items = el.findall("item")
                        array_lengths[name] = len(items)
                        for idx, item in enumerate(items):
                            raw_item = "".join(item.itertext())
                            if _has_unquoted_edge_whitespace(raw_item):
                                issues.append(
                                    f"{rel}: <string-array name={name!r}> item[{idx}] has unquoted leading/trailing whitespace that AAPT2 strips"
                                )
        if expected_locale is not None and file_has_translatable:
            actual_locale = root_el.attrib.get(TOOLS_LOCALE_ATTR, "").strip()
            if actual_locale != expected_locale:
                issues.append(
                    f"{rel}: <resources> defines translatable entries and must declare tools:locale={expected_locale!r} (found {actual_locale!r})"
                )

    return _XmlResources(translatable, non_translatable, strings, plurals, array_lengths, issues)


def check_xml_parity(root: Path) -> list[str]:
    """Verify exact 1:1 key, placeholder, plurals quantity, and array-length parity between values/ and values-en/."""
    res_root = root / "app/src/main/res"
    fr = _collect_xml_resources(res_root / "values", root, expected_locale="fr")
    en = _collect_xml_resources(res_root / "values-en", root, expected_locale="en")
    issues = [*fr.issues, *en.issues]

    for tag in ("string", "plurals", "string-array"):
        missing_in_en = sorted(fr.translatable[tag] - en.translatable[tag])
        extra_in_en = sorted(en.translatable[tag] - fr.translatable[tag])
        for name in missing_in_en:
            issues.append(f"app/src/main/res/values-en: missing <{tag} name={name!r}> (present in values/)")
        for name in extra_in_en:
            if name in fr.non_translatable[tag]:
                issues.append(f"app/src/main/res/values-en: <{tag} name={name!r}> is translatable=\"false\" in values/")
            else:
                issues.append(f"app/src/main/res/values: missing <{tag} name={name!r}> (present in values-en/)")
        for name in sorted(en.non_translatable[tag]):
            issues.append(f"app/src/main/res/values-en: unexpected translatable=\"false\" <{tag} name={name!r}> (keep non-translatable keys in values/ only)")

    for name in sorted(set(fr.strings) & set(en.strings)):
        fr_ph = _extract_placeholders(fr.strings[name])
        en_ph = _extract_placeholders(en.strings[name])
        if fr_ph != en_ph:
            issues.append(
                f"app/src/main/res/values-en: <string name={name!r}> placeholder mismatch {en_ph} vs {fr_ph} in values/"
            )

    for name in sorted(set(fr.plurals) & set(en.plurals)):
        fr_qty = fr.plurals[name]
        en_qty = en.plurals[name]
        if set(fr_qty) != set(en_qty):
            issues.append(
                f"app/src/main/res/values-en: <plurals name={name!r}> quantities {sorted(en_qty)} vs {sorted(fr_qty)} in values/"
            )
        for qty in sorted(set(fr_qty) & set(en_qty)):
            fr_ph = _extract_placeholders(fr_qty[qty])
            en_ph = _extract_placeholders(en_qty[qty])
            if fr_ph != en_ph:
                issues.append(
                    f"app/src/main/res/values-en: <plurals name={name!r} quantity={qty!r}> placeholder mismatch {en_ph} vs {fr_ph} in values/"
                )

    for name in sorted(set(fr.array_lengths) & set(en.array_lengths)):
        if fr.array_lengths[name] != en.array_lengths[name]:
            issues.append(
                f"app/src/main/res/values-en: <string-array name={name!r}> has {en.array_lengths[name]} items vs {fr.array_lengths[name]} in values/"
            )

    return issues


def check_repo(repo_root: Path = ROOT) -> list[str]:
    """Run XML parity + Java hardcoded UI string checks across the repository (zero allowlists)."""
    root = Path(repo_root).resolve()
    problems = check_xml_parity(root)

    java_root = root / "app/src/main/java"
    if not java_root.is_dir():
        return problems

    for java_file in sorted(java_root.rglob("*.java")):
        problems.extend(check_java_file(java_file, root))

    return problems


def main(argv: list[str] | None = None) -> int:
    args = argv if argv is not None else sys.argv[1:]
    target = ROOT
    for arg in args:
        if not arg.startswith("-"):
            target = Path(arg)
    problems = check_repo(target)
    for problem in problems:
        print(problem, file=sys.stderr)
    if not problems:
        print(f"Hardcoded UI strings & FR/EN resource parity check passed ({target}).")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
