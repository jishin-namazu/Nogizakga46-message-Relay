# Nogi Relay 开发文档

本文件面向需要阅读、修改和本地运行代码的开发者，覆盖系统结构、Android 客户端、Node.js 服务端、API、数据库、构建、安装和开发排障。

生产环境变量、Fly.io 部署、官网会话上传、推送验收和运维流程请查看 [DEPLOYMENT.md](DEPLOYMENT.md)。根目录 [README.md](README.md) 只保留项目入口和文档索引。
## 核心功能

### 服务器端

- 使用浏览器会话登录乃木坂46官网并获取短期访问令牌。
- 保留当前有效访问令牌；临近过期时由官网页面预刷新，API 返回 401 时刷新并只重试一次。
- 自动读取当前账号所有处于订阅状态的成员。
- 按成员轮询时间线并识别新消息。
- 支持文字、图片、语音和视频消息。
- 通过消息 ID 去重并写入 PostgreSQL。
- PostgreSQL 瞬时连接失败会指数退避重试；monitor 不会提前确认失败消息，下一轮会重新处理。
- 错误会保留结构化上下文、堆栈和进程信息，并在可用时写入 `error_logs`。
- 把图片、语音、视频和缩略图归档到持久化存储。
- 通过受 Bearer Token 保护的地址向客户端提供媒体文件。
- 通过 FCM 高优先级数据消息推送到已注册设备。
- 保存正式消息的推送结果日志。
- 提供不写入服务器数据库的普通测试消息接口。

### Android 客户端

- 接收 FCM 数据消息并写入本机 SQLite 数据库。
- 启动或回到前台时自动同步服务器历史消息。
- 冷启动或系统重建 Activity 时先显示与官方 Android App 一致的白底、居中乃木坂 46 启动过渡页，Compose 首帧绘制后切换到主界面。
- 主界面内容区和底部导航栏使用同一纯白背景；启动图资源按 Android 版本分别适配。
- 按成员显示消息会话。
- 每页显示 20 条成员消息。
- 支持上一页、下一页以及输入指定页码跳转。
- 翻页后自动回到消息列表顶部。
- 搜索当前成员的全部本地消息，而不仅是当前页。
- 搜索范围包括原文、翻译、成员名、时间、消息类型和中文类型名称。
- 查看图片和视频；图片支持双指缩放、拖动和双击缩放。
- 播放、暂停和拖动语音进度。
- 明确点击下载后，把图片、视频或语音保存到系统 `Download` 文件夹。
- 点击普通消息通知后，进入对应成员和分页，并把目标消息定位为列表顶部第一条；该通知目标仅消费一次。
- 语音消息可显示全屏来电界面。
- 语音播放支持扬声器/听筒切换，连接耳机时可强制使用扬声器。
- 语音播放时使用距离传感器自动熄屏（听筒模式且无外接音频设备时）。
- 播放语音时退出消息列表后重新进入，自动定位到播放消息所在页和位置。
- 可在客户端使用所选模型供应商的 API 将日文消息翻译为简体中文。

## 系统架构

```text
乃木坂46官方网站
        │
        │ 官网页面维护登录状态和刷新令牌
        ▼
Playwright 监控进程（monitor）
        │
        ├── 获取当前有效订阅成员
        ├── 每 60 秒轮询成员时间线
        ├── 归一化并去重新消息
        ├── 下载媒体到持久化卷
        └── 保存消息到 PostgreSQL
                    │
                    ├── REST API（app）
                    │       └── Android 前台历史同步
                    │
                    └── Firebase Admin SDK
                            │
                            ▼
                           FCM
                            │
                            ▼
                    Android 客户端
                    ├── SQLite 本地消息库
                    ├── 系统通知
                    ├── 全屏语音来电
                    ├── 媒体缓存/下载
                    └── 可选多供应商模型翻译
```

### 新消息处理流程

1. `monitor` 调用官网 `/v2/groups` 获取当前账号的开放且有效订阅成员。
2. 进程首次轮询、运行中新发现订阅成员，或浏览器会话文件成功激活后，对需要回填的成员请求 `/v2/groups/{groupId}/past_messages`。
3. 回填时间线的首次请求使用 `count=200&order=desc&clear_unread=false`；响应包含 `continuation` 时，将游标 URL 编码后继续请求，直到服务端不再返回游标。
4. `past_messages` 和所有 timeline 页面按消息 ID 合并去重。
5. 把官网消息归一化为内部消息结构。
6. 服务器根据 PostgreSQL 中的消息 ID 再次去重。
7. 非文字消息会先尝试归档媒体文件。
8. 新消息写入 PostgreSQL；默认启动历史回填不发送 FCM。
9. 成员全量同步完成后，每轮只读取最近 200 条，并把真正的新消息推送到所有已注册设备。
10. Android 客户端解析 FCM payload；payload 不完整时再向服务器请求消息详情。
11. 客户端写入本机 SQLite，然后显示普通通知或全屏语音来电。
12. 客户端启动、回到前台或手动同步时，会分页拉取服务器历史消息，补齐可能漏掉的推送。

