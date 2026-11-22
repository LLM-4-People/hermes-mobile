# Deploy multi-profile Hermes accounts on one HTTPS hostname

Hermes Mobile requires our own Hermes Agent fork at [LLM-4-People/hermes-agent](https://github.com/LLM-4-People/hermes-agent) (for single-host multi-profile `/p/{profile}` routing, run-scoped instructions, live UI editing, universal push notifications, universal sharing, Smart Notes, visual approval diffs, and Brain Studio).

**One single HTTPS server hostname** (for example `https://hermes.example.com`) serves every user and profile under `/p/<profile>`. No per-user subdomains or separate HTTPS hostnames are required. Each person gets an isolated Hermes home, coding workspace, threads, configuration, provider credentials, container network, and unique 256-bit gateway access key. Because profiles stay isolated behind one shared server, users on that server can keep private work separate while still sharing notes, tools, widgets, and discussions with other users on the same server.

The fork’s single-process multiplexed listener also enforces per-profile `API_SERVER_KEY` authentication on `/p/<profile>/...` (failing closed with HTTP 401 when a profile has no key or a wrong key is sent), though it treats profiles sharing one OS account as mutually trusted on disk. For stronger OS and filesystem isolation between different people, `deploy/accounts.py` runs separate containers behind a single HTTPS Caddy ingress. [Hermes security model](https://github.com/LLM-4-People/hermes-agent/blob/c7a3fe245d6bf610f73ff555d57e4ed632f8ded8/SECURITY.md#L210-L225)

## Prepare a runtime image

Install Docker, Docker Compose with support for raw environment files (2.30 or newer), and Python 3. Point **one** DNS hostname (such as `hermes.example.com`) at the Docker host. Public TLS certificate issuance uses ports 80 and 443. The local verification command below uses a private CA and an ephemeral loopback port.

Use an immutable registry digest for an image published from our fork [LLM-4-People/hermes-agent](https://github.com/LLM-4-People/hermes-agent). To build it locally, run the following from the mobile repository. It fetches the exact commit used by this checkout’s gateway integration tests and builds the fork’s Dockerfile. Keep the resulting image ID when rendering the deployment or running the isolation test.

```sh
revision=$(python3 -c 'import runpy; print(runpy.run_path("verification/gateway_fixture.py")["PINNED_REVISION"])')
checkout=$(mktemp -d)
git -C "$checkout" init -q
git -C "$checkout" fetch --depth=1 --no-tags https://github.com/LLM-4-People/hermes-agent.git "$revision"
git -C "$checkout" archive "$revision" |
  docker build -t "hermes-mobile-gateway:$revision" --label "org.opencontainers.image.revision=$revision" -
image=$(docker image inspect --format '{{.Id}}' "hermes-mobile-gateway:$revision")
```

## Create multiple profiles on one host

Run these commands in the mobile repository. Choose a private deployment directory outside source control. Replace the sample hostname and profile names with yours.

```sh
deployment="$HOME/.local/share/hermes-mobile-deployment"
python3 deploy/accounts.py "$deployment" --image "$image" \
  --host hermes.example.com --profile north --profile south
```

This creates one shared HTTPS proxy for `https://hermes.example.com` routing `/p/north/*` to `gateway-north` and `/p/south/*` to `gateway-south`, while returning HTTP 404 for unknown paths or nested `/p/` prefixes. The renderer creates a distinct 256-bit key in each profile’s `gateway.env` with owner-only permissions. Repeating the command preserves existing keys and settings. Use an established private channel to give each person the shared server URL (`https://hermes.example.com`), their **Profil serveur** name (`north` or `south`), and their access key. In Android’s **Connecter Hermes** screen, entering the server URL and profile name composes `https://hermes.example.com/p/north` automatically, then protects credentials with a device-local passphrase.

Put provider credentials in the corresponding `accounts/north/provider.env` or `accounts/south/provider.env`. These raw Compose files accept literal `NAME=value` lines. Supply only the values intended for that profile. Provider files are visible only to their own gateway container. Each container’s supervised gateway runs as the image’s Hermes user, with its workspace at `/opt/data/workspace` inside its own persistent `HERMES_HOME` volume.

On first boot, the mobile deployment uses the gateway image’s configuration example and sets `platform_toolsets.api_server` to `[hermes-api-server, clarify]`. This lets compatible clients answer the agent’s clarification questions during a run. An existing `config.yaml` or an explicit API toolset in a custom image takes precedence. To opt an existing profile in, add `clarify` to its intended API toolsets and restart its gateway. Profile-level disabled toolsets and per-run tool restrictions still apply. Authenticated `GET /v1/toolsets` reports whether `clarify` is enabled.

Enable the bundled file-artifact plugin in each new Hermes home, then start the stack. The renderer saves a unique Compose identity in the private deployment directory’s `project-id` file. Keep it with the keys when moving the deployment. Each new deployment gets new volumes, even if two directories share a name. Let Compose use the generated project name when starting the stack.

```sh
docker compose -f "$deployment/compose.json" config --quiet
docker compose -f "$deployment/compose.json" run --rm gateway-north plugins enable mobile_artifacts
docker compose -f "$deployment/compose.json" run --rm gateway-south plugins enable mobile_artifacts
docker compose -f "$deployment/compose.json" up -d
docker compose -f "$deployment/compose.json" ps
```

Keep using the absolute `$deployment/compose.json` path for Compose commands. Compose resolves the account environment files and bind mounts relative to that file. Use `config --quiet` when validating the stack because the full configuration includes resolved secrets. [Compose file selection](https://docs.docker.com/reference/cli/docker/compose/#use--f-to-specify-the-name-and-path-of-one-or-more-compose-files)

The gateway mounts the shared read-only plugins from `server/artifacts` and `server/nearby`. For mapped restaurant guides, configure the profile’s Overpass interpreter. [Nearby place data](nearby-places.md) Before updating an existing deployment, record its current image reference and back up each home independently. Rerun the original renderer command with the reviewed image digest, `--host`, every `--profile` and enabled `--push` bridge, and any custom proxy image or local TLS options. Apply the generated stack with `docker compose -f "$deployment/compose.json" up -d`. After a route change, run `docker compose -f "$deployment/compose.json" up -d --no-deps --force-recreate https` so Caddy mounts the new file.

## Enable closed-app notifications

Each participating profile can use UnifiedPush, Firebase, or both. Put its configured provider values in `$deployment/accounts/ID/push.env`. For UnifiedPush, configure `MOBILE_UNIFIEDPUSH_ORIGIN` and `MOBILE_VAPID_SUBJECT`. The bridge keeps its VAPID key in the profile’s outbox volume. For Firebase, include its four client configuration values and install the service credential at `$deployment/accounts/ID/firebase.json`. Only Firebase-enabled bridges mount that credential. [Push configuration](push.md) The private deployment directory protects the host files. The service credential needs to be readable by container UID 10000. On a Linux host with regular Docker file mounts, set the credential’s group to 10000 and mode to 0640. Rootless Docker may map that ID differently.

From the mobile repository, rerender the full deployment. This example enables push for `north` and keeps both gateways on the same hostname. Include a separate `--push ID` for each profile that should keep or gain notifications. Also preserve any existing `--proxy-image`, `--local`, and `--port` options.

```sh
python3 deploy/accounts.py "$deployment" --image "$image" \
  --host hermes.example.com --profile north --profile south \
  --push north
docker compose -f "$deployment/compose.json" config --quiet
docker compose -f "$deployment/compose.json" up -d --build
docker compose -f "$deployment/compose.json" up -d --no-deps --force-recreate https
```

The renderer replaces the Caddyfile atomically. Recreating `https` binds the new file at the existing path and loads the push route. When replacing a Firebase credential at the same path, recreate the affected bridge so it mounts and loads the new credential. For `north`, run `docker compose -f "$deployment/compose.json" up -d --no-deps --force-recreate push-north`. [Compose service recreation](https://docs.docker.com/reference/cli/docker/compose/up/)

Each push bridge has its own outbox and mounts only its profile’s Hermes home read-only to observe persisted events. The bridge is trusted with that profile’s run and reminder metadata. The gateway receives `provider.env` and the bridge receives `push.env`. Caddy strips `/p/<profile>` and forwards `/p/<profile>/api/mobile/push` and its subordinate routes to that profile’s bridge on the same single hostname as chat. Firebase delivery verification requires a configured project and a registered Android device.

## Check isolation

```sh
python3 -m unittest discover -s deploy -v
python3 deploy/smoke.py --image "$image" --push
```

The smoke test provisions two disposable profiles (`north` and `south`) behind **one** loopback HTTPS hostname (`hermes.localhost`), verifies the generated CA, exercises both bearer keys against `/p/north/...` and `/p/south/...`, confirms unprefixed and unknown profile paths return 404, and checks independent session and workspace storage. It checks clarification discovery on fresh profiles, then restarts the gateways with explicit tool restrictions and verifies that later runs enforce them. It removes only the temporary Compose project and its volumes on exit. The push check provisions a dual-transport profile and a UnifiedPush-only profile on that same hostname. It verifies the advertised providers, checks that each profile retains its own VAPID key across a restart, and reads a real Hermes reminder ledger entry through its owning bridge. It uses disposable credentials and registers zero devices. Neither check requires a model provider.

<!-- Generated from docs/sources/docs/accounts.md.in by scripts/docs.py. -->
