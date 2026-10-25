# ModSync

![Minecraft](https://img.shields.io/badge/Minecraft-26.3-green)
![Fabric Loader](https://img.shields.io/badge/Fabric_Loader-0.19.5-blue)
![License](https://img.shields.io/badge/License-MIT-yellow)

[English](README.md) | [简体中文](README_CN.md)

**面向 Minecraft Fabric 的「服务端权威」mod 同步。拆成两个 mod：一个给服务端，一个极小助手给玩家。**

mod 清单由服务端说了算。玩家进服时，服务端下发权威清单（文件名 + sha256 + 大小），玩家的助手
算出缺什么、去要、**经游戏连接本身**把字节收下来、校验、装进 `mods/`、重启游戏。
从头到尾没人手动下载过任何东西 —— 连只存在于你服务器上的自制 mod 也一样。

> **内网穿透 / frp / NAT 完全照常**：传输走的就是 Minecraft 那条连接（`server-port=25565`），
> 不开新端口、不要公网 IP、不用挂网盘。只在你服务器上存在的 mod，和 Modrinth 上的走得一样顺。

---

## 两个 mod

| Mod | 装哪 | 干什么 |
|---|---|---|
| **`modsync`** | **仅服务端** | 持有权威清单、探测客户端能力、下发清单、推送文件 |
| **`modsync-client`** | 仅客户端 | 一个极小的"取件员"：比对、请求、校验、安装、重启 |

`modsync` 是**严格仅服务端**（`environment: "server"`），永远不会在玩家机器上运行。
`modsync-client` 里没有任何玩法逻辑，它就是"把文件收到、落盘、重启"所必需的最小集合。

玩家**没装** `modsync-client` 时，服务端会退化成"告诉他去哪下整合包"（聊天栏给链接，或直接踢出）——
这个判断靠的是 Minecraft 配置阶段协商出来的通道列表。

---

## 工作原理

```
 玩家进服
     │
     ▼
 配置阶段 ── 通道协商 ──►  客户端注册了 modsync:probe 吗？
     │
     ├── 没有 ──►  没装助手 ──►  聊天提示下载链接（或踢出）
     │
     └── 有 ──►  JOIN ──►  服务端下发 ManifestChunk × N   (gzip: 文件名+sha256+大小)
                        │
                        ▼
              助手按 sha256 比对本地 mods/
                        │
                        ├── 一致 ──►  提示"已是最新"，什么都不做
                        │
                        └── 不一致 ──►  ModRequest（需要的 mod 名单）
                                         │
                                         ▼
                               FileMeta → FileChunk × N → FileEnd   （逐个 mod）
                                         │
                                         ▼
                               暂存于 .modsync_pending/ ，sha256 校验
                                         │
                                         ▼
                               移入 mods/ ──►  游戏重启 ──►  重新进服
```

**重启是最难的一环。** ModSync 从运行中的 JVM 里还原出启动命令（`java.class.path` +
`sun.java.command` + JVM 参数）拉起新进程，并且**先确认新进程活着，才退出当前进程**。
万一新进程一起来就死，游戏会留在原地、提示玩家手动重启 —— 游戏绝对不会凭空消失。

---

## 安装

前置条件：**Minecraft 26.3**、**Fabric Loader ≥ 0.19.5**、**fabric-api**（任意版本）。

**服务端**
1. 把 `modsync-<version>.jar` 放进服务端 `mods/`。
2. 启动一次生成 `config/modsync.json`，填上你的整合包下载地址。
3. 重启。

**玩家**
1. 把 `modsync-client-<version>.jar` 放进 `.minecraft/mods/` —— **就这一次**。
2. 完事。之后服务端会一直把你对齐，不用再管。

---

## 配置

首次启动自动生成于 `config/modsync.json`：

```json
{
  "serverOnly": [],
  "chunkSize": 30720,
  "checkUrl": "",
  "kickOnMissing": false,
  "kickMessage": "你的客户端缺少同步组件，无法自动获取服务器 mod。请安装整合包后重试。",
  "requireFabric": true,
  "requiredChannels": []
}
```

| 键 | 默认值 | 含义 |
|---|---|---|
| `serverOnly` | `[]` | **不下发**给客户端的仅服务端 mod（Carpet、Lithium 等） |
| `chunkSize` | `30720` | 单个网络包字节数（30 KB） |
| `checkUrl` | `""` | 没装助手的玩家去哪里下载整合包 |
| `kickOnMissing` | `false` | `true` = 踢出没装助手的玩家；`false` = 放行并提示 |
| `kickMessage` | *(中文文案)* | 踢出时显示的文案（支持 `§` 颜色码） |
| `requireFabric` | `true` | 要求客户端是装了 fabric-api 的 Fabric 客户端（依据 `fabric:registry/sync`） |
| `requiredChannels` | `[]` | 额外要求客户端注册的通道，缺一个即视为少对应 mod |

---

## 从源码构建

```bash
git clone https://github.com/CCief/ModSync.git
cd ModSync
./gradlew build          # Windows 用 gradlew.bat build
```

产物：
- `server/build/libs/modsync-<version>.jar`
- `client/build/libs/modsync-client-<version>.jar`

工程是三模块 Gradle 结构：

```
common/   两端共用的协议载荷（不含 fabric.mod.json）
server/   modsync          —— 仅服务端
client/   modsync-client   —— 仅客户端
```

工具链已固定在 `gradle.properties`：**Loom 1.17.19**、**JDK 25**、**Gradle 9.5**，
`minecraft_version=26.3`、`loader_version=0.19.5`、`fabric_version=0.163.0+26.3`。

---

## 更新日志

### v0.3.0 —— 拆成两个 mod + 修好重启

- 拆成 **`modsync`（服务端）** 与 **`modsync-client`（客户端）**。服务端那个严格仅服务端；
  客户端那个是没有任何玩法代码的最小取件员。
- **修好了重启。** v0.1.0 还原启动命令时**漏了 `-cp <java.class.path>`**，新进程因此找不到
  Minecraft、起来就死 —— 表现就是"游戏突然没了"。现在补上了 classpath、正确解析主类，并且
  **只有在新进程确认存活后**才退出当前进程。新进程起不来时游戏会留在原地，并提示手动重启。
- 客户端助手在**配置阶段**注册探针通道 `modsync:probe`，服务端在登录期就能判断"这台客户端
  支持自动下发"。
- 重启前会记下玩家连的服务器地址，重启后提示重新连回（Minecraft 26.3 没有可用的自动重连 API）。
- 端到端已验证可用：清单 → 请求 → 分块传输 → sha256 校验 → 装入 `mods/`
  （这部分 v0.1.0 就已经跑通，缺的只是重启）。

### v0.2.0 —— 仅服务端探测（已被取代）

- 仅服务端重写：按通道检测、聊天提示或踢出。丢掉了传输功能 —— 因为接收与安装文件必须有
  客户端代码，与"仅服务端"的前提自相矛盾。

### v0.1.0 —— 初版

- 双端自动同步，但重启是坏的（见 v0.3.0）。

---

## 注意事项

- 重启的原理是"用同样的 JVM 命令再拉一个进程"。PCL 这种启动器形态已验证可用；万一新进程
  起不来，游戏会保持运行并提示玩家手动重启。
- 重启后**不会自动重连** —— Minecraft 26.3 没有可用的自动 connect API。地址会在聊天栏显示，
  再点一次服务器列表即可。
- **不注册任何网络通道的 mod，服务端的能力检测查不到它。** 但这不影响传输本身：权威清单就是
  服务端 `mods/` 目录的真实内容，里面的每个 mod 都会被送达，与通道无关。

---

## 后续计划

- [ ] 重启后自动重连（需要一种与启动器无关的重连方式）
- [ ] 利用 Fabric 的 registry sync 做按 registry 的检测
- [ ] 提示/踢出文案完全可配置、可本地化
- [ ] 传输进度条 UI（目前是聊天栏文字）
- [ ] 支持更多 MC 版本与其他加载器

---

## 许可证

[MIT](LICENSE) © 2026 CCief
