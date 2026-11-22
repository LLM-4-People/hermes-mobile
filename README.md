# Hermes Mobile

An Android workspace for chat and coding with your Hermes Agent.

## Install

Download `Hermes-Mobile.apk` from a signed [release](https://github.com/LLM-4-People/hermes-mobile/releases). The release includes its checksum, signing certificate fingerprint and build provenance. Android requires the same signing key for updates. [Verify an APK](docs/delivery.md#verify-a-release)

To build a development APK, set `ANDROID_HOME` to your Android SDK directory, install the platform and build tools declared in `gradle.properties`, and use JDK 21.

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build uses a development signing key. Each pull request also produces a downloadable debug APK after the Android package checks pass.

## Development

Run `python3 scripts/docs.py` to generate documentation and `python3 scripts/docs.py --check` to verify it. Edit the corresponding template under `docs/sources/` and place shared documentation values in `docs/sources/facts.json`.

See [delivery and verification](docs/delivery.md) for the test commands, review checks and release procedure.

<!-- Generated from docs/sources/README.md.in by scripts/docs.py. -->
