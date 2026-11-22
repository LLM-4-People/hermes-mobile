# Verification and delivery

## Pull requests

Use a short-lived branch based on current `main`. Open a pull request after completing a milestone. Preserve unique work before deleting merged branches.

Promotion to `main` requires a pull request, resolved review threads and passing results for all five checks (**Validate sources**, **Workspace tests**, **Android package**, **Android device** and **Phone acceptance / AOSP closed-app delivery**). GitHub requires an up-to-date branch and blocks deletion and force-pushes. Record the independent review, confirmed findings, repairs and verification in the pull request before merging. The repository currently has one maintainer and uses independent project reviewers for this gate. Actions starts with read access and cannot approve pull requests.

The checks validate generated Markdown and workflows, run backend and Android host tests, lint and build the app, and exercise it on Android emulators. The AOSP phone checks closed-app notification delivery using the minified release payload. Download the debug APK from the **android-debug** artifact on a successful package job.

```sh
python3 scripts/docs.py --check
python3 -m unittest discover -s ci
python3 -m pip install -r server/requirements.txt
python3 -m unittest discover -s server/tests
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
./gradlew :app:connectedDebugAndroidTest
```

Run the last command with an unlocked emulator or USB device connected to ADB.

## Signed release

Create an Actions environment named `android-release`. Restrict deployment branches to `main`. Store the following environment secrets.

| Name | Value |
| --- | --- |
| `ANDROID_SIGNING_KEYSTORE_BASE64` | Base64-encoded PKCS12 or Java keystore containing the existing release key |
| `ANDROID_SIGNING_ALIAS` | Release key alias |
| `ANDROID_SIGNING_STORE_PASSWORD` | Keystore password |
| `ANDROID_SIGNING_KEY_PASSWORD` | Private key password |

Set the environment variable `ANDROID_SIGNING_CERT_SHA256` to the SHA-256 fingerprint of the expected release certificate. Use the value from your independently retained release certificate. Keep an offline recovery copy of the signing key for future Android updates.

Set the Android version name and a monotonically increasing version code with `hermesVersionName` and `hermesVersionCode` in `gradle.properties`. Merge the reviewed change. In Actions, dispatch **Signed Android release** on `main`. The workflow reruns the workspace checks and compares two unsigned release builds byte for byte. A fresh AOSP phone exercises closed-app delivery using those exact release contents and a disposable test signature. The release job compares the unsigned digest in the device receipt against its downloaded unsigned APK before using the production key. It verifies the resulting certificate and alignment and generates build provenance. It derives the release tag from the signed manifest. A main revision change or conflicting tag stops publication.

The publisher checks that the manifest version code exceeds every previous published release. It uploads the three release files to a draft and verifies the complete list of names and digests immediately before publication. A repeat run fills missing assets in a draft for the same commit. When the signing algorithm emits new signature bytes, the workflow can recover the original APK from that release after verifying its workflow attestation and commit, signing certificate, alignment and complete archive contents against the checked build. Conflicting assets require inspecting the draft in Releases. Published release assets and tags remain available for version-specific downloads.

## Verify a release

Download `Hermes-Mobile.apk`, `SHA256SUMS` and `release.json` from the same release. Use the Android SDK build tools and GitHub CLI to check all three.

```sh
sha256sum --check SHA256SUMS
apksigner verify --verbose --print-certs Hermes-Mobile.apk
gh attestation verify Hermes-Mobile.apk --repo LLM-4-People/hermes-mobile --signer-workflow LLM-4-People/hermes-mobile/.github/workflows/release.yml
```

Compare the printed signer fingerprint with your trusted release fingerprint. The metadata records the package name, embedded Android version, source commit, unsigned and signed package digests, and signing certificate. To upgrade an existing install made with the same key, run `adb install -r Hermes-Mobile.apk`.

<!-- Generated from docs/sources/docs/delivery.md.in by scripts/docs.py. -->
