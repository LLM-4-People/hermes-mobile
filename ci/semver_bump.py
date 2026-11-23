"""Single Source of Truth SemVer + Android versionCode bumper and release planner.

Zero third-party dependencies. Validates PR release labels fail-closed, reads/updates
gradle.properties (hermesVersionName + hermesVersionCode), and decides whether to
tag & dispatch Signed Android release immediately or open a ruleset-compliant bump PR.
"""

import argparse
import json
from pathlib import Path
import re
import sys


SEMVER_RE = re.compile(r"^v?([0-9]+)\.([0-9]+)\.([0-9]+)(?:-[0-9A-Za-z.-]+)?$")
RELEASE_BUMP_LABELS = {
    "release:patch": "patch",
    "release:minor": "minor",
    "release:major": "major",
}
PUBLISH_LABEL = "release:publish"


def parse_semver(raw):
    """Parse a SemVer string (with optional leading 'v') into (major, minor, patch)."""
    match = SEMVER_RE.fullmatch(str(raw).strip())
    if not match:
        raise ValueError(f"Invalid semantic version: {raw!r}")
    return int(match.group(1)), int(match.group(2)), int(match.group(3))


def format_semver(parts):
    major, minor, patch = parts
    if min(major, minor, patch) < 0:
        raise ValueError("Semantic version components must be non-negative")
    return f"{major}.{minor}.{patch}"


def bump_semver(current, bump):
    """Compute the next X.Y.Z version for bump in {'patch', 'minor', 'major'}."""
    major, minor, patch = parse_semver(current)
    if bump == "patch":
        return format_semver((major, minor, patch + 1))
    if bump == "minor":
        return format_semver((major, minor + 1, 0))
    if bump == "major":
        return format_semver((major + 1, 0, 0))
    raise ValueError(f"Unsupported bump kind: {bump!r}")


def select_release_action(labels):
    """Determine release action from PR labels; fail closed on conflicting release:* labels.

    Returns one of 'patch', 'minor', 'major', 'publish', or None.
    """
    cleaned = {str(label).strip() for label in labels if label and str(label).strip()}
    bumps = sorted(RELEASE_BUMP_LABELS[lbl] for lbl in cleaned if lbl in RELEASE_BUMP_LABELS)
    has_publish = PUBLISH_LABEL in cleaned
    unknown_release = sorted(
        lbl for lbl in cleaned
        if lbl.startswith("release:") and lbl not in RELEASE_BUMP_LABELS and lbl != PUBLISH_LABEL
    )
    if unknown_release:
        raise ValueError(f"Unsupported release label(s): {', '.join(unknown_release)}")
    if len(bumps) > 1:
        raise ValueError(f"Conflicting release labels applied: {', '.join('release:' + b for b in bumps)}")
    if has_publish and bumps:
        raise ValueError(f"Cannot combine {PUBLISH_LABEL} with release:{bumps[0]}")
    if has_publish:
        return "publish"
    return bumps[0] if bumps else None


def read_gradle_version(path):
    """Read (version_name, version_code) from gradle.properties."""
    text = Path(path).read_text(encoding="utf-8")
    name_match = re.search(r"^hermesVersionName=(.+)$", text, re.MULTILINE)
    code_match = re.search(r"^hermesVersionCode=([0-9]+)$", text, re.MULTILINE)
    if not name_match or not code_match:
        raise ValueError("gradle.properties missing hermesVersionName or hermesVersionCode")
    version_name = name_match.group(1).strip()
    parse_semver(version_name)
    version_code = int(code_match.group(1))
    if not 1 <= version_code <= 2_100_000_000:
        raise ValueError(f"Invalid hermesVersionCode: {version_code}")
    return version_name, version_code


