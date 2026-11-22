# Published files

The file shelf belongs to one profile and discussion. It follows the verified Hermes compression lineage when the conversation moves to a new session ID. Each fork has its own shelf. In the app, open **Atelier → Artefacts → Fichiers publiés** for a discussion. Choose a file to preview text or a supported image, or choose **Enregistrer sous…** to open Android’s document picker. After returning from the picker, unlock the same profile to complete the export. The app checks the byte length and SHA-256 digest before writing to the selected document. An interrupted copy signals an error to providers that support reliable file descriptors. A provider may retain an incomplete document. Unlocking the originating profile displays an interruption notice when the app cancelled a transfer while locked. Text previews show the first 16 KiB. Other document types can be saved for use in the reader of your choice.

## Install on a dedicated Hermes gateway

Mount the contents of `server/artifacts` at `$HERMES_HOME/plugins/mobile_artifacts` in each account’s gateway. Run `hermes plugins enable mobile_artifacts` in that profile and restart the gateway. The plugin uses Hermes’s native API-server handler registration, the default in-process plugin mode, and the account’s existing bearer credential. The gateway must own the account hostname so the plugin can serve requests under the same HTTPS origin. A deployment using an API path prefix must preserve the full path through its reverse proxy. The fork’s multiplexed `/p/{profile}` catch-all does not route native plugin handlers.

## Publish a workspace output

From the producing agent’s authorized workspace directory, supply the destination discussion’s exact session ID and a relative path.

```sh
python3 "$HERMES_HOME/plugins/mobile_artifacts/publish.py" --session "$HERMES_SESSION_ID" build/report.pdf
```

Use `--name` to change the display name. Publication copies a regular file into an immutable record beneath `$HERMES_HOME/mobile-artifacts` and prints its generated ID. Links and parent-directory traversal are rejected. Give the helper the profile home and the owning session ID explicitly when the agent’s terminal environment does not supply them. The default per-file limit is 16 MiB. `MOBILE_ARTIFACT_MAX_BYTES` accepts a positive byte count up to 64 MiB. Run the publisher with the Hermes Python runtime so it can resolve the stored session lineage. The publishing process must be able to write to the active profile’s artifact directory.

## HTTP contract

- `GET /api/mobile/files/{session}` returns `data` with the opaque ID, display name, MIME type, byte size, SHA-256 digest and Unix creation time. A non-null `next` is an opaque, exclusive `before` cursor for the next page. The optional `limit` is between 1 and 100.
- `GET /api/mobile/files/{session}/{id}` streams the immutable bytes as a download. Both routes require the gateway bearer token.
- A supported server returns an empty list for a discussion without publications. A gateway without the plugin returns HTTP 404. Code blocks in the transcript remain available through the app’s artifact reader.

The plugin tests exercise the HTTP routes over loopback and the publication store with real temporary profile homes. From the repository root run `python3 -m unittest discover -s server/artifacts -t .` in an environment with aiohttp and Python 3.11 or newer.

<!-- Generated from docs/sources/docs/files.md.in by scripts/docs.py. -->
