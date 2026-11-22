# Closed-app notifications and reminders

Hermes Mobile receives Android notifications for completed or failed runs, pending approvals, and reminders. Each registered device gets a generic alert. Opening it requires the appropriate profile before the app can read the associated thread or job. The bridge sends only the event kind and opaque event and installation identifiers through the selected transport. A profile can offer Firebase Cloud Messaging, a self-hosted ntfy distributor, or both. [Set up UnifiedPush](unifiedpush.md)

## Connect a profile

Deploy one stand-alone Hermes API process and one bridge per user, with separate credentials, runtime home, and bridge state. Point the APK at the HTTPS origin serving that runtime. On this same origin route the exact path `/api/mobile/push` and its children to the bridge, and the remaining Hermes API paths to the gateway. A gateway prefix in the phone’s URL requires the reverse proxy to remove that prefix when forwarding to this bridge. The bridge supports one profile per process, and uses the non-multiplexed gateway's API key namespace.

For Firebase delivery, register the APK’s Android application ID in your Firebase project, enable the Cloud Messaging API and give the bridge service account permission to send messages. Supply the four values from the registered Android application's Firebase configuration. Application Default Credentials on the server authorize FCM HTTP v1 through Google's OAuth client. The bridge checks for usable credential configuration at startup. The Android SDK configuration can be returned to the authenticated phone. [Firebase authorization](https://firebase.google.com/docs/cloud-messaging/auth-server)

| Server environment | Value |
| --- | --- |
| `HERMES_HOME` | This user's runtime directory, readable by the bridge. Mount read-only. |
| `MOBILE_PUSH_STATE` | Persistent writable directory, mode 0700. The image uses `/var/lib/hermes-mobile-push`. |
| `API_SERVER_KEY` | Same strong bearer key as this user's Hermes API. |
| `MOBILE_FIREBASE_PROJECT_ID` | Firebase project ID. |
| `MOBILE_FIREBASE_SENDER_ID` | Firebase project number. |
| `MOBILE_FIREBASE_APP_ID` | Android Firebase application identifier, typically starting with `1:`. |
| `MOBILE_FIREBASE_API_KEY` | Android application’s Firebase API key. |
| `GOOGLE_APPLICATION_CREDENTIALS` | Path to a private, read-only credential file when using file-backed ADC. Workload credentials are also supported. |
| `MOBILE_PUSH_BIND`, `MOBILE_PUSH_PORT` | Listen address and port. Default `127.0.0.1` and `8766`. A container can bind its private network interface behind the authenticated HTTPS origin. |
| `MOBILE_PUSH_POLL_SECONDS` | Ledger observation interval, default 2 seconds. |
| `MOBILE_PUSH_RECONCILE_SECONDS` | Interval between retained-history sweeps, default 600 seconds. Normal observations read recent changes with a two-poll overlap. |
| `MOBILE_PUSH_RETENTION_SECONDS` | Time to retain events and attempt delivery, default 604800 seconds. |
| `MOBILE_PUSH_RETRY_MAX_SECONDS` | Maximum retry backoff, default 3600 seconds. |
| `MOBILE_PUSH_SEND_TIMEOUT_SECONDS` | Timeout for an FCM request, default 10 seconds. |
| `MOBILE_PUSH_LOG_LEVEL` | Python log level, default `INFO`. |

Build from `server/Dockerfile` using `server/` as the context. The image runs as UID and GID 10000. The bridge needs read access to the isolated gateway’s SQLite files and an outbound route to Google's authentication and messaging services. For a local Python installation use Python 3.11 or newer, install `server/requirements.txt` into a virtual environment, then run `python -m mobile_push` from `server/`.

Use a device with Google Play services. The Android SDK binds its default Firebase Messaging instance to one project per app installation. Multiple Hermes accounts on one phone can share that Firebase project while keeping distinct installation UUIDs, management keys and profile bearers. Disable the previous project’s enrollments before switching projects. Configure notifications from the app and grant the Android notification permission. Device dismissal and an operating-system process eviction leave FCM able to deliver data messages to the native receiver. Android Settings force-stop requires manually reopening the app. Offline delivery depends on network access, the event retention window and provider delivery. FCM high priority is reserved for user-visible notifications. [Android notification permission](https://developer.android.com/develop/ui/views/notifications/notification-permission) · [FCM priorities](https://firebase.google.com/docs/cloud-messaging/android/message-priority) · [Force-stop behavior](https://firebase.google.com/docs/cloud-messaging/flutter/receive-messages)

## Hermes event sources

The bridge reads the fork's profile-local databases. It can re-read recent entries after restart and places events and per-device deliveries into a single SQLite transaction. Android run starts use a stable `Idempotency-Key` with `POST /v1/runs`. Hermes writes these reservations and their authenticated status to `runs_idempotency.db`. The bridge selects only that API bearer’s run scope and reads run, session, status and approval identity. Each distinct pending approval can generate a new alert. The canonical gateway persists opaque pending prompt identities with each owned run. The bridge retracts queued alerts when their prompt settles, is replaced, or its run stops. A request already in flight to FCM can still arrive after settlement. Opening it retrieves the current state. Approvals that were already answered between ledger observations need no response. Completed and failed runs are retained by their stable run ID. Room-grant runs use a separate Hermes namespace.

Create and manage reminders with the normal Hermes `/api/jobs` API. Set delivery to `local` to keep the job output in Hermes. When a scheduled or manually triggered job actually starts, Hermes records an execution in `cron/executions.db`. That execution creates one reminder event and can be opened by its job ID. The scheduler must be active for a reminder to fire. The bridge observes every job in that user's isolated runtime. Offline reconciliation is bounded by both the bridge retention period and the fork’s own ledger retention. [Hermes run writer](https://github.com/LLM-4-People/hermes-agent/blob/c7a3fe245d6bf610f73ff555d57e4ed632f8ded8/gateway/platforms/api_server_runs.py#L312) · [Cron execution ledger](https://github.com/LLM-4-People/hermes-agent/blob/c7a3fe245d6bf610f73ff555d57e4ed632f8ded8/cron/executions.py#L329)

## Enrollment and synchronization

All responses are private and carry `Cache-Control: no-store`. Discovery and event reads require the profile bearer. Each local profile generates its own random UUIDv4 installation ID and random 32-byte management key. Send the management key as 64 lowercase hexadecimal characters. Keep it on the device in protected storage so the OS can rotate an FCM registration while the profile is locked.

| Request | Contract |
| --- | --- |
| `GET /api/mobile/push` | Lists enabled `providers`. With FCM enabled it also returns `provider: "fcm"` for earlier clients and public `firebase` fields `applicationId`, `gcmSenderId`, `projectId`, `apiKey`. |
| `PUT /api/mobile/push/devices/{id}` | Enroll with the profile bearer and a JSON body containing `token` and `management_key`. The token is the registration token supplied by the app's Firebase Messaging version. A 204 confirms persistence. |
| `PUT /api/mobile/push/devices/{id}` with device bearer | Rotate the destination with the management key as bearer and a JSON body containing only `token`. A 409 means the token belongs to another installation in this runtime. |
| `DELETE /api/mobile/push/devices/{id}` | Revoke the device and its queued deliveries. The profile bearer receives 204 even for an absent device. A well-formed device bearer receives 204 after removing its own matching binding, including on retry when the binding is already gone. Other devices’ enrollments stay private. |
| `GET /api/mobile/push/events/{event_id}` | Returns event kind, timestamp and nullable `run_id`, `session_id`, `job_id`. Using an unknown or expired ID returns 404. |
| `GET /api/mobile/push/events?after=0&limit=50` | Returns `events`, `next_cursor` and `has_more` in insertion order. Retain the cursor per profile and fetch while `has_more` is true. Maximum page size is 100. |

A provider acceptance settles an outbox row. A network error leaves it queued with backoff. The sender honors the provider’s `Retry-After` header within the event retention window. Deferred delivery logs identify the transport, local reason or HTTP status, attempt count and scheduled delay. If a worker exits after the provider accepts a request, a retry can arrive with the same event ID. Device handling must deduplicate using both the installation and event IDs. An unregistered-token response removes that exact device registration, with a lease check to protect a newer registration. Opening an alert only navigates. Approval decisions go through the existing authenticated run approval API.

## Verify delivery

Run `python -m unittest discover -s server/tests` from the repository root after installing the server dependencies. To also exercise a locally installed Hermes fork, set `HERMES_TEST_CHECKOUT` to its checkout in an environment with the fork’s Python dependencies. Use the matching fork’s test environment for the optional contracts. They create run and cron rows through Hermes’s real writers, raise and deny a real canonical test approval, and inspect the bridge’s outgoing FCM envelopes through a local transport capture.

On an enrolled Android device, launch a run, dismiss the activity and inspect the notification drawer after completion. Open the alert, unlock its profile and confirm its thread. Repeat for a scheduled reminder, then check that a second isolated user receives neither event. Deny notification permission and confirm that the authenticated event feed still recovers the event. Revoke the device and verify its old management key receives 401 for token rotation and 204 for a replayed removal. Later runs should produce no alert there. Run these device checks with an enrolled installation, a real gateway and the selected push transport. The FCM route requires a provisioned Firebase project.

<!-- Generated from docs/sources/docs/push.md.in by scripts/docs.py. -->
