# ModSync

![Minecraft](https://img.shields.io/badge/Minecraft-26.3-green)
![Fabric Loader](https://img.shields.io/badge/Fabric_Loader-0.19.5-blue)
![Environment](https://img.shields.io/badge/Environment-服务端%20%2B%20客户端-orange)
![License](https://img.shields.io/badge/License-MIT-yellow)

[English](README.md) | [简体中文](README_CN.md)

**面向 Minecraft Fabric 的「服务端权威」mod 同步方案。**

服主只管往服务端 `mods/` 里加 mod、换 mod，别的不用操心。玩家进服时 ModSync 自动比对双方的 mod
清单，把缺失或版本不一致的 mod **直接通过游戏连接**推过去，客户端自动安装、自动重启、自动重连。
玩家不需要手动下载任何东西，也不需要网盘、共享文件夹或第三方启动器配合。

> **内网穿透 / frp 场景开箱可用。** ModSync 不会新开任何端口，它走的就是 Minecraft 自己的那条连接
> （`server-port=25565`）。只要玩家能进你的服，就能收到 mod。
> **自制 mod、私有 mod 同样适用** —— 传输机制完全一致，不需要把 jar 发布到任何平台。

---

## 特性

- **一个 jar 两边通用。** 产物声明 `environment: "*"`，运行时按 `EnvType` 分流，服务端和客户端
  装同一个文件即可。
- **按内容校验。** 每个 mod 以 `sha256` 标识，即使文件名没变，内容改了也能检出。
- **排除仅服务端 mod。** Carpet、Lithium 这类纯服务端 mod 写进配置后不会被推给客户端。
- **分块传输。** 载荷切成约 30 KB 的小包，远低于 Minecraft 自定义包上限。
- **完整性校验。** 每个文件传输完成后重新计算 `sha256`，只有校验通过才从暂存目录移入 `mods/`。
- **自动重启重连。** 同步完成后客户端重启并自动重连（尽力而为，见[注意事项](#注意事项)）。
- **一致时不打扰。** 客户端本就一致时直接放行，不传文件、不重启。

---

## 工作原理

全部协议由 5 种自定义载荷构成，全部跑在原版连接上：

| # | 方向 | 载荷 | 作用 |
|---|---|---|---|
| 1 | S2C | `ManifestChunk` | gzip 后的清单 `[{name, sha256, size}]`，分块下发 |
| 2 | C2S | `ModRequest` | gzip 后的 JSON，客户端真正需要的 mod 名列表 |
| 3 | S2C | `FileMeta` | 即将发送的文件名、大小、`sha256` |
| 4 | S2C | `FileChunk` | 文件原始字节，带 `index` / `total` |
| 5 | S2C | `FileEnd` | 文件结束标记，附带预期 `sha256` |

```
 玩家进服
     │
     ▼
 服务端扫描 mods/ ──(排除 serverOnly)──►  ManifestChunk × N
     │
     ▼
 客户端按 sha256 与本地 mods/ 比对
     │
     ├── 一致 ────────────►  直接放行，什么都不做
     │
     └── 不一致 ──►  ModRequest（需要的 mod 列表）
                        │
                        ▼
              FileMeta → FileChunk × N → FileEnd   （逐个 mod）
                        │
                        ▼
              暂存于 .modsync_pending/ ，校验 sha256
                        │
                        ▼
              移入 mods/  →  重启游戏  →  自动重连
```

> ModSync 只做**新增与更新**，刻意**不会删除**客户端多出来的 mod —— 避免误删玩家的单机 mod。

---

## 安装

前置条件：**Minecraft 26.3**、**Fabric Loader ≥ 0.19.5**、**fabric-api**（任意版本）。

1. **服务端**：把 `modsync-<version>.jar` 放进服务端的 `mods/` 目录。
2. **客户端**：每位玩家把同一个 jar 放进 `.minecraft/mods/`，同时放一份匹配版本的 `fabric-api`。
   这是唯一需要手动操作的步骤，且只需做一次。
3. 启动一次服务端，让 `config/modsync.json` 生成，然后把仅服务端 mod 填进去
   （见[配置](#配置)）。
4. 重启服务端。此后玩家进服自动对齐。

---

## 配置

首次启动自动生成于 `config/modsync.json`：

```json
{
  "serverOnly": [],
  "chunkSize": 30720,
  "autoRestart": true
}
```

| 键 | 默认值 | 含义 |
|---|---|---|
| `serverOnly` | `[]` | **不推送给客户端**的仅服务端 mod 文件名 |
| `chunkSize` | `30720` | 单个网络包字节数（30 KB） |
| `autoRestart` | `true` | 同步完成后是否尝试自动重启客户端 |

示例 —— 把性能优化类 mod 留在服务端：

```json
{
  "serverOnly": ["fabric-carpet-1.21.1.jar", "lithium-fabric-mc1.21.1-0.14.1.jar"]
}
```

---

## 从源码构建

```bash
git clone https://github.com/CCief/ModSync.git
cd ModSync
./gradlew build          # Windows 用 gradlew.bat build
```

产物：`build/libs/modsync-<version>.jar`

工具链已固定在 `gradle.properties`：**Loom 1.17.19**、**JDK 25**、**Gradle 9.5**，
`minecraft_version=26.3`、`loader_version=0.19.5`、`fabric_version=0.163.0+26.3`。

---

## 项目结构

```
src/main/java/modsync/
├─ ModSync.java      # 入口：注册载荷编解码，按 EnvType 分流
├─ ModPayloads.java  # 5 个 CustomPacketPayload 记录及其编解码器
├─ ServerSync.java   # 服务端：进服时发清单，按请求发文件
├─ ClientSync.java   # 客户端：比对、请求、暂存、安装
├─ Config.java       # config/modsync.json
├─ Util.java         # sha256 / gzip 工具
└─ Restart.java      # 客户端自重启（尽力而为）
```

---

## 当前状态

| 项目 | 状态 |
|---|---|
| 源码（7 个文件） | 已完成 |
| 编译检查（JDK 25 + mojmap） | 通过 |
| 可运行 jar | `modsync-0.1.0.jar` |
| 实机联调 | 进行中 |

---

## 注意事项

- mod 有变动后，**第一个**进服的玩家会把全部变更的 mod 经隧道拉一遍，上行带宽吃紧时会比较慢；
  之后的玩家只传差异部分。
- 自动重启对 MultiMC / Prism / 手动 `java -jar` 有效；**官方启动器**可能不会自动重启 ——
  此时 mod 已经装好了，手动重启一次即可。

---

## 后续计划

- [ ] 传输进度条界面（目前是聊天栏文字提示）
- [ ] HTTP 外链模式（大整合包不走隧道，服务端只发下载地址）
- [ ] 可选 `pruneExtra`：清理客户端多余的 mod
- [ ] 官方启动器的可靠重启方案
- [ ] 支持更多 MC 版本与其他加载器

---

## 许可证

[MIT](LICENSE) © 2026 CCief
