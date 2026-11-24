# Live UI modding, surfaces v2, and on-the-fly widgets

Hermes Mobile lets Hermes adapt your mobile workspace in real time using declarative native Android blocks—with strict validation, WCAG-AA contrast enforcement, profile isolation, and full revision rollback.

> **Requires companion fork:** Live UI patches and gateway revision history require [LLM-4-People/hermes-agent](https://github.com/LLM-4-People/hermes-agent).

## Live UI patches (`ui.patch`)

Ask Hermes in any conversation to tailor a screen, adjust colors, or build a reusable tool or tracker (for example: *"Add a reading tracker card to my workspace home"* or *"Give my workspace a warm dark slate accent"*).

- **Bounded atomic operations:** Each `LiveUiPatch` applies up to **16 operations** (`MAX_OPS = 16`) atomically to the unlocked profile’s workspace state. If any operation fails validation, the entire patch is rejected without mutating saved state.
- **Surfaces v2 & interactive controls:** Surfaces support rich native sections, live values, contextual quick-action chips, and interactive controls (`InteractiveControlsBlock`—toggles, sliders with TalkBack support, multi-select chips, and form inputs) whose state persists locally per profile and stages reviewed drafts before sending.
- **Preview, apply & multi-revision rollback:** Every patch shows a clear summary bar before or after application. Undoing a revision (`rollbackAllTo`) rolls back dependent newer revisions cleanly and synchronizes sequential reverts back to the gateway (`POST /api/mobile/ui/revisions/{id}/revert`). You can also inspect history or reset any surface from **Apps** (or the native screen-layout menu).

## WCAG-AA theme overrides (`ThemePalette`)

Hermes can propose theme accent, surface, or ink overrides while keeping both day and night appearances legible:

- **Minimum `4.5:1` contrast:** `ThemePalette.OverrideSpec` validates foreground/background pairs against WCAG-AA (`>= 4.5:1`) before persisting.
- **Day ↔ night stability:** Single-color `surface_hex` or `ink_hex` overrides in `system` mode resolve mode-invariantly using automatic high-contrast ink selection, so switching between light and dark themes never corrupts saved workspace state or produces unreadable text.

## On-the-fly pinned home screen widgets

Hermes can generate custom home screen widgets alongside a workspace patch or from **Apps**:

- **Zero-leak & profile-scoped:** Every dynamic widget spec is stored under an opaque SHA-256 profile-scoped identifier (`HermesWidgetStore.profileScopedId`) bound to its owning `profileId`.
- **Cross-profile protection:** Tapping a widget owned by another profile locks the current session and prompts you to unlock the owning profile before staging any draft or opening its surface.
- **Clean removal:** Forgetting a profile automatically purges all widget specs owned by that profile and refreshes the launcher widget surface.

## Android 16 OS integration

- **Live Updates:** Active Hermes runs surface progress directly in an Android 16 Live Update notification (`LiveRunNotification`) with a quick stop action—without exposing private prompt contents on the lockscreen.
- **Quick Settings Tile & App Shortcuts:** Jump directly into a new discussion, voice input, or camera capture via `HermesTileService` and `HermesShortcuts`, always gated behind `ProfileGate`.
- **Adaptive Phone & Tablet Shell:** Three living destinations (**Discussions**, **Notes & Tasks**, **Apps**) render as an IME-aware bottom navigation bar on phones (auto-hiding when the keyboard opens without dropping text focus) and as a **92 dp** navigation rail on tablets (`>= 720 dp` wide) paired with a collapsible `232–360 dp` conversation sidebar, with **Universal Search** and **Settings Hub** always available from the top bar.

<!-- Generated from docs/sources/docs/live-ui-and-widgets.md.in by scripts/docs.py. -->
