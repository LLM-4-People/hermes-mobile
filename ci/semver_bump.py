"""Single Source of Truth SemVer + Android versionCode bumper and code-driven release planner.

Zero third-party dependencies. Evaluates PR labels, changed file paths, and PR titles fail-closed,
reads/updates gradle.properties (hermesVersionName + hermesVersionCode), and decides whether to
tag & dispatch Signed Android release immediately, open a ruleset-compliant bump PR, or skip.
"""

import argparse
import json
from pathlib import Path
import re
import sys

try:
    from release_metadata import SEMVER_PATTERN
except ImportError:
    from ci.release_metadata import SEMVER_PATTERN


SEMVER_RE = re.compile(rf"^v?({SEMVER_PATTERN.pattern})$")
BUMP_RANK = {"patch": 1, "minor": 2, "major": 3}
BREAKING_TITLE_RE = re.compile(r"(?:^[a-z][a-z0-9_-]*(?:\([^)]*\))?!:|\bBREAKING[\s_-]+CHANGE\b)", re.IGNORECASE)
FEAT_TITLE_RE = re.compile(r"^feat(?:\([^)]*\))?:", re.IGNORECASE)
RELEASE_BUMP_TITLE_RE = re.compile(r"^chore\(release\):", re.IGNORECASE)

RELEASE_BUMP_LABELS = {
    "release:patch": "patch",
    "release:minor": "minor",
    "release:major": "major",
}
PUBLISH_LABEL = "release:publish"
SKIP_LABEL = "skip-release"
VALID_FORCE_BUMPS = ("patch", "minor", "major", "publish")

SHIPPABLE_PREFIXES = (
    "app/src/main/",
    "gradle/wrapper/",
)
SHIPPABLE_EXACT_FILES = {
    "app/proguard-rules.pro",
    "gradle/libs.versions.toml",
}
SHIPPABLE_GRADLE_PREFIXES = (
    "app/build.gradle",
    "build.gradle",
    "settings.gradle",
)


def parse_semver(raw):
    """Parse a strict SemVer string (with optional leading 'v') into (major, minor, patch)."""
    match = SEMVER_RE.fullmatch(str(raw).strip())
    if not match:
        raise ValueError(f"Invalid semantic version: {raw!r}")
    core = match.group(1).split("-", 1)[0]
    major, minor, patch = core.split(".")
    return int(major), int(minor), int(patch)


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


def _normalize_path(raw):
    text = str(raw or "").strip().replace("\\", "/")
    while text.startswith("./"):
        text = text[2:]
    return text.lstrip("/")


def is_shippable_app_path(path, *, allow_gradle_properties=True):
    """Return True only when a changed path affects the shippable Android APK."""
    norm = _normalize_path(path)
    if not norm:
        return False
    if norm == "gradle.properties":
        return allow_gradle_properties
    if norm in SHIPPABLE_EXACT_FILES:
        return True
    if any(norm.startswith(prefix) for prefix in SHIPPABLE_PREFIXES):
        return True
    for prefix in SHIPPABLE_GRADLE_PREFIXES:
        if norm == prefix or norm.startswith(prefix + "."):
            return True
    return False


def has_shippable_app_changes(changed_files, pr_title=""):
    """Return True when at least one changed file affects the shippable Android APK."""
    if not changed_files:
        return False
    is_bump_title = bool(RELEASE_BUMP_TITLE_RE.match((pr_title or "").strip()))
    return any(
        is_shippable_app_path(path, allow_gradle_properties=not is_bump_title)
        for path in changed_files
    )


def infer_bump_from_pr(pr_title="", labels=()):
    """Infer SemVer bump ('major'|'minor'|'patch') from Conventional Commit title and PR labels."""
    title = (pr_title or "").strip()
    cleaned_labels = {str(lbl).strip().lower() for lbl in (labels or ()) if lbl and str(lbl).strip()}
    if BREAKING_TITLE_RE.search(title):
        return "major"
    if FEAT_TITLE_RE.match(title) or "enhancement" in cleaned_labels:
        return "minor"
    return "patch"


def select_release_action(labels, changed_files=None, pr_title=""):
    """Determine release action from PR labels, changed files, and PR title (fail-closed).

    Precedence:
    1. Reject malformed or conflicting release:* labels fail-closed.
    2. 'skip-release' label -> None (skip release).
    3. Explicit 'release:publish|major|minor|patch' label -> honored directly.
    4. Automatic code-change classification when changed_files contains shippable Android app files.

    Returns one of 'patch', 'minor', 'major', 'publish', or None.
    """
    action, _reason = classify_release(labels, changed_files=changed_files, pr_title=pr_title)
    return action


def classify_release(labels, changed_files=None, pr_title="", force_bump=None):
    """Return (action, reason) tuple for release planning and step summaries."""
    if force_bump is not None and str(force_bump).strip():
        chosen = str(force_bump).strip().lower()
        if chosen not in VALID_FORCE_BUMPS:
            raise ValueError(f"Unsupported forced bump kind: {force_bump!r}")
        return chosen, "manual_dispatch"

    cleaned = {str(label).strip() for label in (labels or ()) if label and str(label).strip()}
    lowered = {lbl.lower() for lbl in cleaned}

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

    if SKIP_LABEL in lowered:
        return None, "skip_label"
    if has_publish:
        return "publish", "explicit_label"
    if bumps:
        return bumps[0], "explicit_label"

    if changed_files is None:
        return None, "no_release_label"
    if not has_shippable_app_changes(changed_files, pr_title=pr_title):
        return None, "non_shippable_paths"
    return infer_bump_from_pr(pr_title=pr_title, labels=cleaned), "auto_code_change"


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