任何包含历史回填的轮询都没有 120 秒的轮询总时限，避免成员和分页较多时被截断；每个官网 API 请求仍使用独立超时和现有的 401 刷新重试。若服务端重复返回同一个 continuation，monitor 会中止本次同步并记录错误，避免无限循环；未成功的成员在后续轮询中会重新尝试，数据库消息主键保证重复导入安全。

## 项目目录

```text
nogizaka46msg/
├── app/                              Android 客户端
│   ├── build.gradle.kts              Android 构建配置与版本号
│   ├── google-services.json          本机 Firebase 客户端配置，不提交
│   └── src/main/
│       ├── AndroidManifest.xml        权限、Activity、Service 和 Receiver
│       ├── java/com/nogirelay/app/
│       │   ├── MainActivity.kt        主界面、同步、成员消息、搜索和分页
│       │   ├── call/                  全屏来电通知与界面
│       │   ├── data/                  设置、本地数据库和服务器客户端
│       │   ├── media/                 下载与语音播放
│       │   ├── notification/          Android 通知通道
│       │   ├── push/                  FCM 接收和设备注册
│       │   ├── translation/           多供应商模型翻译
│       │   └── ui/                    主题、远程图片、媒体查看器
│       └── res/                       启动图标、来电图片、铃声和启动过渡页资源
├── server/                           Node.js 服务端
│   ├── src/
│   │   ├── index.js                  API 服务入口
│   │   ├── db/                       PostgreSQL 连接封装
│   │   ├── middleware/               Bearer Token、日志与错误处理
│   │   ├── routes/                   devices/messages/push 路由
│   │   ├── services/                 消息、设备、媒体和 FCM 业务逻辑
│   │   └── monitor/                  官网监控与媒体服务
│   ├── database/schema.sql           当前数据库初始化脚本
│   ├── db/schema.sql                 早期遗留 schema，仅供参考
│   ├── .env.example                  本地环境变量模板
│   ├── firebase-admin-key.json       本机 Firebase 服务端密钥，不提交
│   └── nogi-browser-state.json       官网登录会话，不提交
├── Dockerfile                        Fly.io 容器镜像
├── fly.toml                          Fly.io 应用、进程、端口和卷配置
├── build.gradle.kts                  Android 顶层插件版本
├── settings.gradle.kts               Gradle 仓库和模块定义
└── README.md                         本文档
```

## 运行条件

### Android 构建

- Windows、macOS 或 Linux。
- JDK 17。
- Android SDK 34。
- 可选：Android Studio。
- 真机安装需要 Android Platform Tools（ADB）。

### 服务器

- Node.js 18 或更高版本，建议 Node.js 20。
- PostgreSQL。
- Firebase 项目和 Firebase Admin 服务账号。
- Playwright 及 Chromium，或者本机 Microsoft Edge。
- 有效的乃木坂46消息服务账号和订阅。
- Fly.io 部署时需要 `flyctl`。

## 凭据和敏感文件

以下内容必须保密：

- `ACCESS_TOKEN`：Nogi Relay API 的共享 Bearer Token。
- Firebase Admin 服务账号 JSON。
- `FIREBASE_PRIVATE_KEY_BASE64` 或 `FIREBASE_PRIVATE_KEY_JSON`。
- `nogi-browser-state.json`：包含乃木坂46官网 cookies、localStorage 和 IndexedDB 会话。
- `NOGI_ACCESS_TOKEN`、`NOGI_REFRESH_TOKEN` 和 `NOGI_AUTH_TKN`。
- Android 客户端中填写的模型供应商 API Key。

项目的 `.gitignore` 已忽略主要敏感文件，但提交前仍应检查：

```powershell
git status --short
```

不要在 README、脚本、聊天记录或截图中粘贴真实 Token。本文档中的 `YOUR_ACCESS_TOKEN`、`YOUR_DEVICE_ID`、`MONITOR_MACHINE_ID` 等都必须在本机替换。

### 客户端配置注意事项

`ApiConfig.kt` 只包含默认服务器地址，访问令牌默认为空。开发者可以在客户端设置页输入令牌；如果仅在本机调试包中注入默认值，可在未提交的 `local.properties` 中加入 `relay.access.token=YOUR_ACCESS_TOKEN`。不要把真实令牌写入 Kotlin 源码或提交记录。

设置页面中的访问令牌和模型供应商 API Key 当前保存在 Android `SharedPreferences` 中，没有额外加密。请仅在可信设备上使用。

## 开发配置说明

本地服务使用 server/.env，最小配置和启动步骤见本地启动服务器章节。完整变量表以及生产覆盖值见 [DEPLOYMENT.md](DEPLOYMENT.md)。

## 本地启动服务器

### 安装依赖

```powershell
Set-Location .\server
npm ci
```

`npm ci` 按 `package-lock.json` 安装完全一致的依赖；只有在主动修改依赖并更新锁文件时才使用 `npm install`。

### 创建本地配置

复制 `server/.env.example` 为 `server/.env`，然后填写真实值。不要提交 `.env`。

```powershell
Copy-Item .\.env.example .\.env
```

最小 API 配置示例：

