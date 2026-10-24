# ModSync

![Minecraft](https://img.shields.io/badge/Minecraft-26.3-green)
![Fabric Loader](https://img.shields.io/badge/Fabric_Loader-0.19.5-blue)
![Environment](https://img.shields.io/badge/Environment-server%20%2B%20client-orange)
![License](https://img.shields.io/badge/License-MIT-yellow)

[English](README.md) | [简体中文](README_CN.md)

**Server-authoritative mod synchronization for Minecraft Fabric.**

Drop a mod into the server's `mods/` folder and forget about it. When a player joins, ModSync
compares their mod set against the server's, pushes whatever is missing or outdated **straight
through the game connection**, installs it client-side, and restarts the game so they can rejoin —
no manual downloads, no shared drive, no third-party launcher integration.

> Works behind **frp / NAT / intranet penetration** out of the box. ModSync never opens a new
> port: it rides the existing Minecraft connection (`server-port=25565`). If players can join
> your server, they can receive mods. This also holds for **self-made / private mods** — they are
> transferred the same way, so nothing needs to be published anywhere.

---

## Features

- **One jar, both sides.** A single artifact declares `environment: "*"` and branches on
  `EnvType` at runtime — install the same file on server and client.
- **Content-addressed.** Every mod is identified by `sha256`, so a changed file is detected even
  if its name stayed the same.
- **Server-only exclusion.** Pure server mods (Carpet, Lithium, …) are listed in config and never
  pushed to clients.
- **Chunked transport.** Payloads are split into ~30 KB chunks, well under Minecraft's custom
  payload limit.
- **Integrity verified.** Each file is re-hashed after transfer; only a matching `sha256` is
  promoted from the staging folder into `mods/`.
- **Restart & rejoin.** After a successful sync the client restarts and reconnects
  automatically (best-effort; see [Caveats](#caveats)).
- **No-op when in sync.** If the client already matches, ModSync just lets them in — no transfer,
  no restart.

---

## How it works

Five custom payloads carry the whole protocol, all over the vanilla connection:

| # | Direction | Payload | Purpose |
|---|---|---|---|
| 1 | S2C | `ManifestChunk` | Gzipped manifest `[{name, sha256, size}]`, split into chunks |
| 2 | C2S | `ModRequest` | Gzipped JSON list of mods the client actually needs |
| 3 | S2C | `FileMeta` | Name, size and `sha256` of the file about to be sent |
| 4 | S2C | `FileChunk` | Raw file bytes, chunk `index` / `total` |
| 5 | S2C | `FileEnd` | End-of-file marker carrying the expected `sha256` |

```
 Player joins
     │
     ▼
 Server scans mods/  ──(minus serverOnly)──►  ManifestChunk × N
     │
     ▼
 Client compares against local mods/ by sha256
     │
     ├── in sync ──────────────►  let them in, nothing else happens
     │
     └── drift ──►  ModRequest (list of needed mods)
                        │
                        ▼
              FileMeta → FileChunk × N → FileEnd   (per mod)
                        │
                        ▼
              staged in .modsync_pending/ , sha256 verified
                        │
                        ▼
              moved into mods/  →  restart  →  auto-rejoin
```

> ModSync only **adds and updates**. It deliberately never deletes client mods that the server
> doesn't have, to avoid destroying someone's singleplayer setup.

---

## Installation

Requirements: **Minecraft 26.3**, **Fabric Loader ≥ 0.19.5**, **fabric-api** (any version).

1. **Server** — put `modsync-<version>.jar` into your server's `mods/` folder.
2. **Client** — every player puts the same jar into `.minecraft/mods/`, together with a matching
   `fabric-api` jar. This is the only manual step, and it happens once.
3. Start the server once so `config/modsync.json` is generated, then list your server-only mods
   (see [Configuration](#configuration)).
4. Restart the server. Players are aligned automatically from then on.

---

## Configuration

Generated at `config/modsync.json` on first launch:

```json
{
  "serverOnly": [],
  "chunkSize": 30720,
  "autoRestart": true
}
```

| Key | Default | Meaning |
|---|---|---|
| `serverOnly` | `[]` | File names of server-only mods that must **not** be pushed to clients |
| `chunkSize` | `30720` | Bytes per network chunk (30 KB) |
| `autoRestart` | `true` | Attempt to restart the client after a successful sync |

Example — keeping performance mods on the server only:

```json
{
  "serverOnly": ["fabric-carpet-1.21.1.jar", "lithium-fabric-mc1.21.1-0.14.1.jar"]
}
```

---

## Building from source

```bash
git clone https://github.com/CCief/ModSync.git
cd ModSync
./gradlew build          # Windows: gradlew.bat build
```

Artifact: `build/libs/modsync-<version>.jar`

Toolchain is pinned in `gradle.properties`: **Loom 1.17.19**, **JDK 25**, **Gradle 9.5**,
`minecraft_version=26.3`, `loader_version=0.19.5`, `fabric_version=0.163.0+26.3`.

---

## Project layout

```
src/main/java/modsync/
├─ ModSync.java      # entrypoint: registers payload codecs, branches on EnvType
├─ ModPayloads.java  # the five CustomPacketPayload records + codecs
├─ ServerSync.java   # server side: send manifest on join, serve requested files
├─ ClientSync.java   # client side: compare, request, stage, install
├─ Config.java       # config/modsync.json
├─ Util.java         # sha256 / gzip helpers
└─ Restart.java      # best-effort client self-restart
```

---

## Status

| Item | State |
|---|---|
| Source (7 files) | Done |
| Compile check (JDK 25, mojmap) | Passing |
| Runnable jar | `modsync-0.1.0.jar` |
| Real-world testing | In progress |

---

## Caveats

- The **first** player to join after a mod change pulls every changed mod through your tunnel.
  On a slow uplink this takes a while; later joins only transfer the delta.
- Auto-restart works with MultiMC / Prism / plain `java -jar`. With the **official launcher** the
  restart may not fire — the mods are already installed, so a manual restart is enough.

---

## Roadmap

- [ ] Transfer progress UI (currently chat messages)
- [ ] HTTP mirror mode for large packs (server sends a URL instead of bytes)
- [ ] Optional `pruneExtra` to remove client mods absent from the server
- [ ] Reliable restart for the official launcher
- [ ] Support for more Minecraft versions and other loaders

---

## License

[MIT](LICENSE) © 2026 CCief
