# ModSync

![Minecraft](https://img.shields.io/badge/Minecraft-26.3-green)
![Fabric Loader](https://img.shields.io/badge/Fabric_Loader-0.19.5-blue)
![Environment](https://img.shields.io/badge/Environment-server--only-red)
![License](https://img.shields.io/badge/License-MIT-yellow)

[English](README.md) | [简体中文](README_CN.md)

**Server-side-only mod check for Minecraft Fabric. Players install nothing.**

ModSync lives on the server. When a player joins, the server inspects what the client's
mod set actually looks like and — if something required is missing — either shows them a
download link in chat or kicks them with an explanatory message. No client-side install, no
auto-transfer, no restarts.

> Works identically behind **frp / NAT / intranet penetration** and with **self-made mods**:
> what it points to is just a URL — a web page, a netdisk share, an HTTP file, anything.
> Nothing needs to be published to CurseForge or Modrinth.

---

## How it works

Minecraft's login has a **configuration phase** where client and server negotiate which
custom payload channels they understand. Fabric's networking layer exposes exactly that to
mods: `ServerConfigurationNetworking.getReceived(listener)` returns the set of channels the
client registered.

ModSync uses that as its probe:

- `fabric:registry/sync` is registered by every client that has **fabric-api** → tells the
  server "this is a Fabric client, not vanilla".
- Each mod that registers a channel of its own (e.g. `carpet:carpet`) shows up in the same
  set → the server can tell whether that specific mod is installed.

If the required set isn't satisfied, the server either kicks the player (with a message
listing what's missing) or lets them in and posts a chat notice with your download URL.

```text
 player joins
     │
     ▼
 configuration phase ── channel negotiation ──►  client's registered channels
     │
     ├─ fabric:registry/sync present?  ── no ──► missing "fabric-api"
     └─ each requiredChannels entry present? ── no ──► missing that channel
     │
     ├── all satisfied ──►  let them in
     │
     └── something missing ──►  kickOnMissing?
                                ├── true  ──►  disconnect (message lists what's missing)
                                └── false ──►  chat notice (+ checkUrl) right after spawn
```

### What it checks — and what it can't

The probe inspects **observable features of the client** (registered channels), not the client's
file system. A server-only mod has no way to enumerate a client's `mods/` folder: Fabric
provides no protocol that reports a client's mod list to the server, so a per-file `sha256`
diff is impossible without client-side code.

So the trade-off is explicit:

| | v0.2.0 (server-only) | v0.1.0 (two-sided) |
|---|---|---|
| Client install | none | ModSync jar required |
| Detection | channel / feature based | per-file `sha256` diff |
| Delivery | link in chat | bytes pushed over the game connection |
| Restart | player restarts manually | mod tries to auto-restart |
| Works with a vanilla client | yes (detected as "no fabric-api") | no |

If you need byte-exact enforcement, use v0.1.0 with the client jar — but then the client
needs a restart-capable launcher, and mods that register no channel can't be verified either
way.

---

## Installation

Requirements: **Minecraft 26.3**, **Fabric Loader ≥ 0.19.5**, **fabric-api** (any version).

1. Put `modsync-<version>.jar` into the **server's** `mods/` folder. That's it — the client
   side needs nothing.
2. Start the server once so `config/modsync.json` is generated.
3. Edit the config: set your download URL, and list the channels clients must have.
4. Restart the server.

---

## Configuration

Generated at `config/modsync.json` on first launch:

```json
{
  "checkUrl": "",
  "kickOnMissing": false,
  "kickMessage": "你的客户端缺少服务端要求的 mod，请先安装后再进入。详情见服务器公告。",
  "requireFabric": true,
  "requiredChannels": []
}
```

| Key | Default | Meaning |
|---|---|---|
| `checkUrl` | `""` | URL shown in the chat notice (modpack page, netdisk link, anything) |
| `kickOnMissing` | `false` | `true` = disconnect players missing required mods; `false` = allow in but show the notice |
| `kickMessage` | *(Chinese text)* | Message shown when kicking (supports `§` color codes) |
| `requireFabric` | `true` | Require a Fabric client with fabric-api (detected via `fabric:registry/sync`) |
| `requiredChannels` | `[]` | Channels the client must have registered; each missing one is reported as a missing mod |

Example — require Carpet and a fabric-api based mod:

```json
{
  "checkUrl": "https://your-site.example/mods",
  "kickOnMissing": true,
  "requireFabric": true,
  "requiredChannels": ["carpet:carpet", "yourmod:network"]
}
```

> A mod that registers **no** network channel cannot be detected by any server-side method.
> For those, keep them in the modpack and rely on the download link, or ship a tiny optional
> client component (future roadmap item).

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
├─ ModSync.java      # entrypoint
├─ ServerSync.java   # channel probe (CONFIGURE) + chat notice (JOIN) / kick
└─ Config.java       # config/modsync.json
```

Three source files. That's the whole mod.

---

## Changelog

### v0.2.0 — server-only rewrite

- **Breaking:** the mod no longer runs on clients at all (`environment: "server"`). Clients
  install nothing and need nothing.
- Replaced the v0.1.0 auto-transfer (manifest → request → chunked push → auto-restart) with a
  channel probe plus notice/kick. The transfer design required client-side code to receive
  and install files, which contradicts "server-only".
- Detection now uses the client's registered network channels
  (`ServerConfigurationNetworking.getReceived`) — `fabric:registry/sync` identifies Fabric
  clients, and `requiredChannels` entries identify individual mods.
- New config: `checkUrl`, `kickOnMissing`, `kickMessage`, `requireFabric`, `requiredChannels`.

### v0.1.0 — initial release (deprecated design)

- Two-sided auto-sync: the server pushed missing mods through the game connection and the
  client installed them and tried to restart. Works, but the auto-restart only succeeds on
  launchers that allow a child process to relaunch the game (MultiMC / Prism / plain
  `java -jar`); with launchers such as PCL the client exits without coming back.

---

## Roadmap

- [ ] Optional tiny client component for byte-exact `sha256` verification
- [ ] Per-registry detection via Fabric's registry sync (catches mods without channels)
- [ ] Fully localized notice/kick messages from config
- [ ] Support for more Minecraft versions and other loaders

---

## License

[MIT](LICENSE) © 2026 CCief