```dotenv
NODE_ENV=development
PORT=3000
DATABASE_URL=postgresql://postgres:password@localhost:5432/nogi_relay
ACCESS_TOKEN=YOUR_RANDOM_ACCESS_TOKEN
FIREBASE_PROJECT_ID=YOUR_FIREBASE_PROJECT_ID
FIREBASE_PRIVATE_KEY_PATH=./firebase-admin-key.json
```

可用 PowerShell 生成随机 API Token：

```powershell
[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLower()
```

### 初始化数据库

推荐直接执行当前 schema：

```powershell
psql $env:DATABASE_URL -f .\database\schema.sql
```

也可以在 API 启动后调用受认证保护的幂等初始化接口：

```powershell
$token = Read-Host 'ACCESS_TOKEN'; Invoke-RestMethod -Method Post -Uri 'http://localhost:3000/init-db' -Headers @{ Authorization = "Bearer $token" }
```

`server/db/schema.sql` 是早期版本，字段约束和当前代码存在差异；新环境应使用 `server/database/schema.sql` 或 `/init-db`。

### 启动 API

```powershell
npm start
```

开发时自动重启：

```powershell
npm run dev
```

默认监听 `http://localhost:3000`。

### 创建官网浏览器会话

在 `server` 目录中执行：

```powershell
$env:NOGI_BROWSER_EXECUTABLE_PATH = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
$env:NOGI_BROWSER_STATE_FILE = '.\nogi-browser-state.json'
npm run bootstrap:browser
```

在弹出的官网窗口中完成登录，确认能看到消息，然后回到终端按回车。脚本检测到官网授权请求后才会保存会话。

### 启动监控

```powershell
npm run monitor
```

monitor 进程会同时启动 8081 端口的受保护媒体服务。

### 官网访问令牌自动续期

刷新令牌始终由官网页面中的 TokenManager 管理；官网当前会在访问令牌接近过期（约 10 秒内）或 API 返回 401 时调用 `/v2/update_token`。monitor 不再定时清空有效 token 或仅凭页面未发请求判定失效；预刷新失败时仍尝试当前 token，真实 401 只触发一次新 token 刷新和原请求重试。

当 `/v2/update_token` 返回 HTTP `400` 时，monitor 立即进入 `signedOut`，并同时进入 `authPaused`：关闭 Chromium、停止官网认证请求和消息轮询、删除失效 access-token 缓存，但保留健康检查、管理接口、媒体服务和会话文件监听。`signedOut` 期间立即输出一次退出告警，之后每 5 分钟输出 `[NOGI_SESSION_UPDATE_REQUIRED]`，直到新会话通过官网 API 验证。其他 `/v2/update_token` 4xx/5xx 按连续失败次数累计，达到阈值后进入 `authPaused`；成功响应会清零计数。

### 服务启动时序

容器由 `server/start-all.sh` 编排两个服务阶段：

1. 后台启动 `npm start`，API 完成 Firebase 初始化、数据库兼容迁移和清理任务后监听 `PORT`。
2. 启动脚本轮询 `http://127.0.0.1:${PORT}/health`；未返回成功时不会启动 monitor。
3. `/health` 成功后前台启动 `npm run monitor`，由 monitor 再启动 8081 媒体服务和 Chromium。

Fly.io 的端口检查由平台在机器进入 `started` 后独立发起，可能早于应用监听端口并记录瞬时失败。`fly.toml` 中 API、媒体 TCP 检查和 API HTTP 检查均设置了 30 秒 `grace_period`；这能避免启动窗口造成部署失败，但不会隐藏平台产生的首次探测日志。

**配置项:**
- `NOGI_BROWSER_RESTART_INTERVAL_SECONDS`: 浏览器进程重启间隔(默认 1800 秒)
- `NOGI_MAX_TOKEN_REFRESH_FAILURES`: `/v2/update_token` 非 400 失败进入认证暂停前允许的连续次数（默认 3）
- `NOGI_MAX_AUTH_FAILURES`: 兼容旧配置名；未设置前者时作为回退值

**会话失效信号:**
- 日志中出现 `[NOGI_AUTH_SIGNED_OUT]` 或 `/v2/update_token 返回 400`
- `signedOut` 期间每 5 分钟出现 `[NOGI_SESSION_UPDATE_REQUIRED]`
- 日志中出现 `Nogi API 401: Unauthorized`（表示业务请求触发了刷新路径）
- monitor 停止获取新消息 (`fetched=0`)
- 此时需要重新生成并上传浏览器会话文件

## API 认证

除 `/health` 和 monitor 媒体服务自己的 `/health` 外，项目 API 都需要：

```http
Authorization: Bearer YOUR_ACCESS_TOKEN
```

认证中间件把 Bearer Token 与服务器环境变量 `ACCESS_TOKEN` 做固定字符串比较，不是 JWT，也没有用户身份解析。因此：

- `req.user` 当前不会被设置。
- 新注册设备的 `user_id` 通常为 `NULL`。
- 该认证方式本质上是单租户共享 Token。
- 如果未来对外提供服务，应改用设备级或用户级认证。

### 验证 Relay API Token

```powershell
$token = Read-Host 'ACCESS_TOKEN'; try { Invoke-RestMethod -Uri 'https://nogi-relay.fly.dev/v1/devices' -Headers @{ Authorization = "Bearer $token" } | ConvertTo-Json -Depth 6 } catch { $_.Exception.Response.StatusCode.value__ }
```

