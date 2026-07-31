# neo-chatsounds (Minecraft Edition:tm:)

Memes ported into your own Minecraft free of charge.

A Kotlin Minecraft port of [neo-chatsounds](https://github.com/Earu/neo-chatsounds), for **NeoForge** and **Fabric** (MC 1.21.11; version branches: `1.21.1`, `1.20.1`).

- **Client-first**: parses incoming chat and plays sounds positionally from the sender, works on vanilla servers, no server install required. Plugin-reformatted chat (Paper etc.) is handled through configurable sender-extraction patterns.
- **Optional server component**: when the mod is also on the server it takes authority, repo config sync on join, spam control (token bucket), and hearing-radius filtering, relayed over an optional channel that vanilla clients never see.
- **Same sound data as the original**: lists and audio download on demand from GitHub repositories you configure, through a 4-CDN fallback chain (jsdelivr → statically → githack → raw.githubusercontent). The mod ships zero audio and no repositories — add your own to `repo_config.json` ([repo_config.example.json](repo_config.example.json) shows the format, which is compatible with the GMod addon's).
- **Full modifier parity**: the complete parser (scopes, `:modifier(args)`, legacy `%` `^` `--` `++` `*` `#` `=` `%%` `^^` syntaxes, `[expr]` dynamic expressions, `;` parallel contexts, trailing-`!!!` yelling) and all 18 modifiers, driven by a faithful Kotlin port of the WebAudio mixer: extreme pitch (±50x, reverse), echo with feedback tails, low/high-pass, pitch/volume LFOs, sample-accurate loops, seeks, and gated-RMS loudness normalization — synthesized on a dedicated thread into our own OpenAL sources (Minecraft handles 3D spatialization natively, one deliberate improvement over GMod's hand-rolled panning).
- **Chat autocomplete**: suggestions under the chat input backed by the ported completion trie, Tab/Shift-Tab cycling, modifier name/argument hints, `sound#` variant browsing. Vanilla `/command` completion is untouched.
- **Environmental audio compat**: with [Sound Physics Remastered](https://github.com/henkelmax/sound-physics-remastered) or [Dynamic Surroundings](https://github.com/OreCruncher/DynamicSurroundingsFabric) installed, chatsounds voices get the same reverb/occlusion as every other sound (reflective bridges, no hard dependency; Sound Physics takes priority when both are present).

## Commands

| Command | Effect |
|---|---|
| `/chatsounds toggle` · `volume <0-4>` · `hidetext` · `shmode <0-2>` | client settings |
| `/chatsounds invertprefix` | flip the `;` prefix: normally it blocks chatsounds for a message; inverted, only `;`-prefixed messages play |
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