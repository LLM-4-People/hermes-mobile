# Verify a closed-app UnifiedPush delivery

Use a disposable, fully booted Android 13 or newer emulator with SystemUI and an English system locale. The test installs the supplied signed Hermes Mobile APK and a checksum-verified upstream ntfy APK. Both packages must be absent at the start, and both are uninstalled during cleanup. Automated Test Device images omit the notification shade needed to exercise the tap.

On a Linux x86-64 host, prepare a clean checkout of the Hermes fork and its Python development environment. Install `server/requirements.txt` into a separate Python environment for the relay. Docker supplies the same digest-pinned Caddy binary as the account deployment. With the current directory at the mobile project root, run:

```bash
"$fork/scripts/run-in-hermes-env" python3 -B -m verification.unifiedpush.prove \
  --source "$fork" --push-python "$relay_env/bin/python" \
  --apk app/build/outputs/apk/debug/app-debug.apk --serial "$serial"
```

`--adb` and `--adb-port` select the Android SDK client and server. `--cache` selects the downloaded distributor and server archive directory. The fixture verifies each file against its pinned SHA-256 before use. The first fetch requires network access to the public release artifacts. The test creates temporary keys, isolated Hermes homes, a local ntfy service and a private UnifiedPush relay. Android reaches only their loopback ports through ADB reverse mappings.

The test enrolls one of two protected local accounts through the app’s own controls and opens the other account. It backgrounds Hermes, calls Android’s Task Manager stop operation and checks that the process is absent while the package remains runnable. It starts a new server-side run and matches the resulting event to a binary message in the real ntfy topic and the active Android notification. It taps the system notification and unlocks the owning profile to open the newly created thread. It then revokes the subscription and checks that the next completed run is recorded without another topic message or device alert. A successful pass prints a JSON result. The temporary profiles, database and port forwards are removed during teardown.

The installed distributor is the F-Droid flavor published by the ntfy project. It uses a persistent connection to the explicitly selected private server. This lane can run without a cloud account. Firebase delivery has a separate infrastructure requirement.

[Official ntfy Android release](https://github.com/binwiederhier/ntfy-android/releases/tag/v1.25.2) · [Official ntfy server release](https://github.com/binwiederhier/ntfy/releases/tag/v2.38.1) · [Android Task Manager testing](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)

**Phone acceptance / AOSP closed-app delivery** runs for every pull request and can be run manually through **Workspace checks**. It downloads the minified release package compiled by **Android package**, signed for the test with a disposable key. The app uses HTTPS for its private test gateways. A dedicated full AOSP emulator trusts the fixture certificate. The check validates the official SDK image and the absence of Google services.

The receipt compares Android's installed base APK digest with the checked artifact. It records the app version, source and gateway commits, unsigned and signed APK digests, component versions and every required delivery assertion. The production signing job waits for the same check and independently verifies the unsigned APK digest before it can sign.

<!-- Generated from docs/sources/docs/unifiedpush-verification.md.in by scripts/docs.py. -->
