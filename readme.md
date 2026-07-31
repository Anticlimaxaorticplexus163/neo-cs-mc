# neo-cs-mc

Chat messages become sounds. A Kotlin Minecraft port of the Garry's Mod [neo-chatsounds](https://github.com/Earu/neo-chatsounds) addon, for **NeoForge** and **Fabric** (MC 1.21.1).

- **Client-first**: parses incoming chat and plays sounds positionally from the sender — works on vanilla servers, no server install required. Plugin-reformatted chat (Paper etc.) is handled through configurable sender-extraction patterns.
- **Optional server component**: when the mod is also on the server it takes authority — repo config sync on join, spam control (token bucket), and hearing-radius filtering, relayed over an optional channel that vanilla clients never see.
- **Same sound data as GMod**: lists and audio download on demand from the same community GitHub repositories (`PAC3-Server/chatsounds-valve-games` by default: csgo, css, ep1, ep2, hl1, hl2, l4d, l4d2, portal, tf2 — ~63k sounds, ~28k keys) through a 4-CDN fallback chain (jsdelivr → statically → githack → raw.githubusercontent). The mod ships zero audio. `repo_config.json` is format-compatible with the GMod addon.
- **Full modifier parity**: the complete parser (scopes, `:modifier(args)`, legacy `%` `^` `--` `++` `*` `#` `=` `%%` `^^` syntaxes, `[expr]` dynamic expressions, `;` parallel contexts, trailing-`!!!` yelling) and all 18 modifiers, driven by a faithful Kotlin port of the WebAudio mixer: extreme pitch (±50x, reverse), echo with feedback tails, low/high-pass, pitch/volume LFOs, sample-accurate loops, seeks, and gated-RMS loudness normalization — synthesized on a dedicated thread into our own OpenAL sources (Minecraft handles 3D spatialization natively, one deliberate improvement over GMod's hand-rolled panning).
- **Chat autocomplete**: suggestions under the chat input backed by the ported completion trie, Tab/Shift-Tab cycling, modifier name/argument hints, `sound#` variant browsing. Vanilla `/command` completion is untouched.

## Commands

| Command | Effect |
|---|---|
| `/chatsounds say <text>` | play locally only |
| `/chatsounds broadcast <text>` | play for others via the server mod (falls back to local) |
| `/chatsounds sh` | stop all sounds (typing `sh` in chat works too, see `shmode`) |
| `/chatsounds toggle` · `volume <0-4>` · `hidetext` · `shmode <0-2>` | client settings |
| `/chatsounds block/unblock sound <index> <key>` (or `realm <name>`, `repository <name>`) | blacklist |
| `/chatsounds reload` · `reloadfull` · `clearcache` | list/cache maintenance |

## Config (`config/chatsounds/`)

- `client_config.json` — enabled, volume, hideText, shMode, maxDistance, pcmCacheMb, senderPatterns, playUnpositioned
- `repo_config.json` — sound repositories (`Repo`/`Branch`/`BasePath`/`UseMsgPack`, GMod-compatible)
- `server_config.json` — (server) hearing radius, op spam exemption
- `blacklist.json`, `dyn_lookup.json`, `repositories/`, `cache/` — managed

## Building

Requires JDK 21.

```sh
./gradlew build                  # everything + tests
./gradlew :neoforge:runClient    # NeoForge dev client
./gradlew :fabric:runClient      # Fabric dev client
./gradlew :neoforge:runServer    # dedicated dev server
```

Jars land in `neoforge/build/libs/` and `fabric/build/libs/`.

## Structure

- `common/` — loader-agnostic logic compiled against Mojang-mapped vanilla: parser (`parser/`), 18 modifiers (`modifiers/`), data layer + trie (`data/`), DSP audio engine (`audio/`), playback orchestration (`playback/`), completion/hide-text (`client/`), relay/spam (`server/`), shared payloads (`net/`).
- `neoforge/` — NeoForge entrypoint, events, channel registration (Kotlin for Forge).
- `fabric/` — Fabric entrypoints and event bridges (Fabric API + fabric-language-kotlin).

Common sources compile directly into each loader jar (MultiLoader pattern); the only mixin is a `ChatScreen` input accessor.

## License

AGPL-3.0, same as neo-chatsounds. Sound content belongs to its respective owners and is downloaded client-side from user-configured repositories.