def highest_bump(primary_bump, unreleased_titles=()):
    """Return the highest SemVer bump across primary_bump and all unreleased commit subjects."""
    best = primary_bump
    best_rank = BUMP_RANK[primary_bump]
    for raw_title in unreleased_titles or ():
        title = str(raw_title or "").strip()
        if not title or RELEASE_BUMP_TITLE_RE.match(title):
            continue
        candidate = infer_bump_from_pr(pr_title=title)
        rank = BUMP_RANK[candidate]
        if rank > best_rank:
            best = candidate
            best_rank = rank
    return best


def plan_release(
    current_name,
    current_code,
    action,
    existing_tags=(),
    published_max_code=0,
    unreleased_titles=(),
    pending_release_tags=(),
):
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

    # For patch/minor/major: if gradle.properties on main is ALREADY strictly newer than
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
    effective_bump = highest_bump(action, unreleased_titles)
    next_name = bump_semver(format_semver(base_tuple), effective_bump)
    next_tuple = parse_semver(next_name)

    # Never downgrade below an already-open release/v* PR targeting a higher unreleased version.
    for raw_pending in pending_release_tags or ():
        try:
            pending_tuple = parse_semver(raw_pending)
        except ValueError:
            continue
        pending_tag = f"v{format_semver(pending_tuple)}"
        if pending_tuple > next_tuple and pending_tag not in tag_set:
            next_tuple = pending_tuple
            next_name = format_semver(pending_tuple)

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


def find_superseded_release_prs(open_prs, target_tag):
    """Return sorted PR numbers of open release/v* PRs whose SemVer is strictly lower than target_tag."""
    target_tuple = parse_semver(target_tag)
    superseded = []
    for pr in open_prs or ():
        if not isinstance(pr, dict):
            continue
        head = str(pr.get("headRefName") or "").strip()
        if not head.startswith("release/"):
            continue
        candidate_tag = head[len("release/"):]
        try:
            candidate_tuple = parse_semver(candidate_tag)
        except ValueError:
            continue
        if candidate_tuple < target_tuple:
            number = pr.get("number")
            if isinstance(number, int) and not isinstance(number, bool):
                superseded.append(number)
    return sorted(set(superseded))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle-properties", type=Path, default=Path("gradle.properties"))
    parser.add_argument("--labels-json", default="[]", help="JSON array of PR label names")
    parser.add_argument("--changed-files-json", default=None, help="Optional JSON array of changed repo paths")
    parser.add_argument("--pr-title", default="", help="Optional merged PR title for automatic SemVer classification")
    parser.add_argument("--force-bump", default=None, help="Optional manual override: patch|minor|major|publish")
    parser.add_argument("--tags-json", default="[]", help="JSON array of existing git tag names")
    parser.add_argument("--unreleased-titles-json", default="[]", help="JSON array of unreleased commit titles since last tag")
    parser.add_argument("--pending-tags-json", default="[]", help="JSON array of open release/v* PR target tags")
    parser.add_argument("--superseded-prs-json", default=None, help="JSON array of open PRs ({number, headRefName}) to filter against --target-tag")
    parser.add_argument("--target-tag", default=None, help="Target SemVer tag used with --superseded-prs-json")
    parser.add_argument("--published-max-code", type=int, default=0)
    parser.add_argument("--apply-bump", action="store_true", help="Write bumped version to gradle.properties when mode=open_bump_pr")
    parser.add_argument("--github-output", type=Path, default=None)
    args = parser.parse_args(argv)

    if args.superseded_prs_json is not None:
        if not args.target_tag:
            raise ValueError("--target-tag is required with --superseded-prs-json")
        for pr_number in find_superseded_release_prs(json.loads(args.superseded_prs_json), args.target_tag):
            print(pr_number)
        return 0

    labels = json.loads(args.labels_json)
    changed_files = json.loads(args.changed_files_json) if args.changed_files_json is not None else None
    tags = json.loads(args.tags_json)
    unreleased_titles = json.loads(args.unreleased_titles_json)
    pending_tags = json.loads(args.pending_tags_json)
    action, reason = classify_release(
        labels,
        changed_files=changed_files,
        pr_title=args.pr_title,
        force_bump=args.force_bump,
    )
    current_name, current_code = read_gradle_version(args.gradle_properties)
    plan = plan_release(
        current_name,
        current_code,
        action,
        existing_tags=tags,
        published_max_code=args.published_max_code,
        unreleased_titles=unreleased_titles,
        pending_release_tags=pending_tags,
    )
    plan["reason"] = reason
    if action is not None:
        plan["bump"] = action

    if args.apply_bump and plan.get("mode") == "open_bump_pr":
        write_gradle_version(args.gradle_properties, plan["version_name"], plan["version_code"])

    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as handle:
            for key in ("mode", "tag", "version_name", "version_code", "branch", "reason", "bump"):
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