- 返回设备 JSON：Token 有效。
- 返回 `401`：Token 缺失或无效。
- 返回 `429`：15 分钟内请求超过限制。

所有 `/v1/*` 接口按来源 IP 限制为每 15 分钟最多 100 次请求。

## API 接口

基础地址：`https://nogi-relay.fly.dev`

| 方法 | 路径 | 认证 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/health` | 否 | API 健康检查 |
| `POST` | `/init-db` | 是 | 幂等创建/补齐数据库结构 |
| `POST` | `/v1/devices` | 是 | 注册或更新 FCM 设备 |
| `GET` | `/v1/devices` | 是 | 获取设备列表，不返回 FCM Token |
| `DELETE` | `/v1/devices/:id` | 是 | 删除设备 |
| `GET` | `/v1/messages` | 是 | 分页获取消息 |
| `GET` | `/v1/messages/:id` | 是 | 获取消息详情 |
| `GET` | `/v1/messages/:id/media/:kind` | 是 | 读取归档媒体，`kind` 为 `media`、`thumbnail` 或 `phone_image` |
| `PATCH` | `/v1/messages/:id/played` | 是 | 标记语音已播放 |
| `GET` | `/v1/messages/stats/summary` | 是 | 获取消息数量统计 |
| `POST` | `/v1/push/send` | 是 | 重新推送数据库中的指定消息 |
| `POST` | `/v1/push/test-message` | 是 | 发送不落服务器数据库的普通测试消息 |
| `POST` | `/v1/push/test-call` | 是 | 发送不落服务器数据库的测试语音来电 |
| `GET` | `/v1/push/logs` | 是 | 查询正式推送日志 |
| `POST` | `/v1/admin/browser-session` | 是 | 原子上传浏览器会话，返回待激活请求 ID |
| `GET` | `/v1/admin/browser-session/status` | 是 | 查询最新上传请求的激活与验证状态 |

### 浏览器会话热更新协议

`POST /v1/admin/browser-session` 只确认会话文件已经完整、原子地写入持久卷。新上传通常返回 HTTP `202`，其中 `accepted=true`、`activated=false`、`activationStatus="pending"`。这不代表官网认证已经可用。

monitor 监听会话文件所在目录，同时处理原子替换产生的 `rename` 和普通 `change` 事件。它读取一次完整快照并直接用该快照建立新浏览器上下文，不会在关闭旧上下文后再次从磁盘读取。随后 monitor 会打开官网，并以一次不自动重试的官网 API 请求验证捕获到的访问令牌：

- `activating`：monitor 正在加载并验证该请求。
- `active`：新浏览器上下文已经加载，官网 API 验证成功。
- `failed`：加载或官网 API 验证失败；`activationError` 包含原因。
- `pending`：文件已接受，但 monitor 尚未开始处理，或状态文件仍指向上一请求。

上传和激活通过随机 `requestId` 关联，通过 SHA-256 `version` 标识会话文件内容。`GET /v1/admin/browser-session/status` 的关键字段如下：

| 字段 | 说明 |
| --- | --- |
| `requestId` | 最新上传请求 ID |
| `uploadedVersion` | 最新上传文件的内容版本 |
| `activationStatus` | `pending`、`activating`、`active`、`failed` 或 `unknown` |
| `activated` | 仅当当前请求已经验证并激活时为 `true` |
| `activeRequestId` / `activeVersion` | 当前成功激活的请求与内容版本 |
| `activationUpdatedAt` | monitor 最近更新时间 |
| `activationError` | 当前请求失败时的错误信息 |

`server/upload-session.js` 会在上传后轮询该状态，最多等待 90 秒；只有对应 `requestId` 进入 `active` 才以成功状态退出。

### 注册设备

```http
POST /v1/devices
Content-Type: application/json

