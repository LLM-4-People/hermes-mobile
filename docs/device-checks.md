# Verify chat and account isolation on Android

Use a disposable Android API 30 or later phone emulator. Install the debug app and its instrumentation APK. The journey uses two temporary Hermes runtimes backed by the fork’s loopback model fixture. It creates private test accounts, sends chat and code requests, applies a layout proposed by the model, and restores the workspace.

Run the following from the repository root in the fork’s test environment. Replace the checkout and emulator serial with your own.

```sh
python verification/live_api.py --source /path/to/clean/hermes-agent --require-tool-controls \
    --device-command python verification/device/run.py --serial emulator-5554 \
    --output /tmp/hermes-mobile-captures
```

Add `--adb-port` to the device command when the emulator uses a dedicated ADB server. The driver creates its own loopback port reversals and removes them on exit. Each run uses fresh local passphrases. It checks both the server-provided model inventory and the handset’s private thread state.

The first device phase verifies chat, a code artifact, agent layout apply and undo, model settings, usage, reminder creation, pause, resume and deletion. It opens the software keyboard and sets a private draft. A second user sees an empty history, and that user’s passphrase fails to open the first account. The second phase force-stops and relaunches the app, unlocks the original account and checks the restored draft with a 1.5× font scale in night mode. It removes the two synthetic accounts once both phases pass. The host restores the emulator’s original night mode, font scale and keyboard settings even when a check fails.

Snapshots are drawn inside the test process from the focused app window. Android’s private-window flag stays enabled. The keyboard-visible capture includes the resized application at the time the input method reports itself visible. Measuring actual push delivery after ordinary process death requires a push-capable handset or emulator and a configured provider.

<!-- Generated from docs/sources/docs/device-checks.md.in by scripts/docs.py. -->
