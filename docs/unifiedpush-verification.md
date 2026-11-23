# Verify a closed-app UnifiedPush delivery

Use a disposable, fully booted Android 13 or newer emulator with SystemUI and an English system locale. The test installs the supplied signed Hermes Mobile APK and a checksum-verified upstream ntfy APK. Both packages must be absent at the start, and both are uninstalled during cleanup. Automated Test Device images omit the notification shade needed to exercise the tap.

Use the Android SDK platform and build tools from gradle.properties and a JDK on PATH. The driver compiles an external SDK UI Automator helper. One shell process reads visible controls and enters values through their Android accessibility labels across both apps.

On a Linux x86-64 host, prepare a clean checkout of the Hermes fork and its Python development environment. Install `server/requirements.txt` into a separate Python environment for the relay. Docker supplies the same digest-pinned Caddy binary as the account deployment. With the current directory at the mobile project root, run:

```bash
"$fork/scripts/run-in-hermes-env" python3 -B -m verification.unifiedpush.prove \
  --source "$fork" --push-python "$relay_env/bin/python" \
  --apk app/build/outputs/apk/debug/app-debug.apk --serial "$serial"
```

`--adb` and `--adb-port` select the Android SDK client and server. `--cache` selects the downloaded distributor and server archive directory. The fixture verifies each file against its pinned SHA-256 before use. The first fetch requires network access to the public release artifacts. The test creates temporary keys, isolated Hermes homes, a local ntfy service and a private UnifiedPush relay. Android reaches only their loopback ports through ADB reverse mappings.

For a minified release APK, create a fresh named AVD from the official Android 13 AOSP `default;x86_64` revision 2 image pinned in `ci/aosp-phone.json`. Start it with `-writable-system -no-snapshot`. The emulator uses a temporary writable copy of the SDK system image. Set `ANDROID_HOME` to the SDK directory and put OpenSSL and the JDK on PATH. Set `avd` to the AVD name, `serial` to its running emulator serial, `fork_python` to the Python executable in the fork’s development environment and `signed_apk` to the signed candidate. [Android emulator command-line options](https://developer.android.com/studio/run/emulator-commandline)

```bash
python3 -B ci/aosp_trust.py --avd "$avd" --serial "$serial" -- \
  "$fork_python" -B -m verification.unifiedpush.prove \
    --source "$fork" --push-python "$relay_env/bin/python" \
    --apk "$signed_apk" --serial "$serial"
```

The wrapper checks the named AOSP userdebug phone, creates a one-day private CA and issues a server certificate for `127.0.0.1`. It provisions the public CA in the emulator through Android’s development remount and reboot procedure. Android’s default trust manager must reject the leaf before installation and accept it afterward. The wrapper supplies the child process with `HERMES_FIXTURE_TLS_CA`, `HERMES_FIXTURE_TLS_CERT` and `HERMES_FIXTURE_TLS_KEY`. The clients verify the certificate chain and IP address for both HTTPS account gateways. The ntfy service uses a private loopback port.

On exit, the wrapper removes the test CA, restores verified system boot and the original ADB user, and verifies that Android rejects the leaf again. SELinux stays enforcing throughout. Temporary certificate files and the Android probe are removed. Shut down and discard the dedicated AVD after the run.

The test enrolls one of two protected local accounts through the app’s own controls. It taps **Open distributor** and waits for the real ntfy server to report a live subscriber on the enrolled topic. It opens the other Hermes account and confirms the subscriber remains connected. It backgrounds Hermes, calls Android’s Task Manager stop operation and checks that the process is absent while the package remains runnable.

The test starts a new server-side run and matches the resulting event to a binary message in the real ntfy topic and the active Android notification. It taps the system notification and unlocks the owning profile to open the newly created thread. The same visible screen must contain its unique title, its unique assistant reply and the thread composer. It then revokes the subscription and checks that the next completed run is recorded without another topic message or device alert. A successful pass prints a JSON result. The optional `--evidence-dir` creates a new directory for the native notification labels and the visible controls in the protected test thread. The fixture masks the rest of the notification shade before writing it. Hermes keeps its secure-window flag enabled. The temporary profiles, database and port forwards are removed during teardown.

The installed distributor is the F-Droid flavor published by the ntfy project. It uses a persistent connection to the explicitly selected private server. This lane can run without a cloud account. Firebase delivery has a separate infrastructure requirement.

[Official ntfy Android release](https://github.com/binwiederhier/ntfy-android/releases/tag/v1.25.2) · [Official ntfy server release](https://github.com/binwiederhier/ntfy/releases/tag/v2.38.1) · [Android Task Manager testing](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)

**Phone acceptance / AOSP closed-app delivery** runs for every pull request and can be run manually through **Workspace checks**. It downloads the minified release package compiled by **Android package**, signed for the test with a disposable key. The app uses HTTPS for its private test gateways. A dedicated full AOSP emulator trusts the fixture certificate. The check validates the official SDK image and the absence of Google services.

The receipt compares Android's installed base APK digest with the checked artifact. It records the app version, source and gateway commits, unsigned and signed APK digests, component versions and every required delivery assertion. The production signing job waits for the same check and independently verifies the unsigned APK digest before it can sign.

<!-- Generated from docs/sources/docs/unifiedpush-verification.md.in by scripts/docs.py. -->
