# Self-hosted Android notifications

Hermes Mobile can receive closed-app notifications through the ntfy Android distributor on an AOSP device. The operator hosts an ntfy server. Its Android app maintains the connection to that server and delivers encrypted Web Push messages to Hermes Mobile. The bridge, notification history, unlock flow and per-user event permissions are shared with Firebase delivery. [UnifiedPush protocol](https://unifiedpush.org/developers/intro/) · [ntfy Android setup](https://docs.ntfy.sh/subscribe/phone/)

## Configure the bridge

Keep the account’s normal gateway and push-bridge configuration. In its private `push.env`, set these two values using your own hostname and contact address.

```ini
MOBILE_UNIFIEDPUSH_ORIGIN=https://push.example.org
MOBILE_VAPID_SUBJECT=mailto:operator@example.org
```

The origin is the exact HTTPS scheme, hostname and optional port of the ntfy instance. Set the same base URL on the phone. Use a valid VAPID subject with the operator's contact address. A loopback-only test can use `http://127.0.0.1:PORT`. Setup with UnifiedPush alone needs these two provider settings. Add the Firebase settings described in the [push guide](push.md) to also offer Firebase.

On first startup the bridge generates a VAPID signing key in its owner-only state directory. Preserve that volume across updates and include it in private backups. The authenticated discovery endpoint advertises the public key to the phone. Moving a registration to another signing key or another origin requires enrolling again.

## Set up the phone

Install the ntfy Android distributor and select the matching self-hosted ntfy server as its default server. Enable UnifiedPush distribution in ntfy. Unlock the intended Hermes profile. In **Atelier → Notifications**, choose **Activer**, then **Activer avec UnifiedPush**. Use **Choisir un distributeur** to select ntfy. Allow Android to display notifications. To change distributors, turn off enrollments using the current distributor first. The distributor must be able to reach its server in the background. A user-initiated Android force-stop requires reopening the stopped app.

## Device contract

The bridge responds to authenticated `GET /api/mobile/push` with its enabled `providers`, including `unifiedpush`, and an object `unifiedpush.vapidPublicKey`. The public key is an unpadded base64url P-256 point.

1. Reserve the profile’s installation at `PUT /api/mobile/push/devices/{uuid}` with the profile bearer and body `{"provider":"unifiedpush","management_key":"…"}`. The pending reservation receives no events.
2. Register that instance with the selected distributor and the advertised VAPID public key.
3. On the distributor callback, bind the same installation with its management bearer and body `{"provider":"unifiedpush","subscription":{"endpoint":"…","keys":{"p256dh":"…","auth":"…"}}}`. Use the same call for endpoint or key rotation.
4. Revoke it using the usual authenticated device DELETE. A subsequent management update will be refused.

The server accepts ntfy distributor URLs containing a generated topic on the configured origin. It refuses redirects. It encrypts only the event ID, installation ID and kind using RFC 8291, signs the request with VAPID and bounds delivery by the remaining event retention. The phone retrieves the associated private thread or reminder from the authenticated gateway.

<!-- Generated from docs/sources/docs/unifiedpush.md.in by scripts/docs.py. -->
