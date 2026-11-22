---
name: publish
description: Deliver a completed workspace file to the current user's Hermes mobile discussion as a downloadable artifact.
---

When the user asks for a file from work in this discussion, create or select the completed regular file in the authorized working directory. Identify the active session from the current conversation context. Use its exact ID for publication.

Run this command in the workspace, substituting the relative output path and the intended session ID.

```sh
python3 "$HERMES_HOME/plugins/mobile_artifacts/publish.py" --session "${HERMES_SESSION_ID}" "build/report.pdf"
```

The command prints the generated artifact ID after it stores an immutable copy. On success, tell the user the file name and where to find it in **Fichiers publiés**. If the environment lacks the helper, profile home, session ID or workspace access, describe the missing prerequisite. Each publication is visible to the holder of that profile’s bearer credential.

<!-- Generated from docs/sources/server/artifacts/SKILL.md.in by scripts/docs.py. -->