{
  "token": "FCM_DEVICE_TOKEN",
  "platform": "android",
  "label": "OPPO PKU110"
}
```

`platform` 只接受 `android` 或 `ios`。相同 FCM Token 再次注册时会更新设备信息和 `last_seen_at`。

### 获取消息列表

```http
GET /v1/messages?limit=50&offset=0&type=audio&member_id=123
```

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `limit` | 50 | 返回数量 |
| `offset` | 0 | 跳过数量 |
| `type` | 空 | `text`、`image`、`audio`、`video` |
| `member_id` | 空 | 指定成员 ID |

响应按 `sent_at DESC` 排序。服务器没有返回总条数，客户端历史同步通过“本页少于请求数量”判断结束。

### 手动推送已有消息

```json
{
  "message_id": "MESSAGE_ID",
  "user_id": null
}
```

消息必须已存在于 `messages` 表。正式推送会写入 `push_logs`。

### 测试普通消息

PowerShell 单行命令：

```powershell
$token = Read-Host 'ACCESS_TOKEN'; Invoke-RestMethod -Method Post -Uri 'https://nogi-relay.fly.dev/v1/push/test-message' -Headers @{ Authorization = "Bearer $token" } -ContentType 'application/json; charset=utf-8' -Body (@{ member_name='Nogi Relay'; text='这是一条推送测试消息' } | ConvertTo-Json)
```

可选请求字段：

| 字段 | 说明 |
| --- | --- |
| `member_name` | 通知显示的发送者，默认 `Nogi Relay` |
| `text` | 非空字符串，最多 2000 UTF-8 字节 |
| `user_id` | 仅推送给对应用户；固定 Token 认证下通常不使用 |

这个接口具有以下特性：

- 不写入服务器 `messages` 表。
- 不写入服务器 `push_logs` 表。
- 响应中的 `successCount` 和 `failureCount` 是主要判断依据。
- FCM payload 中包含完整临时消息。
- Android 客户端会接收并尝试写入本地数据库，但以 `test-` 开头的消息不会显示在客户端消息列表中。

成功响应示意：

```json
{
  "success": true,
  "message": {
    "id": "test-message-...",
    "type": "text",
    "text": "这是一条推送测试消息"
  },
  "result": {
    "success": true,
    "successCount": 1,
    "failureCount": 0
  }
}
```

### 测试全屏来电

```powershell
$token = Read-Host 'ACCESS_TOKEN'; Invoke-RestMethod -Method Post -Uri 'https://nogi-relay.fly.dev/v1/push/test-call' -Headers @{ Authorization = "Bearer $token" } -ContentType 'application/json; charset=utf-8' -Body (@{ member_name='来电测试' } | ConvertTo-Json)
```

`test-call` 不写入服务器的 `messages` 或 `push_logs` 表，推送是否成功以接口响应中的 `successCount` 和 `failureCount` 为准。服务端会从 `/v1/push/test-call-audio.wav` 提供一段内置短 WAV，客户端必须下载完成后才显示来电页面。

服务端启动时会清理旧版本遗留的 `test-...` 和 `test_...` 消息，历史消息接口也不会返回测试 ID。客户端数据库升级到 v3 时会清理本地旧测试记录；客户端启动和测试来电结束时还会撤销对应的临时来电通知。

服务端启动时会清理旧版本遗留的 `test-...` 和 `test_...` 消息，历史消息接口也不会返回测试 ID。客户端数据库升级到 v3 时会清理本地旧测试记录；客户端启动和测试来电结束时还会撤销对应的临时来电通知，因此不需要为此卸载应用或清空正式消息。

## 消息数据结构

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | string | 消息唯一 ID，也是去重键 |
| `member_id` | string/null | 成员或分组 ID |
| `member_name` | string | 成员显示名称 |
| `member_avatar_url` | string/null | 成员头像 |
| `phone_image_url` | string/null | 全屏来电图片 |
| `type` | string | `text`、`image`、`audio`、`video` |
| `text` | string/null | 消息文字 |
| `media_url` | string/null | 主媒体地址；API 通常返回受保护的归档地址 |
| `thumbnail_url` | string/null | 缩略图地址 |
| `duration_seconds` | integer/null | 音视频时长 |
| `sent_at` | timestamp | 官网发送时间 |
| `incoming_call_from` | string/null | 非空且类型为 audio 时触发来电逻辑 |
| `ringtone_url` | string/null | 来电铃声地址，客户端可回退到内置铃声 |
| `is_played` | boolean | 服务端播放状态 |
| `original_data` | JSONB | 官网原始消息，仅保存在服务器数据库 |
| `media_local_path` | string/null | 服务器归档文件路径，不直接返回客户端 |
| `thumbnail_local_path` | string/null | 服务器缩略图路径，不直接返回客户端 |
| `phone_image_local_path` | string/null | 来电背景图片归档路径，不直接返回客户端 |

官网类型会按以下规则归一化：

- `text`、`article` → `text`
- `picture`、`photo`、`image` → `image`
- `audio`、`voice`、`call` → `audio`
- `video`、`movie` → `video`
- 未知类型 → `text`

## 数据库

### `devices`

保存 FCM 注册信息：

- `id`
- `fcm_token`，唯一
- `platform`
- `label`
- `user_id`
- `last_seen_at`
- `created_at`

### `messages`

保存官网消息、归一化字段、官网原始 JSON 和媒体归档路径。`id` 是主键，因此同一消息不会重复插入。

### `push_logs`

保存正式推送到每台设备的结果：

- `message_id`
- `device_id`
- `fcm_message_id`
- `status`：`success` 或 `failed`
- `error_message`
- `created_at`

`test-message` 不写这个表，因为其临时 ID 不存在于 `messages`，无法满足外键约束。

### `members`

schema 包含成员基础信息表，但监控实际以官网 `/v2/groups` 返回的有效订阅为准，主流程暂不依赖 `members` 表。

## FCM 推送

### 服务端

服务器发送高优先级 Android data message：

```json
{
  "data": {
    "message_id": "MESSAGE_ID",
    "type": "audio",
    "payload": "{...完整消息 JSON...}"
  }
}
```

完整 payload 小于约 3800 字符时直接携带；超过限制时只发送 `message_id` 和 `type`，客户端再调用消息详情接口。

### 客户端推送流程

**获取 FCM Token:**

Android 客户端在应用启动时通过 Firebase Cloud Messaging SDK 获取设备的 FCM Token:

```kotlin
// 在应用启动或 Firebase 初始化后
FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
    if (!task.isSuccessful) {
        Log.w(TAG, "Fetching FCM registration token failed", task.exception)
        return@addOnCompleteListener
    }
    
    // 获取到的 FCM token
    val token = task.result
    Log.d(TAG, "FCM Token: $token")
    
    // 保存到本地并注册到服务器
    registerDeviceWithServer(token)
}
```

FCM Token 会在以下情况自动刷新:
- 应用首次启动
- 应用被卸载后重新安装
- 用户清除应用数据
- Token 过期(极少发生)

监听 Token 刷新:
```kotlin
class MyFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Refreshed token: $token")
        
        // Token 刷新后重新注册到服务器
        registerDeviceWithServer(token)
    }
}
```

**推送接收流程:**

1. Firebase 生成或刷新 FCM Token。
2. 客户端保存 Token 并调用 `POST /v1/devices` 注册。
3. `NogiFirebaseMessagingService` 收到 data message。
4. 客户端优先解析完整 payload，否则请求 `/v1/messages/:id`。
5. 消息写入 SQLite。
6. 语音来电先由 `IncomingCallPreparationService` 下载到应用私有缓存。
7. 音频下载成功后才发布来电通知并启动全屏来电页；下载失败只保留可重试通知。
8. 其他非文字媒体在后台预下载，新消息才显示通知，重复消息不重复提醒。

### 全屏来电条件

客户端满足以下条件时进入来电通知逻辑：

```text
type == audio
并且 incoming_call_from 非空
```

Android 14 还需要用户允许应用使用全屏通知。概览页面会显示通知权限和全屏通知权限状态,并提供设置入口。

收到高优先级 FCM 后,客户端先在后台准备语音,下载完成后才发布带 `setFullScreenIntent` 的高优先级来电通知并尝试直接启动来电页。准备阶段不发布来电通知;下载失败只显示普通可重试通知。应用在前台、锁屏或后台时都走同一入口;Android 系统或厂商策略仍可能拦截后台 Activity 启动,此时通知栏来电会作为兜底。必须允许通知、全屏通知、自启动、后台运行和锁屏显示权限。

### 静音模式下的铃声播放

在正常模式下,来电铃声通过手机扬声器或已连接的音频设备播放。在静音或振动模式下:

- **无耳机时**:保持系统静音,不播放铃声,只显示来电界面和振动
- **连接耳机时**:铃声仅通过耳机播放,手机扬声器保持静音

铃声使用 `USAGE_MEDIA` 和 `CONTENT_TYPE_MUSIC` 音频属性,确保在 ColorOS 等定制系统的静音模式下也能正常通过耳机播放。

### 语音播放与音频路由

**消息列表语音播放**使用 `USAGE_VOICE_COMMUNICATION` 和 `MODE_IN_COMMUNICATION` 音频属性,确保扬声器/听筒切换流畅:

- **无耳机时**:
  - Android 12+: 使用 `setCommunicationDevice` API 控制扬声器/听筒切换
  - 旧版本: 使用 `isSpeakerphoneOn` 切换
  - 听筒模式下启用距离传感器自动熄屏

- **连接耳机时**:
  - 默认通过耳机播放
  - 点击扬声器按钮可强制切换到手机扬声器
  - 扬声器模式下不启用距离传感器

**全屏来电语音播放**同样使用通话模式音频属性,接听后默认使用听筒模式。

### 消息自动定位

通知点击和语音播放共用同一种分页、滚动定位形式：先根据成员的完整本地消息顺序计算目标所在页，再滚动到目标在该页的位置，使目标消息显示为列表顶部第一条。

**普通消息通知：**

1. 通知 `PendingIntent` 把消息 ID 传给 `MainActivity`。
2. 客户端切换到“消息”页，进入对应成员，并跳到目标消息所在分页。
3. 消息列表完成滚动后立即消费该通知目标。
4. 此后切换底部 Tab、返回成员列表或再次进入成员消息列表，都不会重复执行这次通知定位。

如果目标消息已不在本地数据库中，客户端会消费无效目标并保持正常消息页行为，避免以后反复尝试定位。

**语音播放：**

1. 点击成员进入消息列表时，检测是否有该成员的消息正在播放。
2. 如果有，计算播放消息所在页码并自动跳转。
3. 直接定位到播放消息位置，显示为列表顶部第一条。

消息位置通过 SQLite 按成员查询计算，不受单页 20 条或旧版 100 条查询上限影响。

## 自动识别新增订阅成员

默认情况下 `NOGI_GROUP_IDS` 为空。监控每轮都会重新调用官网分组接口，并筛选：

```text
organization_id 匹配
state == open
subscription.state == active
```

因此，账号新增或取消成员订阅后，服务器会在后续轮询中自动调整监控范围，不需要修改服务器或客户端代码。进程运行期间首次出现的订阅成员会执行与启动时相同的 `past_messages + timeline continuation` 完整历史同步；成员离开有效订阅列表后会移除完成标记，以后重新订阅时会再次完整回填。新成员出现消息并同步到客户端后，客户端会按 `member_id` 自动生成新的成员会话。

浏览器会话文件成功激活后，monitor 会清空当前进程内所有成员的历史回填完成标记；下一轮会为新的会话重新执行完整历史同步。默认 `NOGI_BACKFILL_ON_START=true` 时，启动、新订阅和会话更新触发的历史回填都不会发送 FCM。

如果配置了 `NOGI_GROUP_IDS`，监控范围会固定为指定 ID，此时新增订阅不会自动加入，必须更新该环境变量。

## 媒体存储与下载

### 服务器归档

- 只允许从 HTTPS 地址归档。
- 单个文件最大 100 MB。
- 下载超时 90 秒。
- 使用临时文件写入，完成后再重命名，避免客户端读取半成品。
- 相同目标文件已存在且非空时不会重复下载。
- 正式语音来电的 `phone_image_url` 会归档为消息目录中的 `phone_image.<扩展名>`。
- API 对外返回受保护的 Relay 媒体 URL，客户端不直接依赖官网私有 CDN URL。
- Fly.io 的持久化卷挂载到 monitor 进程的 `/data`。
- 线上媒体通过 `https://nogi-relay.fly.dev:8081` 提供，并要求相同 Bearer Token。

