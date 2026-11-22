#!/usr/bin/env python3
import hashlib
import json
import os
from pathlib import Path
import sys

state_path = Path(os.environ["TEST_STATE"])
state = json.loads(state_path.read_text())
args = sys.argv[1:]
tag = "v1.2.3"
sha = os.environ["GITHUB_SHA"]
if args[0] == "api":
    if any("/branches/main" in arg for arg in args):
        print(state["main"])
    elif any("/git/matching-refs/tags/" in arg for arg in args):
        print(json.dumps([{"ref": "refs/tags/" + tag}] if state["ref"] else []))
    elif any("/commits/tags/" in arg for arg in args):
        print(state["ref"])
    elif any("/releases?" in arg for arg in args):
        print(json.dumps([state["releases"]]))
    else:
        raise ValueError(args)
else:
    assert args[:3] == ["release", "--repo", os.environ["GITHUB_REPOSITORY"]]
    command = args[3]
    assert args[4] == tag
    if command == "create":
        assert not state["releases"] and "--draft" in args and args[args.index("--target") + 1] == sha
        state["releases"].append({"tag_name": tag, "target_commitish": sha, "draft": True, "assets": []})
    else:
        release = state["releases"][0]
        if command == "view":
            print(json.dumps({"isDraft": release["draft"], "assets": release["assets"]}))
        elif command == "upload":
            name = args[5]
            assert release["draft"] and all(x["name"] != name for x in release["assets"])
            if name == os.getenv("FAIL_UPLOAD"):
                sys.exit(1)
            digest = hashlib.sha256(Path(name).read_bytes()).hexdigest()
            release["assets"].append({"name": name, "digest": "sha256:" + digest})
        elif command == "edit":
            assert release["draft"] and "--draft=false" in args
            release["draft"] = False
            state["ref"] = sha
        else:
            raise ValueError(args)
    state_path.write_text(json.dumps(state))
