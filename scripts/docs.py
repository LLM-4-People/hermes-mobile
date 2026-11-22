#!/usr/bin/env python3
"""Render the repository's Markdown from its declared sources and shared facts."""

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path("docs/sources")
TOKEN = re.compile(r"{{\s*([a-z][a-z0-9_]*)\s*}}")


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


def render(root, check):
    sources = root / SOURCE
    facts = json.loads((sources / "facts.json").read_text(encoding="utf-8"))
    outputs = set()
    problems = []
    for template in sorted(sources.rglob("*.md.in")):
        target = template.relative_to(sources).with_suffix("")
        path = root / target
        outputs.add(target)
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
    for unmanaged in sorted(public_markdown(root) - outputs):
        problems.append(f"{unmanaged}: needs source {SOURCE / unmanaged}.in")
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="report drift without writing")
    args = parser.parse_args()
    problems = render(ROOT, args.check)
    for problem in problems:
        print(problem, file=sys.stderr)
    return int(bool(problems))


if __name__ == "__main__":
    sys.exit(main())