### Android 下载

- 收到消息或同步历史时，媒体先进入应用私有缓存。
- 只有用户点击下载按钮，才复制到系统公共 `Download` 文件夹。
- Android 10 及以上使用 MediaStore，不需要传统存储权限。
- Android 8/9 需要 `WRITE_EXTERNAL_STORAGE` 权限。
- 文件名格式为 `成员名_消息ID.扩展名`。
- 同名文件存在时自动添加 `(2)`、`(3)` 等后缀。

## Android 客户端使用说明

### 概览

- 查看 FCM 是否已配置。
- 查看通知权限。
- 查看全屏通知权限。
- 手动同步服务器历史消息。
- 执行本机全屏来电界面测试。

客户端在启动和每次回到前台时都会同步历史消息。单页读取 200 条，最多读取 50 页，即单次最多扫描 10,000 条服务器消息。

### 启动过渡页与配色

- `MainActivity` 在 Manifest 中使用独立的 `Theme.NogiRelay.Launch` 启动主题，首帧绘制后切换到 `Theme.NogiRelay`，因此不会把启动图留在主界面。
- `res/drawable/launch_background.xml` 使用白色背景和居中标志；Android 12 及以上使用 `values-v31` 中的专用启动图。
- `NogiRelayTheme` 的浅色 `background`、`surface` 以及系统状态栏/导航栏均为 `#FFFFFF`，底部 `NavigationBar` 直接使用同一背景色。
- 启动页资源来自已安装官方 APK 的资源结构复核，仅复制启动图所需资源，不依赖官方 App 运行时。

