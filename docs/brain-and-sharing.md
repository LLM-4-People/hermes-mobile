# Notes, search, power tools, and same-server sharing

**Notes & tasks**, top-bar **Universal search**, inline **Shared** filters, and the **Settings** power-user sections give you direct, everyday-language control over what Hermes remembers, knows, searches, and shares on your server—without editing raw configuration files or leaving Android.

> **Requires companion fork:** Memory, notes, habits, sharing, search, skills, workspace files, and profile defaults endpoints (`/api/memory`, `/api/notes`, `/api/habits`, `/api/shares`, `/api/search`, `/api/skills`, `/api/workspace`, `/api/profile/defaults`) require [LLM-4-People/hermes-agent](https://github.com/LLM-4-People/hermes-agent).

## Everyday notes, universal search, and settings tools

1. **Hermes memory (`MEMORY.md` & `USER.md`, in Settings → Assistant & AI)**
   - Inspect what Hermes remembers about your projects and preferences, add or edit entries directly, and save safely with server-side revision checks.
2. **Smart notes (in Notes & tasks)**
   - Capture structured Markdown notes with tags and full-text filtering, switch between **My notes** and **Shared** inline, pin your own notes (`owner`-gated), or stage a note directly into the active discussion draft.
3. **Pattern-gated habits (`>= 3` occurrences)**
   - Hermes only suggests a reusable habit or recurring automation after observing a real pattern **at least 3 times**. Review each suggestion in plain language and accept, customize, or dismiss it.
4. **Universal search (top-bar Search on every screen)**
   - Search instantly across local screens, settings, tools, and server-side SQLite FTS5 conversation history, smart notes, and shared items in one `FLAG_SECURE` overlay with category filter chips and incremental pagination.
5. **Skills (in Settings → Assistant & AI)**
   - Browse enabled skills on your gateway, inspect summaries and instructions, create or update reusable skills (validated against the fork’s 60-character description budget and lowercase single-segment category rules), or stage a skill into your current draft.
6. **Workspace files & Git diffs (in Settings → Workspace & system)**
   - Browse the active thread’s workspace directory on the gateway, read source files in the syntax-highlighted `CodeReader`, inspect live Git diffs, or reference files in your next message.
7. **Profile defaults & language sync (in Settings)**
   - Set your default provider, model, and capabilities for the unlocked profile. Changing the app language (**Auto / French / English**) asynchronously syncs `preferred_language` (`PATCH /api/profile/defaults`) and sends `Accept-Language` and `user_language` so Hermes replies in your language.

## Thread power actions

From any conversation menu, you can:
- **Pin / unpin** important threads to the top of your discussion list.
- **Branch / fork** a conversation from any message turn (including turns from compacted lineage resolved automatically by the fork) while choosing whether to keep the original thread open.
- **Export** a complete transcript as readable **Markdown** or structured **JSON** via Android’s native storage picker.

## Universal same-server sharing (7 resource kinds)

Because all users on a deployment share **one single HTTPS hostname** (`/p/<profile>`) while keeping isolated homes and keys, you can selectively share items with other profiles on the same server without exposing your workspace:

| Kind | What can be shared |
| --- | --- |
| `note` | Smart Notes (read-only or collaborative edit; pinning remains owner-only) |
| `skill` | Reusable Hermes skills and workflows |
| `surface` | Custom Live UI screens and layouts |
| `widget` | Dynamic Home Screen widget definitions |
| `tool` | Model-authored discussion tools and forms |
| `artifact` | Named code, diff, or published file artifacts |
| `session` | Conversation snapshots and discussions |

Open **Share on this server** from a supported item to grant or revoke access via `/api/shares/outgoing`. Incoming shared items (`/api/shares/incoming`) appear directly inline via the **Shared** filter in **Discussions**, the **Shared** filter in **Notes & tasks**, **Notes & tasks → Inbox**, and top-bar **Universal search**, where you can preview them safely or stage them into your own workspace.

<!-- Generated from docs/sources/docs/brain-and-sharing.md.in by scripts/docs.py. -->
