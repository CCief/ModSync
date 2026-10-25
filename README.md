# ModSync

![Minecraft](https://img.shields.io/badge/Minecraft-26.3-green)
![Fabric Loader](https://img.shields.io/badge/Fabric_Loader-0.19.5-blue)
![License](https://img.shields.io/badge/License-MIT-yellow)

[English](README.md) | [简体中文](README_CN.md)

**Server-authoritative mod sync for Minecraft Fabric. Two mods: one for the server, one tiny helper for players.**

The server owns the mod list. When a player joins, the server sends its manifest (name + sha256 +
size), the player's helper works out what is missing, asks for it, receives the bytes **over the
game connection itself**, verifies them, installs them into `mods/` and restarts the game. Nobody
downloads anything by hand — not even self-made mods that exist nowhere but on your server.

> Works unchanged behind **frp / NAT / intranet penetration**: the transfer rides the Minecraft
> connection (`server-port=25565`), so no extra port, no public IP, no file hosting. A mod that
> only exists on your server travels exactly as well as one from Modrinth.

---

## The two mods

| Mod | Where | What it does |
|---|---|---|
| **`modsync`** | **server only** | Owns the authoritative list, detects the client's capabilities, sends the manifest, streams the files |
| **`modsync-client`** | client only | A tiny "courier": compares, requests, verifies, installs, restarts |

`modsync` is strictly server-side (`environment: "server"`) — it never runs on a player machine.
`modsync-client` contains no gameplay logic at all; it is the minimum needed to receive files,
write them to disk and restart the game.

If a player does **not** have `modsync-client`, the server falls back to telling them where to
get the pack (chat notice with your URL, or a kick) — the check is done through the channels the
client registered during Minecraft's configuration phase.

---

## How it works

```text
 player joins
     │
     ▼
 configuration phase ── channel negotiation ──►  did the client register modsync:probe?
     │
     ├── no ──►  no helper installed ──►  chat notice with download URL (or kick)
     │
     └── yes ──►  JOIN ──► server sends ManifestChunk × N   (gzip: name + sha256 + size)
                        │
                        ▼
              helper compares local mods/ by sha256
                        │
                        ├── in sync ──►  "you're up to date", nothing happens
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
                               moved into mods/ ──►  game restarts ──►  rejoin
```

**The restart is the tricky part.** ModSync reconstructs the launch command from the running JVM
(`java.class.path` + `sun.java.command` + JVM args) and starts a new process, then **waits to
confirm the new process is alive before quitting the current one**. If the new process dies
immediately, the game stays up and the player is told to restart manually — the game is never
lost.

---

## Installation

Requirements: **Minecraft 26.3**, **Fabric Loader ≥ 0.19.5**, **fabric-api** (any version).

**Server**
1. Put `modsync-<version>.jar` into the server's `mods/` folder.
2. Start once so `config/modsync.json` is generated, then fill in your download URL.
3. Restart.

**Players**
1. Put `modsync-client-<version>.jar` into `.minecraft/mods/` — **once**.
2. That is the whole setup. From now on the server keeps them in sync automatically.

---

## Configuration

Generated at `config/modsync.json` on first launch:

```json
{
  "serverOnly": [],
  "chunkSize": 30720,
  "checkUrl": "",
  "kickOnMissing": false,
  "kickMessage": "你的客户端缺少同步组件，无法自动获取服务器 mod。请安装整合包后重试。",
}
```

| Key | Default | Meaning |
|---|---|---|
| `serverOnly` | `[]` | Server-only mods that must **not** be sent to clients (Carpet, Lithium, …) |
| `chunkSize` | `30720` | Bytes per network chunk (30 KB) |
| `checkUrl` | `""` | Where players without the helper should download the pack |
| `kickOnMissing` | `false` | `true` = disconnect players without the helper; `false` = allow in and show a notice |
| `kickMessage` | *(Chinese text)* | Message shown when kicking (supports `§` color codes) |

---

## Building from source

```bash
git clone https://github.com/CCief/ModSync.git
cd ModSync
./gradlew build          # Windows: gradlew.bat build
```

Artifacts:
- `server/build/libs/modsync-<version>.jar`
- `client/build/libs/modsync-client-<version>.jar`

The project is a three-module Gradle build:

```
common/   protocol payloads shared by both sides (no fabric.mod.json)
server/   modsync          — server only
client/   modsync-client   — client only
```

Toolchain is pinned in `gradle.properties`: **Loom 1.17.19**, **JDK 25**, **Gradle 9.5**,
`minecraft_version=26.3`, `loader_version=0.19.5`, `fabric_version=0.163.0+26.3`.

---

## Changelog

### v0.3.0 — split into two mods, reliable restart

- Split into **`modsync` (server)** and **`modsync-client` (client)**. The server mod is strictly
  server-side; the client mod is a minimal courier with no gameplay code.
- **Fixed the restart.** v0.1.0 rebuilt the launch command without `-cp <java.class.path>`, so the
  new process could not find Minecraft and died instantly — the game just vanished. Now the
  classpath is included, the main class is parsed correctly, and the current process only exits
  **after** the new one is confirmed alive. If the new process fails, the game stays up and the
  player is told to restart manually.
- The client helper registers a **configuration-phase** probe channel (`modsync:probe`), so the
  server can tell during login that automatic delivery is possible.
- The server remembers the player's server address before restarting and tells them what to
  reconnect to (Minecraft 26.3 exposes no usable auto-connect API).
- Verified working end to end: manifest → request → chunked transfer → sha256 verification →
  install into `mods/` (v0.1.0 already proved this part; the restart was the missing piece).

### v0.2.0 — server-only probe (superseded)

- Server-only rewrite: detection via registered channels, chat notice or kick. Dropped the
  transfer — which contradicted the "server-only" premise, since receiving and installing files
  requires code on the client.

### v0.1.0 — initial release

- Two-sided auto-sync with a broken restart (see v0.3.0).

---

## Caveats

- The restart works by re-launching the same JVM command. It has been verified for the PCL
  launcher shape; if the new process cannot be started, the game is left running and the player
  is asked to restart manually.
- Automatic reconnection after the restart is not performed — Minecraft 26.3 exposes no usable
  API for it. The address is shown in chat so re-joining is one click.
- A mod that registers **no** network channel cannot be verified by the server's capability
  check. That does not affect the transfer itself: the authoritative list is the server's actual
  `mods/` directory, so every mod there is delivered regardless of channels.

---

## Roadmap

- [ ] Auto-reconnect after restart (needs a launcher-agnostic way to re-join)
- [ ] Per-registry detection via Fabric's registry sync
- [ ] Localized notice/kick messages from config
- [ ] Progress UI instead of chat messages
- [ ] Support for more Minecraft versions and other loaders

---

## License

[MIT](LICENSE) © 2026 CCief