def write_gradle_version(path, version_name, version_code):
    """Update hermesVersionName and hermesVersionCode in gradle.properties cleanly."""
    parse_semver(version_name)
    if type(version_code) is not int or not 1 <= version_code <= 2_100_000_000:
        raise ValueError(f"Invalid hermesVersionCode: {version_code}")
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    updated, n_name = re.subn(r"^hermesVersionName=.*$", f"hermesVersionName={version_name}", text, count=1, flags=re.MULTILINE)
    updated, n_code = re.subn(r"^hermesVersionCode=.*$", f"hermesVersionCode={version_code}", updated, count=1, flags=re.MULTILINE)
    if n_name != 1 or n_code != 1:
        raise ValueError("Failed to update hermesVersionName/hermesVersionCode in gradle.properties")
    target.write_text(updated, encoding="utf-8")


def plan_release(current_name, current_code, action, existing_tags=(), published_max_code=0):
    """Compute whether to tag+release immediately or open a ruleset-compliant version-bump PR."""
    if action is None:
        return {"mode": "none"}

    current_tuple = parse_semver(current_name)
    tag_set = {t.strip() for t in existing_tags if t and t.strip()}
    parsed_tags = []
    for tag in tag_set:
        try:
            parsed_tags.append(parse_semver(tag))
        except ValueError:
            continue
    highest_tag = max(parsed_tags) if parsed_tags else None
    current_tag = f"v{current_name}"

    if action == "publish":
        if current_tag in tag_set:
            raise ValueError(f"Release tag {current_tag} already exists")
        if current_code <= published_max_code:
            raise ValueError(
                f"hermesVersionCode ({current_code}) must exceed published max ({published_max_code})"
            )
        if highest_tag is not None and current_tuple <= highest_tag:
            raise ValueError(
                f"hermesVersionName ({current_name}) must exceed latest tag v{format_semver(highest_tag)}"
            )
        return {
            "mode": "tag_and_release",
            "tag": current_tag,
            "version_name": current_name,
            "version_code": current_code,
        }

    # For release:patch/minor/major: if gradle.properties on main is ALREADY strictly newer than
    # all existing tags and published versionCodes (e.g., first release 0.1.0 or manually bumped PR),
    # tag and dispatch Signed Android release immediately. Otherwise open a ruleset-compliant bump PR.
    if (
        current_tag not in tag_set
        and current_code > published_max_code
        and (highest_tag is None or current_tuple > highest_tag)
    ):
        return {
            "mode": "tag_and_release",
            "tag": current_tag,
            "version_name": current_name,
            "version_code": current_code,
        }

    base_tuple = max(current_tuple, highest_tag) if highest_tag is not None else current_tuple
    next_name = bump_semver(format_semver(base_tuple), action)
    next_code = max(current_code, published_max_code) + 1
    next_tag = f"v{next_name}"
    if next_tag in tag_set:
        raise ValueError(f"Computed release tag {next_tag} already exists")
    return {
        "mode": "open_bump_pr",
        "tag": next_tag,
        "version_name": next_name,
        "version_code": next_code,
        "branch": f"release/{next_tag}",
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle-properties", type=Path, default=Path("gradle.properties"))
    parser.add_argument("--labels-json", default="[]", help="JSON array of PR label names")
    parser.add_argument("--tags-json", default="[]", help="JSON array of existing git tag names")
    parser.add_argument("--published-max-code", type=int, default=0)
    parser.add_argument("--apply-bump", action="store_true", help="Write bumped version to gradle.properties when mode=open_bump_pr")
    parser.add_argument("--github-output", type=Path, default=None)
    args = parser.parse_args(argv)

    labels = json.loads(args.labels_json)
    tags = json.loads(args.tags_json)
    action = select_release_action(labels)
    current_name, current_code = read_gradle_version(args.gradle_properties)
    plan = plan_release(
        current_name,
        current_code,
        action,
        existing_tags=tags,
        published_max_code=args.published_max_code,
    )
    if args.apply_bump and plan.get("mode") == "open_bump_pr":
        write_gradle_version(args.gradle_properties, plan["version_name"], plan["version_code"])

    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as handle:
            for key in ("mode", "tag", "version_name", "version_code", "branch"):
                if key in plan:
                    handle.write(f"{key}={plan[key]}\n")

    print(json.dumps(plan, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as err:
        print(f"Release planner error: {err}", file=sys.stderr)
        raise SystemExit(1) from err
