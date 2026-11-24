#!/usr/bin/env python3
"""Render repository Markdown and GitHub Pages HTML from declared sources and live code facts."""

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT / "ci") not in sys.path:
    sys.path.insert(0, str(ROOT / "ci"))
from release_metadata import release_apk_name

SOURCE = Path("docs/sources")
TOKEN = re.compile(r"{{\s*([a-z][a-z0-9_]*)\s*}}")
ANDROID_RELEASE_BY_API = {
    28: "9",
    29: "10",
    30: "11",
    31: "12",
    32: "12L",
    33: "13",
    34: "14",
    35: "15",
    36: "16",
}


def live_facts(root):
    """Derive live factual tokens directly from repository code so docs never drift."""
    derived = {}
    gradle_props = root / "gradle.properties"
    if gradle_props.is_file():
        props = {}
        for line in gradle_props.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
        if "hermesMinSdk" in props:
            derived["min_sdk"] = props["hermesMinSdk"]
            if props["hermesMinSdk"].isdigit():
                derived["min_android"] = ANDROID_RELEASE_BY_API.get(
                    int(props["hermesMinSdk"]), props["hermesMinSdk"]
                )
        if "hermesTargetSdk" in props:
            derived["target_sdk"] = props["hermesTargetSdk"]
            if props["hermesTargetSdk"].isdigit():
                derived["target_android"] = ANDROID_RELEASE_BY_API.get(
                    int(props["hermesTargetSdk"]), props["hermesTargetSdk"]
                )
        if "hermesCompileSdk" in props:
            derived["compile_sdk"] = props["hermesCompileSdk"]
        if "hermesBuildTools" in props:
            derived["build_tools"] = props["hermesBuildTools"]
        if "hermesVersionName" in props:
            derived["version_name"] = props["hermesVersionName"]
            derived["release_apk"] = release_apk_name(props["hermesVersionName"])

    fixture = root / "verification/gateway_fixture.py"
    if fixture.is_file():
        m = re.search(
            r'^PINNED_REVISION\s*=\s*"([0-9a-f]{40})"',
            fixture.read_text(encoding="utf-8"),
            re.MULTILINE,
        )
        if m:
            derived["pinned_revision"] = m.group(1)
            derived["pinned_revision_short"] = m.group(1)[:8]

    config_xml = root / "app/src/main/res/values/config.xml"
    if config_xml.is_file():
        m = re.search(
            r'<integer\s+name="hermes_expected_fork_capabilities">\s*(\d+)\s*</integer>',
            config_xml.read_text(encoding="utf-8"),
        )
        if m:
            derived["expected_fork_capabilities"] = m.group(1)
    if "expected_fork_capabilities" not in derived:
        hermes_config = root / "app/src/main/java/org/llm4people/hermes/HermesConfig.java"
        if hermes_config.is_file():
            m = re.search(
                r"DEFAULT_EXPECTED_FORK_CAPABILITIES\s*=\s*(\d+)",
                hermes_config.read_text(encoding="utf-8"),
            )
            if m:
                derived["expected_fork_capabilities"] = m.group(1)

    templates_xml = root / "app/src/main/res/values/templates_strings.xml"
    if templates_xml.is_file():
        count = len(
            re.findall(
                r'<string\s+name="tpl_[a-z0-9_]+_title">',
                templates_xml.read_text(encoding="utf-8"),
            )
        )
        if count > 0:
            derived["curated_workflows_count"] = str(count)

    return derived


def public_markdown(root):
    listing = subprocess.check_output(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
        cwd=root,
    )
    return {
        Path(item.decode())
        for item in listing.split(b"\0")
        if item and item.lower().endswith(b".md")
    }


def public_localized_site_html(root):
    listing = subprocess.check_output(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
        cwd=root,
    )
    result = set()
    for item in listing.split(b"\0"):
        if not item:
            continue
        rel = Path(item.decode())
        if rel.suffix.lower() == ".html" and len(rel.parts) >= 3 and rel.parts[0] == "site" and rel.parts[1] in ("en", "fr"):
            result.add(rel)
    return result


def render(root, check):
    sources = root / SOURCE
    facts = json.loads((sources / "facts.json").read_text(encoding="utf-8"))
    facts.update(live_facts(root))
    md_outputs = set()
    html_outputs = set()
    problems = []

    templates = sorted(
        list(sources.rglob("*.md.in")) + list(sources.rglob("*.html.in"))
    )
    for template in templates:
        target = template.relative_to(sources).with_suffix("")
        path = root / target
        if target.suffix.lower() == ".md":
            md_outputs.add(target)
        elif target.suffix.lower() == ".html":
            html_outputs.add(target)
        if template.is_symlink() or path.resolve() != path:
            problems.append(f"{target}: documentation paths must be regular files")
            continue
        try:
            body = TOKEN.sub(
                lambda match: facts[match[1]], template.read_text(encoding="utf-8")
            )
        except KeyError as error:
            problems.append(f"{target}: missing documentation fact {error}")
            continue
        generated = (
            body.rstrip() + f"\n\n<!-- Generated from {SOURCE / template.relative_to(sources)}"
            " by scripts/docs.py. -->\n"
        )
        if path.is_file() and path.read_bytes() == generated.encode():
            continue
        if check:
            problems.append(f"{target}: stale or missing, run python3 scripts/docs.py")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(generated.encode())

    for unmanaged in sorted(public_markdown(root) - md_outputs):
        problems.append(f"{unmanaged}: needs source {SOURCE / unmanaged}.in")
    if (sources / "site").is_dir():
        for unmanaged in sorted(public_localized_site_html(root) - html_outputs):
            problems.append(f"{unmanaged}: needs source {SOURCE / unmanaged}.in")
    return problems


def managed_outputs(root):
    sources = root / SOURCE
    templates = sorted(
        list(sources.rglob("*.md.in")) + list(sources.rglob("*.html.in"))
    )
    return [template.relative_to(sources).with_suffix("").as_posix() for template in templates]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="report drift without writing")
    mode.add_argument("--write", action="store_true", help="render outputs in place (default)")
    mode.add_argument("--list-outputs", action="store_true", help="print every managed output path")
    args = parser.parse_args()
    if args.list_outputs:
        for rel in managed_outputs(ROOT):
            print(rel)
        return 0
    problems = render(ROOT, args.check)
    for problem in problems:
        print(problem, file=sys.stderr)
    return int(bool(problems))


if __name__ == "__main__":
    sys.exit(main())