### 消息

- 首层按成员分组。
- “最近收到”显示最近六个成员。
- “全部成员”显示成员会话、最后一条预览和当前加载集合中的计数。
- 进入成员后，每页 20 条。
- 点击中间页码按钮可输入页码跳转。
- 搜索会查询该成员本地数据库中的全部消息，然后对结果分页。
- 翻页或搜索变化后自动回到顶部。
- 点击普通消息通知会直接进入对应成员和分页，并把通知对应消息显示为顶部第一条；完成这一次定位后，后续页面导航不会再次定位到它。

成员入口由本机最近 200 条非测试消息生成；进入成员后的分页和搜索会查询该成员的全部本地消息。因此消息量非常大时，较久未发消息且不在最近 200 条中的成员可能不会出现在入口列表，这是实现限制。

### 设置

- Relay 同步服务地址，必须使用 HTTPS。
- Relay 访问令牌。
- 保存并重新注册本机 FCM Token。
- 启用或关闭翻译。
- 用户称呼；仅用于在翻译和显示前精确替换消息中的 `%%%` 占位符，不会替换成员姓名。
- 所选模型供应商的 API Key。
- 校验 API Key 并加载当前账号可用模型。
- 只能从已加载的可用模型中选择翻译模型；应用不会预设或自动选择模型。

### 翻译

- 翻译由 Android 客户端直接请求所选模型供应商。供应商支持时优先使用 Anthropic Messages API，其次使用 OpenAI Responses API；Gemini 使用原生 `generateContent` API。
- API Key 不发送到 Nogi Relay 服务器。
- 客户端先把消息中的 `%%%` 精确替换成设置中的用户称呼；提示词不会要求模型把成员姓名替换成用户称呼。
- `TranslationLayout` 保留原文全部换行符、空行、行首和行尾空白，并把非空文本行整理为有序片段。一次模型请求会同时携带完整原文 `full_text` 和全部 `segments`，因此模型可以结合整条消息的上下文翻译，而不是逐行独立请求。
- 提示词要求人名保持原文，不得翻译、音译、改写或替换；对于不应翻译的专有名词、代码等内容也保留原文。模型只能返回与输入片段数量和顺序一致的 JSON 字符串数组。
- 客户端会验证模型输出必须是合法 JSON 数组、片段数量一致、每项均为字符串且不含额外换行。验证通过后，译文片段才会与客户端保存的原始分隔符重新组合；因此手动换行和空行结构与原文一致。屏幕宽度造成的自动折行不属于消息格式，仍由 Compose 布局决定。
- 译文保存和显示阶段不会再把 `%%` 转成换行，也不会压缩或猜留空行。
- 最多同时执行三个翻译请求。
- 网络、API 或输出格式验证失败时会记录指数退避时间，最长等待 5 分钟；下一次 App 启动、新消息、同步、刷新或设置变更触发翻译队列后，已到期的消息才会再次请求。
- 已完成的翻译保存在本机 SQLite。
- 不含汉字、平假名或片假名的消息，以及纯表情、纯符号消息，不会请求翻译。
- 点击“重新翻译”会先清空该消息已有译文并将其标记为待翻译，再使用当前提示词和模型重新请求；请求失败时不会恢复旧译文。提示词或分段逻辑更新不会自动清理历史译文，需要逐条重新翻译才能应用新规则。

