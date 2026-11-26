# Zero-root ambient intelligence

Hermes Mobile includes an optional, zero-root **Ambient context** panel under **Settings → Ambient context** that lets Hermes help with selected third-party notifications, media playback, and calendar context—while staying **fail-closed and completely OFF by default**.

## Opt-in and privacy boundaries

- **OFF by default per profile:** Enabling ambient access on one profile never enables it on another profile on the same device.
- **Guided Android 13–16 setup:** The panel walks you through Android’s two-step restricted-settings and Notification Listener enrollment without hidden prompts or accessibility hacks.
- **Searchable per-app allowlist:** Only apps you explicitly toggle ON can enter the in-memory sanitized digest.
- **Fail-closed `NotificationPrivacyGuard`:** Before any third-party notification reaches memory, `NotificationPrivacyGuard` evaluates it at the lowest choking point and hard-blocks:
  - Hermes itself (`org.llm4people.hermes`)
  - OTP / 2FA verification codes and alphanumeric authentication tokens
  - Password managers, authenticators, banking, finance, and crypto wallet apps (excluded from the allowlist UI as well)
  - `VISIBILITY_SECRET` lockscreen notifications
  - Ongoing, foreground-service, group-summary, and system/status noise

## What you can do from the digest

Each sanitized entry in your profile’s live digest offers explicit, user-confirmed actions:
- **Summarize:** Stage a prompt in your active discussion composer (never auto-sent).
- **Create note:** Turn an important message into a structured note in **Notes & tasks**.
- **Add to calendar:** Open Android’s calendar editor pre-filled with the event details for review.
- **Reply:** Draft a reviewed quick-reply with Hermes, inspect the exact outgoing text in a confirmation dialog, and explicitly approve before Android invokes the notification’s `RemoteInput` action.
- **Dismiss:** Remove the entry from the local digest buffer.

## Now playing, calendar, and approved device bridge

- **Now Playing card:** When notification access is granted and media control is enabled, inspect the active media session and trigger user-confirmed play/pause/next/previous actions.
- **Calendar & approved device bridge:** Optional read-only calendar context and approved bridge capabilities (`calendar_read`, `media_control`, `camera`, `location_coarse`, `nearby_osm`, `clipboard_read`, and the 11 reviewed OS actions) each request explicit runtime permission and per-action user review before executing or returning results to Hermes.

<!-- Generated from docs/sources/docs/ambient.md.in by scripts/docs.py. -->
