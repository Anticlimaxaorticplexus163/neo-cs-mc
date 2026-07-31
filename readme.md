# neo-cs-mc

Chat messages become sounds. A Kotlin Minecraft port of the Garry's Mod [neo-chatsounds](https://github.com/Earu/neo-chatsounds) addon, for NeoForge (Fabric planned).

- **Client-first**: parses incoming chat and plays sounds positionally from the sender — works on vanilla servers, no server install required.
- **Optional server component**: adds repo-config authority, spam control, and range filtering when installed server-side.
- **Same sound data as GMod**: lists and audio are downloaded on demand from the same community GitHub repositories (`PAC3-Server/chatsounds-valve-games` by default) via a multi-CDN fallback chain. The mod ships zero audio.

## Building

Requires JDK 21.

```sh
./gradlew :neoforge:build       # mod jar in neoforge/build/libs/
./gradlew :neoforge:runClient   # dev client
```

## Structure

- `common/` — loader-agnostic logic (parser, modifiers, data layer, audio DSP, completion) compiled against Mojang-mapped vanilla.
- `neoforge/` — NeoForge entrypoint, events, payloads, config (Kotlin for Forge).

## License

AGPL-3.0, same as neo-chatsounds. Sound content belongs to its respective owners and is downloaded client-side from user-configured repositories.
