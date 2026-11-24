# Security policy

## Reporting a vulnerability

Please **do not** open a public GitHub issue for security vulnerabilities. Report suspected vulnerabilities privately through [GitHub Security Advisories](https://github.com/LLM-4-People/hermes-mobile/security/advisories/new).

Include:
- Affected component (Android app, `server/mobile_push`, `server/artifacts`, `server/nearby`, `deploy/accounts.py`, or companion fork contract)
- Exact reproduction steps or proof-of-concept
- Expected vs. observed security boundary behavior

## Security architecture and boundaries

Hermes Mobile is designed **fail-closed by default** across four boundaries:

1. **Device-Local Profile Vault, Multi-Mode Lock & Permissions Center**
   - Each profile’s endpoint, bearer credential, drafts, custom surfaces, and settings are stored in encrypted per-profile storage unlocked by your chosen lock mode: Class-3 hardware-backed **Biometrics / device lock** (`BIOMETRIC_STRONG` / `DEVICE_CREDENTIAL`), a **6–12 digit PIN**, or a **12+ character passphrase**, with a smart **5-minute** default auto-lock grace window (configurable from immediate lock to longer grace intervals) and persisted unlock backoff.
   - **Settings → Permissions** surfaces every optional Android runtime or special permission (notifications, microphone, camera, approximate location, calendar, notification access, package installs) with transparent status and 1-tap **Allow** / **Open settings** actions—nothing is requested silently.
   - All activities set `FLAG_SECURE` to prevent screenshots and recents previews from leaking private transcripts.
   - Locking a profile or destroying `MainActivity` zeros active keys, dismisses open dialogs, and cancels in-flight biometric sessions. Forgetting a profile purges its encrypted files, push enrollments, and owned Home Screen Widgets.
2. **Single-Host Multi-Profile Server Isolation (`/p/<profile>`)**
   - Behind one shared HTTPS hostname (`deploy/accounts.py`), Caddy strips `/p/<profile>` and routes each user to a dedicated gateway container with an isolated `HERMES_HOME`, workspace, provider credentials, and 256-bit `API_SERVER_KEY`. Unknown or nested `/p/` paths return HTTP 404.
   - Same-server sharing (`/api/shares/*`) is explicit, owner-controlled, and revocable; shared items never expose another user’s private workspace, bearer key, or unshared threads.
3. **Zero-Leak Closed-App Push Delivery**
   - The push bridge (`server/mobile_push`) mounts its profile’s `HERMES_HOME` read-only and transmits only opaque event kinds, identifiers, urgency, and hashed profile hints. Message bodies, delivery titles, prompts, and answers never transit FCM or ntfy/UnifiedPush—the Android client fetches them directly from the authenticated gateway after the owning profile unlocks.
4. **Zero-Root Ambient Intelligence & Media Hygiene**
   - Ambient notification, media, and calendar integrations start **OFF** for every profile.
   - `NotificationPrivacyGuard` runs at the lowest choking point before any notification text enters memory, hard-blocking Hermes itself, OTP/2FA codes, alphanumeric tokens, password managers, authenticators, banking/finance/crypto apps, `VISIBILITY_SECRET` notices, and ongoing system notifications.
   - Still images attached from gallery or camera are decoded and re-encoded into fresh sRGB opaque pixels, discarding all EXIF, GPS, and ICC metadata before upload.

## Verifying official releases

Signed release APKs are built from clean outputs on `main` with reproducible unsigned comparison, AOSP device verification, and GitHub build provenance attestations. Always verify the checksum, APK signer certificate fingerprint, and attestation before installing or upgrading. [Verify a release](docs/delivery.md#verify-a-release)

<!-- Generated from docs/sources/SECURITY.md.in by scripts/docs.py. -->
