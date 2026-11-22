# Hermes Mobile

An Android client for chatting and coding with your own Hermes Agent. Ask a question, talk through a code change, inspect a diff, or schedule a reminder. When Hermes needs a decision, it offers choices in the discussion. The interface is in French.

## Get the APK

Pull requests produce an installable development APK. Open a successful **Workspace checks** run, download **android-debug** under **Artifacts**, extract the APK and install it on your phone. The release workflow also produces `Hermes-Mobile.apk` with a certificate, checksum and build provenance when the project owner publishes a version. Android checks the signing identity on updates. [Builds](https://github.com/LLM-4-People/hermes-mobile/actions/workflows/checks.yml) · [Releases](https://github.com/LLM-4-People/hermes-mobile/releases) · [Verify an APK](docs/delivery.md#verify-a-release)

## Connect a profile

1. Start a Hermes gateway with the API server enabled. For different people, give each person a separate runtime, private workspace, HTTPS address and API key. [Provision isolated accounts](docs/accounts.md)
2. In **Connecter Hermes**, enter a name, the HTTPS gateway address and its access key. Set a local passphrase to protect the credentials, saved drafts and settings on this device.
3. Unlock the profile and choose **Nouvelle conversation**. Messages and coding actions run on its Hermes gateway. Returning to the app requires unlocking the profile again.

## Talk to Hermes

Write a request in your own words or tap **Dicter** and speak. For example, ask « Peux-tu m’expliquer ce changement et me proposer deux façons de le tester ? » Edit the transcript, then tap **Envoyer**. On supported phones, Hermes uses Android’s on-device recognizer. If French is available to download, open **Dictée hors connexion…** and choose **Télécharger le français…**. Once Android finishes the download, tap the microphone to dictate. If recognition needs the system speech service, the app explains that the service may send audio to its provider and lets you choose whether to use it.

When Hermes has a question, its suggestions appear in the discussion. Tap a single choice, select several if the question allows it, or choose **Écrire une réponse** to type or dictate your own answer. A coding approval shows what Hermes proposes and lets you inspect the technical details. You can allow that action once or decline it from the same screen.

Tap **Écouter** on a question or choose **Écouter la dernière réponse** from the discussion menu to listen. Playback uses an installed offline French voice and stops when you leave the discussion, lock the account, or begin dictating.

To ask about a screenshot or photo, open **Autres actions → Joindre une image…** in the discussion. Select one still image, review its preview, write your question, then tap **Envoyer**. **Retirer** keeps the question in the composer. The phone re-encodes the image without its original file metadata. The selected gateway and its configured vision provider receive the pixels when you send. [Hermes vision settings](https://hermes-agent.nousresearch.com/docs/user-guide/features/vision) · [What a local vision model saw](docs/local-vision.md)

## Bring work in from another Android app

Select a passage of code and choose **Demander à Hermes**, or share text or a link through Android’s share sheet. Unlock the intended profile, check the discussion and choose **Ajouter au brouillon**. Hermes appends the selection to your draft. You can add a question such as « Où ce calcul peut-il perdre de la précision ? » before tapping **Envoyer**.

Hermes can also prepare work for another app. For example, ask « Prépare un rendez-vous dans mon agenda, un message pour mon équipe et une recherche du lieu sur la carte. » Review the event, text or place in the discussion. When you choose an action, Android opens the corresponding editor, share sheet or map. [Android actions and a real model example](docs/android-actions.md)

## Turn a review into a reusable tool

Ask Hermes « Transforme cette revue de code en un outil pour ce fil. » Try the form in the reply, enter a file and goal, and pin it to the discussion. On a wide display the form sits beside the conversation. Ask « Ajoute un champ de priorité. » You can inspect the pinned form, the proposed form, and the specific changes before adopting a version. Preparing a message from the form leaves its text in your composer until you send it. [Working with generated tools](docs/workspace.md) · [See a code review become a form on Android](docs/local-model-review.md)

## Work in a discussion

- **Chat and code.** Follow a streaming reply, inspect tool activity and cancel ongoing work. On disconnect, the app uses the saved run identity and server state to reconcile the thread. An uncertain coding action needs an explicit decision before it can run again.
- **Model and tools.** Open **Modèle et outils** to choose a model from a configured provider. Per-tool and per-toolset restrictions require a gateway that advertises and enforces those controls. Each discussion keeps its own selection.
- **Skills.** In **Modèle et outils**, open **Compétences de Hermes** to search the skills installed on this profile’s gateway. Tap a result to read its summary, then **Préparer le brouillon** to add it to the discussion draft. Add the task you want it to perform and tap **Envoyer**. Some skills require configuration on the gateway. [Use a photo or an installed skill in a discussion](docs/discussion-context.md)
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
