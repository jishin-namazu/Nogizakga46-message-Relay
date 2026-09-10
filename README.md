# Nogi Relay

[![GitHub](https://img.shields.io/badge/GitHub-jishin--namazu%2FNogizakga46--message--Relay-blue?logo=github)](https://github.com/jishin-namazu/Nogizakga46-message-Relay)

Nogi Relay 用于接收乃木坂46官方消息，归档文字和媒体，通过 Firebase Cloud Messaging（FCM）推送到 Android 客户端，并提供历史同步、搜索、分页、翻译和语音来电能力。

## 文档

| 文档 | 用途 |
| --- | --- |
| [DEVELOPMENT.md](DEVELOPMENT.md) | 代码结构、本地开发、API、数据库、Android 构建与安装 |
| [DEPLOYMENT.md](DEPLOYMENT.md) | 生产配置、Fly.io 部署、官网会话、推送验收与运维 |

## 系统概览

```text
乃木坂46官网
    -> Playwright monitor（会话、轮询、媒体归档）
    -> PostgreSQL + Nogi Relay API
    -> Firebase Cloud Messaging
    -> Android 客户端（SQLite、通知、全屏语音来电）
```

服务端在一个 Fly Machine 容器内运行两个 Node.js 进程：API 进程提供 REST API 和健康检查，monitor 进程维护官网会话、轮询订阅成员、提供媒体服务并触发推送。`server/start-all.sh` 先确认 API 健康，再启动 monitor。monitor 会为进程启动时的全部成员、运行中新发现的订阅成员以及会话文件更新后的全部成员导入 `past_messages`，并沿 timeline 的 `continuation` 游标拉取全部历史；已完成回填的成员在后续轮询中只读取最近 200 条。Android 客户端在启动、回到前台或手动同步时补齐服务器历史消息。

monitor 保留当前有效访问令牌，只在令牌临近过期或官网 API 返回 `401` 时让官网页面续期，并在真实 `401` 后只重试原请求一次。官网 `/v2/update_token` 返回 `400` 时立即进入 `signedOut`，关闭 Chromium 和消息轮询，等待上传新会话；`signedOut` 期间每 5 分钟输出一次会话更新提示。其他认证失败按配置阈值隔离；REST API、健康检查和会话上传仍保持可用。

生产容器先启动 API，等待本机 `/health` 返回 `200` 后才启动 monitor、媒体服务和 Chromium。Fly.io 在机器进入 `started` 时可能先记录一次端口探测失败；`grace_period` 用于避免这段启动窗口导致部署失败，不能抑制平台日志。

## 核心能力

- 使用 Playwright 浏览器会话维护官网登录状态，支持访问令牌自动续期和会话文件在线热更新。
- 启动、新订阅成员出现或会话文件更新时自动导入过去消息，并完整遍历 timeline continuation 分页。
- 支持文字、图片、语音和视频消息。
- 正式媒体归档到持久化卷；语音来电的背景图片保存为每条消息目录中的 `phone_image.<扩展名>`。
- FCM 高优先级数据推送，客户端按消息 ID 去重。
- 语音来电先完成音频下载，再显示来电通知和全屏来电页。
- Android 客户端支持成员会话、全量搜索、分页、媒体查看、Download 文件夹保存和可选的上下文感知翻译；点击普通消息通知会进入对应成员和分页，并把目标消息定位为列表顶部第一条，定位仅消费一次；翻译会保留人名、专有名词、代码以及原文的手动换行和空行结构。
- 测试消息和测试来电不写入正式消息日志；旧版本遗留测试数据会在服务端和客户端启动时清理。

## 目录

```text
app/                    Android 客户端
server/                 Node.js API、监控、媒体和数据库脚本
Dockerfile              Fly.io 镜像定义
fly.toml                Fly.io 进程、端口和持久卷配置
DEVELOPMENT.md          开发文档
DEPLOYMENT.md           部署文档
```

## 安全边界

Token、Firebase 服务账号、官网浏览器状态和模型供应商 API Key 都属于敏感数据，不提交到仓库。当前 API 使用共享 Bearer Token，适合私人部署；公开分发 APK 前应移除内置 Token，并改用设备级凭据。

## 许可证与使用范围

代码和服务端依赖的许可证说明见 [DEVELOPMENT.md](DEVELOPMENT.md) 与 [DEPLOYMENT.md](DEPLOYMENT.md) 末尾。乃木坂46官方图片、音频、视频和商标的再分发权不因本项目代码许可证自动获得，部署和使用须遵守相关服务条款与法律。