## 构建 Android APK

在项目根目录执行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
.\gradlew.bat :app:assembleDebug --no-daemon
```

默认构建不会把 Relay Token 写入 APK。需要仅在本机调试包中提供默认 Token 时，在未提交的 `local.properties` 中保留以下配置（`local.properties` 已被忽略）：

```properties
sdk.dir=C:/Users/YOUR_USER/AppData/Local/Android/Sdk
relay.access.token=YOUR_ACCESS_TOKEN
```

正式或共享 APK 不应注入 Token，应在应用设置页填写设备使用的 Token。

项目使用 JDK 17、compileSdk 34，调试包由 Android Gradle Plugin 生成。若本机 JDK 17 安装在其他目录，只需相应调整 `JAVA_HOME`。

输出文件：

```text
app/build/outputs/apk/debug/app-debug.apk
```

检查 APK 版本：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\34.0.0\aapt.exe" dump badging '.\app\build\outputs\apk\debug\app-debug.apk' | Select-Object -First 3
```

校验文件完整性：

```powershell
Get-FileHash -Algorithm SHA256 '.\app\build\outputs\apk\debug\app-debug.apk'
```

Windows 如果出现 `Unable to establish loopback connection` 或 `Could not receive a message from the daemon`，通常是本机 Java/Gradle 进程通信被安全软件或用户级 Gradle 参数影响。先确认使用 JDK 17，再在同一个 PowerShell 会话中尝试：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:/tmp'
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain "-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -Djdk.net.unixdomain.tmpdir=C:/tmp"
```

若仍失败，删除已停止的 Gradle 进程后重试，或在 Android Studio 的 Gradle 设置中选择 JDK 17。不要为了绕过错误而提交用户目录下的全局 `gradle.properties` 或构建缓存。

Release 构建没有配置正式签名；对外发布前需要创建并妥善保管签名密钥，并在 Gradle 中配置 release signing。

## 安装到 Android 手机

打开手机开发者选项和 USB 调试，连接电脑后，在项目根目录执行以下命令定位 ADB：

```powershell
$sdk = $env:ANDROID_SDK_ROOT
if ([string]::IsNullOrWhiteSpace($sdk)) { $sdk = $env:ANDROID_HOME }
if ([string]::IsNullOrWhiteSpace($sdk)) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (!(Test-Path -LiteralPath $adb)) { throw "找不到 ADB：$adb，请安装 Android SDK Platform-Tools" }
& $adb start-server
& $adb devices -l
```

确认目标设备状态为 `device`，再覆盖安装并保留原有应用数据：

```powershell
$deviceId = 'YOUR_DEVICE_ID'
& $adb -s $deviceId install -r '.\app\build\outputs\apk\debug\app-debug.apk'
```

只有一台设备时也可以省略 `-s $deviceId`：

```powershell
& $adb install -r '.\app\build\outputs\apk\debug\app-debug.apk'
```

安装后启动应用并核对包信息：

```powershell
& $adb -s $deviceId shell am start -W -n com.nogirelay.app/.MainActivity
& $adb -s $deviceId shell dumpsys package com.nogirelay.app | Select-String 'versionName=|versionCode='
```

`install -r` 会保留本机数据库、设置和已保存的 Relay 配置。以下命令会删除应用数据，仅在需要完全重置时使用：

```powershell
& $adb -s $deviceId uninstall com.nogirelay.app
```

首次运行后建议检查：

1. 通知权限已允许。
2. Android 14 的全屏通知权限已允许。
3. 设置页面中的服务器地址和访问令牌正确。
4. 点击“保存并注册推送”，页面显示设备已注册。
5. 对 OPPO、vivo、小米等系统，允许自启动、后台运行并关闭不必要的电池限制。

如果构建时项目根目录没有 `app/google-services.json`，应用仍可安装和浏览已同步数据，但 FCM 初始化与推送注册会失败；需要从 Firebase 项目下载与包名 `com.nogirelay.app` 匹配的配置文件后重新构建。

## 开发故障排查

Gradle、数据库、Firebase 和官网会话的常见问题集中在 [DEPLOYMENT.md](DEPLOYMENT.md) 的排障章节；开发时优先确认 JDK 17、Android SDK 34、server/.env 和本地 PostgreSQL。
