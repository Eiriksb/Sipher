# Compatibility promises

From 1.0 on, players will run different Sipher versions on clients and servers, and other mods build on Sipher. These
are the rules every change has to follow within a major version (1.x). Breaking one of them needs a new major version.

## Clients and servers on different versions

Sipher's network channel is optional in both directions: players without Sipher can join servers that have it, and
Sipher players can join servers without it. The same must hold between any two 1.x versions.

- **Never change `PROTOCOL` in `SipherNetwork`.** NeoForge's "optional" only covers a channel that is missing. When
  both sides have the channel with different versions, negotiation fails and the player cannot join at all
  (`neoforge.network.negotiation.failure.version.mismatch`). So the protocol version stays `"1"` for all of 1.x.
- **Never change an existing payload's codec** (`CaptionUpdatePayload`, `CaptionPayload`). An older client or server
  would read the new bytes with the old codec and disconnect.
- **To change what is sent, add a new payload type** (for example `sipher:caption_v2`), registered as optional next to
  the old one. Senders check `hasChannel` and use the newest type the other side has, falling back to the old one.
  Old payload types are removed only in a new major version.
- The server only sends Sipher payloads to players whose connection has the channel (`CaptionRelay`). NeoForge throws
  if a mod sends a payload to a client without it.

## Language packs

Every released jar carries the pack catalogue (`src/main/resources/sipher/catalog.json`), with the URL and SHA-256
checksum of every file.

- **Files on the `models-v1` release are permanent.** They are never deleted or replaced, not even to fix them:
  every jar that points at them would stop working or fail its checksum.
- New or improved models go on a new release (`models-v2`, …) and reach players through a new catalogue in a new Sipher
  version. Older jars keep downloading from the release they know.
- A pack's language code and component ids stay the same, so installed packs keep working after an update.

## `PlayerCaptionEvent`

`io.github.eiriksb.sipher.api.PlayerCaptionEvent` is Sipher's public API for server mods (for example
[They Will Talk](https://github.com/Eiriksb/they-will-talk)).

- Within 1.x, existing methods keep their names, types and meaning. When it's posted also stays the same: on the
  server thread, for every caption the server accepts, whether or not relaying to players is enabled.
- New information may be added as new methods. Nothing is removed or renamed before 2.0.
- Everything outside the `api` package is internal and may change in any release.

## Settings

- Keys in `sipher-client.toml` and `sipher-server.toml` are not renamed or removed within 1.x, and their defaults don't
  change meaning. A setting that is no longer used is ignored, not deleted, until the next major version.

## Minecraft and Simple Voice Chat

- Sipher 1.x targets Minecraft 1.21.1 with NeoForge. Other Minecraft versions get their own build.
- Simple Voice Chat 2.5 or newer: the oldest API with every event Sipher uses (`voicechat_api_version_range` in
  `gradle.properties`). Raising that minimum is called out in the changelog.
