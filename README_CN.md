# ModSync

![Minecraft](https://img.shields.io/badge/Minecraft-26.3-green)
![Fabric Loader](https://img.shields.io/badge/Fabric_Loader-0.19.5-blue)
![Environment](https://img.shields.io/badge/Environment-仅服务端-red)
![License](https://img.shields.io/badge/License-MIT-yellow)

[English](README.md) | [简体中文](README_CN.md)

**面向 Minecraft Fabric 的「仅服务端」mod 校验器。玩家什么都不用装。**

ModSync 只装在服务端。玩家进服时，服务端会检查客户端的 mod 状况 —— 如果缺少必需的东西，
要么在聊天栏发一条下载链接提示，要么直接把玩家踢出并说明缺什么。客户端零安装、零自动传文件、
零自动重启。

> **内网穿透 / frp / 自制 mod 全部照常可用**：它给的只是一个普通 URL —— 网页、网盘、HTTP 文件
> 都行，不需要把 mod 发布到 CurseForge 或 Modrinth。

---

## 工作原理

Minecraft 登录时会进入一个**配置阶段（configuration phase）**，客户端和服务端在这里协商
各自支持哪些自定义网络通道。Fabric 的网络层把这个过程暴露给了 mod：
`ServerConfigurationNetworking.getReceived(listener)` 拿到的就是**客户端注册过的通道集合**。

ModSync 用它当探针：

- `fabric:registry/sync` —— 只要客户端装了 **fabric-api** 就会注册它 → 服务端据此判断
  「这是 Fabric 客户端，不是原版」。
- 每个注册了自己通道的 mod（比如 `carpet:carpet`）也会出现在同一集合里 → 服务端能精确
  判断这个 mod 装没装。

必需项不满足时，服务端按配置踢出（并在提示里列出缺什么）或放行后发聊天提示。

```
 玩家进服
     │
     ▼
 配置阶段 ── 通道协商 ──►  客户端注册了哪些通道
     │
     ├─ 有 fabric:registry/sync 吗？ ── 没有 ──► 缺 "fabric-api"
     └─ requiredChannels 每项都在吗？ ── 缺 ──► 记下缺哪个通道
     │
     ├── 全部满足 ──►  放行
     │
     └── 有缺失 ──►  kickOnMissing?
                      ├── true  ──►  踢出（消息里列出缺失项）
                      └── false ──►  进世界后发聊天提示（带 checkUrl）
```

### 它能查什么、查不了什么

探针看的是**客户端的可观测特征**（注册了哪些通道），而不是客户端的文件系统。仅服务端的 mod
**没有办法枚举客户端的 `mods/` 目录** —— Fabric 没有提供任何把客户端 mod 清单上报给服务端的
协议，所以逐文件 `sha256` 比对在没有客户端代码配合时根本做不到。

于是取舍是明摆着的：

| | v0.2.0（仅服务端） | v0.1.0（双端） |
|---|---|---|
| 客户端安装 | 什么都不用装 | 需要 ModSync jar |
| 检测方式 | 通道 / 特征检测 | 逐文件 `sha256` 比对 |
| 交付方式 | 聊天栏给链接 | 经游戏连接推字节 |
| 重启 | 玩家自己重启 | mod 尝试自动重启 |
| 原版客户端 | 能识别（判为「没 fabric-api」） | 不能 |

如果你需要字节级强校验，就用 v0.1.0 + 客户端 jar —— 但那样客户端得配一个支持子进程重启游戏的
启动器（MultiMC / Prism / 手动 `java -jar` 有效；PCL 这类启动器会让客户端退出后拉不起来）。

---

## 安装

前置条件：**Minecraft 26.3**、**Fabric Loader ≥ 0.19.5**、**fabric-api**（任意版本）。

1. 把 `modsync-<version>.jar` 放进**服务端的** `mods/` 目录。完事 —— 客户端啥也不用装。
2. 启动一次服务端，让 `config/modsync.json` 生成。
3. 编辑配置：填下载地址，列出客户端必须具备的通道。
4. 重启服务端。

---

## 配置

首次启动自动生成于 `config/modsync.json`：

```json
{
  "checkUrl": "",
  "kickOnMissing": false,
  "kickMessage": "你的客户端缺少服务端要求的 mod，请先安装后再进入。详情见服务器公告。",
  "requireFabric": true,
  "requiredChannels": []
}
```

| 键 | 默认值 | 含义 |
|---|---|---|
| `checkUrl` | `""` | 聊天提示里给的下载地址（整合包页面、网盘链接，任意 URL） |
| `kickOnMissing` | `false` | `true` = 踢出缺 mod 的玩家；`false` = 放行但发提示 |
| `kickMessage` | *(中文文案)* | 踢出时显示的文案（支持 `§` 颜色码） |
| `requireFabric` | `true` | 要求客户端是装了 fabric-api 的 Fabric 客户端（依据 `fabric:registry/sync`） |
| `requiredChannels` | `[]` | 客户端必须注册的通道清单，缺一个就算少一个 mod |

示例 —— 要求 Carpet 和某个基于 fabric-api 的 mod：

```json
{
  "checkUrl": "https://your-site.example/mods",
  "kickOnMissing": true,
  "requireFabric": true,
  "requiredChannels": ["carpet:carpet", "yourmod:network"]
}
```

> **不注册任何网络通道的 mod，任何服务端方法都检测不出来。** 这类 mod 请放进整合包、靠下载
> 链接解决；或者后续做一个可选的极简客户端组件（见路线图）。

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
├─ ModSync.java      # 入口
├─ ServerSync.java   # 通道探针（CONFIGURE）+ 聊天提示（JOIN）/ 踢出
└─ Config.java       # config/modsync.json
```

三个源文件，整个 mod 就这么多。

---

## 更新日志

### v0.2.0 —— 仅服务端重写

- **破坏性变更**：mod 完全不在客户端运行（`environment: "server"`），玩家什么都不用装。
- 用「通道探针 + 提示/踢出」替换了 v0.1.0 的自动传输（清单 → 请求 → 分块推送 → 自动重启）。
  原设计需要客户端代码接收并落盘，这与「仅服务端」相矛盾。
- 检测改为读客户端注册的网络通道（`ServerConfigurationNetworking.getReceived`）：
  `fabric:registry/sync` 识别 Fabric 客户端，`requiredChannels` 里的每一项识别具体 mod。
- 新配置：`checkUrl`、`kickOnMissing`、`kickMessage`、`requireFabric`、`requiredChannels`。

### v0.1.0 —— 初版（已废弃的设计）

- 双端自动同步：服务端经游戏连接推送缺失的 mod，客户端安装后尝试自动重启。功能是通的，
  但自动重启只在允许子进程重新拉起游戏的启动器上成功（MultiMC / Prism / 手动 `java -jar`）；
  在 PCL 这类启动器下客户端会退出而拉不回来。

---

## 后续计划

- [ ] 可选的极简客户端组件，实现字节级 `sha256` 校验
- [ ] 利用 Fabric 的 registry sync 做按 registry 的检测（能抓到不注册通道的 mod）
- [ ] 提示/踢出文案完全可配置、可本地化
- [ ] 支持更多 MC 版本与其他加载器

---

## 许可证

[MIT](LICENSE) © 2026 CCief
