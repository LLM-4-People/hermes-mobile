# Hermes Mobile

An Android client for chatting and coding with your own Hermes Agent. Continue a discussion on your phone, review a command that needs approval, read a diff, or schedule a reminder. The interface is in French.

## Get the APK

Pull requests produce an installable development APK. Open a successful **Workspace checks** run, download **android-debug** under **Artifacts**, extract the APK and install it on your phone. The release workflow also produces `Hermes-Mobile.apk` with a certificate, checksum and build provenance when the project owner publishes a version. Android checks the signing identity on updates. [Builds](https://github.com/LLM-4-People/hermes-mobile/actions/workflows/checks.yml) · [Releases](https://github.com/LLM-4-People/hermes-mobile/releases) · [Verify an APK](docs/delivery.md#verify-a-release)

## Connect a profile

1. Start a Hermes gateway with the API server enabled. For different people, give each person a separate runtime, private workspace, HTTPS address and API key. [Provision isolated accounts](docs/accounts.md)
2. In **Connecter Hermes**, enter a name, the HTTPS gateway address and its access key. Set a local passphrase to protect the credentials, saved drafts and settings on this device.
3. Unlock the profile and choose **Nouvelle conversation**. Messages and coding actions run on its Hermes gateway. Returning to the app requires unlocking the profile again.

## Work in a discussion

- **Chat and code.** Follow a streaming reply, see tool activity, answer approval and clarification requests, and cancel ongoing work. On disconnect, the app uses the saved run identity and server state to reconcile the thread. An uncertain coding action needs an explicit decision before it can run again.
- **Model and tools.** Open **Modèle et outils** to choose a model from a configured provider. Per-tool and per-toolset restrictions require a gateway that advertises and enforces those controls. Each discussion keeps its own selection.
- **Usage.** **Utilisation** shows the server's token and cache counters, available price information, and phone-observed timings. Timing and throughput measurements identify their scope. Missing measurements remain visible as unknown.
- **Artifacts.** Open **Artefacts** to compare named revisions or read a diff. The optional file extension lets Hermes publish generated images, text, and binary files into a discussion for authenticated preview or export. [File publication](docs/files.md)
- **Workspace changes.** Ask Hermes to adapt a screen. Inspect its proposal, apply it to this profile, and undo it from **Atelier**. Changes to native capabilities go through a signed application update. [Layout contract and examples](docs/workspace.md)
- **Reminders and alerts.** **Rappels** manages jobs on the gateway. Enable the notification bridge and enroll a device to receive private alerts outside the app. Tapping an alert opens the owning profile before retrieving its content. [Notification setup](docs/push.md)

## Build locally

Use JDK 21, set `ANDROID_HOME` to your Android SDK, and install the platform and Build Tools versions in `gradle.properties`.

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Host and device tests cover the Android client, real Hermes sessions, profile isolation, notifications, and release signing. The live fixture uses real gateway processes and a local model provider. [API verification](docs/api-verification.md) · [Device checks](docs/device-checks.md) · [Release workflow](docs/delivery.md)

Edit documentation in `docs/sources/`. Run `python3 scripts/docs.py` to render it and `python3 scripts/docs.py --check` to verify the result.

<!-- Generated from docs/sources/README.md.in by scripts/docs.py. -->
