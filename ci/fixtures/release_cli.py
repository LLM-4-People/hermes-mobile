#!/usr/bin/env python3
"""Local release-state double used by the delivery contract tests."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys

state_path = Path(os.environ["TEST_STATE"])
state = json.loads(state_path.read_text())
args = sys.argv[1:]
sha = os.environ["GITHUB_SHA"]
if args[0] == "api":
    path = args[-1]
    if "/branches/main" in path:
        print(json.dumps({"commit": {"sha": state["main"]}}))
    elif "/git/matching-refs/tags/" in path:
        if state["ref"]:
            obj = ({"type": "tag", "sha": "0" * 40} if state.get("annotated")
                   else {"type": "commit", "sha": state["ref"]})
            print(json.dumps([{"ref": "refs/tags/" + path.split("/")[-1], "object": obj}]))
        else:
            print("[]")
    elif "/commits/refs/tags/" in path:
        print(json.dumps({"sha": state["ref"]}))
    elif "/releases?" in path:
        print(json.dumps([state["releases"]]))
    elif "/releases/assets/" in path:
        sys.stdout.write(state["assetMetadata"][path.rsplit("/", 1)[1]])
    else:
        raise ValueError(args)
elif args[:2] == ["attestation", "verify"]:
    assert args[args.index("--source-digest") + 1] == sha
    assert args[args.index("--signer-workflow") + 1] == os.environ["GITHUB_REPOSITORY"] + "/.github/workflows/release.yml"
    sys.exit(0 if state.get("attestation") else 1)
else:
    assert args[:3] == ["release", "--repo", os.environ["GITHUB_REPOSITORY"]]
    command, tag = args[3:5]
    release = next((r for r in state["releases"] if r["tag_name"] == tag), None)
    if command == "create":
        assert not release and "--draft" in args and args[args.index("--target") + 1] == sha
        state["releases"].append({"tag_name": tag, "target_commitish": sha, "draft": True, "assets": []})
    elif command == "view":
        print(json.dumps({"isDraft": release["draft"], "assets": release["assets"]}))
    elif command == "upload":
        file = Path(args[5])
        assert release["draft"] and all(x["name"] != file.name for x in release["assets"])
        if file.name == os.getenv("FAIL_UPLOAD"):
            sys.exit(1)
        digest = hashlib.sha256(file.read_bytes()).hexdigest()
        release["assets"].append({"name": file.name, "digest": "sha256:" + digest})
        if file.name == "release.json" and os.getenv("EXTRA_ON_UPLOAD"):
            release["assets"].append({"name": "unreviewed.bin", "digest": "sha256:extra"})
    elif command == "download":
        shutil.copyfile(state["download"], Path(args[args.index("--dir") + 1]) / args[args.index("--pattern") + 1])
    elif command == "edit":
        assert release["draft"] and "--draft=false" in args
        release["draft"] = False
        state["ref"] = sha
    else:
        raise ValueError(args)
    state_path.write_text(json.dumps(state))
