---
name: hermes-mobile-workspace
description: Propose profile-scoped Hermes Mobile screen layouts and forms, create linked screens, and publish versioned source or diff artifacts into a chat thread.
---

# Shape a Hermes Mobile screen

When someone asks to rearrange their Android workspace, return a complete layout as an assistant message with a single fenced `hermes-workspace` block for each screen. The app offers a native preview and asks the person to apply the proposal. Describe the affected screen and the change in ordinary language. Keep suggested screen labels and notes in French. Do not claim that a draft has been applied.

```hermes-workspace
{
  "version": 1,
  "surface": "thread",
  "title": "Table de travail",
  "theme": "system",
  "density": "compact",
  "blocks": [
    {
      "title": "Code",
      "actions": ["artifacts", "tools", "models"]
    },
    {"type": "content"},
    {
      "title": "Après la revue",
      "note": "Retrouvez les rappels liés à votre travail.",
      "actions": ["reminders", "stats"]
    }
  ]
}
```

Choose a surface from `home`, `threads`, `thread`, `settings`, `reminders`, `artifacts`, or `stats`. The person can restore each one independently. When extending a screen, the `content` block places its native controls between the surrounding sections. Change block order and include the action IDs the person wants to see.

Use an integer schema version of 1, a title of 1–64 characters, and 1–12 blocks. On a built-in screen in the default `extend` mode, include exactly one `{"type":"content"}` block. Each section block needs a title and at least one note or action. A note accepts up to 240 characters. A section accepts up to eight distinct action IDs from `threads`, `new_thread`, `artifacts`, `models`, `tools`, `reminders`, `stats`, `profiles`, `notifications`, `workspace`, and `pages`. The app supplies localized action labels. Use the optional `theme` field to choose `system`, `light`, or `dark` for the layout controls, and `density` to choose `comfortable` or `compact` spacing. Use plain text and standard JSON. Extra properties, external links as actions, and program code have no behavior.

# Create a coding-review tool

Set `mode` to `replace` to author all the content inside an authenticated screen. Omit the `content` block in this mode. For a new screen use `surface: "page/<slug>"`. A slug begins with a lowercase ASCII letter and contains up to 36 lowercase letters, digits or hyphens. The native **Mes écrans** library lists applied pages. One profile can hold 24 named pages, including removed pages awaiting undo.

This page asks for a file, review goal, and optional context. A person can inspect and edit the generated draft, transfer it into a conversation and choose when to send. Submitted form values stay in memory until the person transfers the text into the profile’s native draft. They do not enter the agent’s context on their own.

```hermes-workspace
{
  "version": 1, "mode": "replace", "surface": "page/revue-code", "title": "Revue de code",
  "blocks": [
    {"type":"value","title":"Conversation active","source":"thread.title"},
    {
      "type":"form","title":"Préparer la revue",
      "prompt":"Examine les changements décrits et propose des vérifications concrètes.",
      "fields":[
        {"id":"fichier","label":"Fichier ou composant","kind":"text"},
        {"id":"objectif","label":"Objectif","kind":"choice","options":["Fiabilité","Lisibilité","Performance"]},
        {"id":"contexte","label":"Contexte","kind":"multiline","required":false}
      ]
    }
  ]
}
```

The next proposal replaces the Atelier body and links directly to the new tool. Send both proposals so the person can preview and apply each destination.

```hermes-workspace
{
  "version":1, "mode":"replace", "surface":"home", "title":"Atelier de revue",
  "blocks":[
    {"type":"link","title":"Ouvrir la revue de code","page":"page/revue-code"},
    {"title":"Conversations","actions":["threads","new_thread","pages"]}
  ]
}
```

A `value` block can read `profile.label`, `thread.title`, or `thread.messageCount`. These values appear locally on the screen. Form fields use `text`, `multiline`, or `choice`; fields default to required. Each form accepts up to six fields with unique IDs following the slug syntax. Choice fields accept one to twelve distinct options. The app limits free-text answers to 1,500 characters. Use a short imperative `prompt` for the draft, followed by a label for each answer. Form submission always offers a native, editable review before transferring the draft.

A thread’s `content` block can include a `slots` array. Include a nonempty subset of `notice`, `transcript`, and `composer` in display order. For example, `{"type":"content","slots":["composer","notice","transcript"]}` places the native composer first. Omit `slots` to retain the standard thread layout. The app’s protected profile gate, screen recovery, permission prompts and update signature remain native. The **Dispositions des écrans** menu restores screen layouts, removes named pages and allows their last revision to be recovered.

# Share a versioned artifact

Send code as a fenced block. Put the same relative filename after the language in subsequent revisions to collect them under one entry in the thread. Use an explicit `file=` name for extensionless files such as `Dockerfile`, `Makefile`, and `src/LICENSE`. For a patch use a `diff` fence. Preserve the complete file or diff body in each revision. The phone provides a selectable code reader and copy action.

```python file=tools/ready.py
def is_ready(state):
    return state == "ready"
```

```diff file=tools/ready.patch
-    return state == "pending"
+    return state == "ready"
```

Use a reviewed source change and a signed APK release for a new native capability. A proposal can invoke installed actions and rearrange a screen immediately after confirmation.

<!-- Generated from docs/sources/skills/hermes-mobile-workspace/SKILL.md.in by scripts/docs.py. -->
