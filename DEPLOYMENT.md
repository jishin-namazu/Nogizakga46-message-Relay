# Nogi Relay 部署文档

本文件面向部署和维护人员,覆盖生产环境配置、Fly.io 部署、官网会话管理、推送验证和故障排查。

开发环境配置、代码结构和 API 细节请查看 [DEVELOPMENT.md](DEVELOPMENT.md)；完整环境变量见下文第 1 节。

## 1. 生产环境必需配置

部署到 Fly.io 前必须配置以下 Secret:

| Secret | 用途 |
| --- | --- |
| `DATABASE_URL` | PostgreSQL 连接字符串 |
| `ACCESS_TOKEN` | API 认证令牌 |
| `FIREBASE_PROJECT_ID` | Firebase 项目 ID |
| `FIREBASE_PRIVATE_KEY_BASE64` | Base64 编码的 Firebase Admin JSON |

### 1.1 完整环境变量

除上表的 Secret 外，以下变量通过 `fly.toml` 的 `[env]` 或 Fly Secret 提供；默认值取自源码。

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `NODE_ENV` | `development` | `production` 时错误响应不返回原始信息，日志默认写入 `/data/nogi-logs` |
| `PORT` | `3000` | API 监听端口（生产为 `8080`） |
| `DATABASE_URL` | 无（必需） | PostgreSQL 连接串 |
| `ACCESS_TOKEN` | 无（必需） | REST API、管理接口和 8081 媒体服务共用的 Bearer Token |
| `FIREBASE_PROJECT_ID` | 无（必需） | Firebase 项目 ID |
| `FIREBASE_PRIVATE_KEY_BASE64` | 无 | Base64 编码的服务账号 JSON（生产推荐） |
| `FIREBASE_PRIVATE_KEY_JSON` | 无 | 直接提供服务账号 JSON 字符串 |
| `FIREBASE_PRIVATE_KEY_PATH` | `./firebase-admin-key.json` | 本地开发读取的服务账号文件路径 |
| `PUBLIC_BASE_URL` | `https://nogi-relay.fly.dev` | 生成测试音频等公开 URL 的基址 |
| `PUBLIC_MEDIA_BASE_URL` | 同 `PUBLIC_BASE_URL` | 生成媒体 URL 的基址；生产指向 8081 媒体服务 |
| `MEDIA_STORAGE_DIR` | `/app/nogi-media` | 媒体归档目录（生产为 `/data/nogi-media`） |
| `MEDIA_MAX_BYTES` | `104857600` | 单个媒体文件上限（100 MB） |
| `NOGI_MEDIA_PORT` | `8081` | monitor 媒体服务端口 |
| `NOGI_WEB_URL` | `https://message.nogizaka46.com` | 官网网页基址 |
| `NOGI_API_URL` | `https://api.message.nogizaka46.com` | 官网 API 基址（不是网页域名） |
| `NOGI_APP_ID` | `jp.co.sonymusic.communication.nogizaka 2.5` | 请求头 `X-Talk-App-ID` |
| `NOGI_APP_PLATFORM` | `web` | 请求头 `X-Talk-App-Platform` |
| `NOGI_ORGANIZATION_ID` | `1` | 组织 ID |
| `NOGI_GROUP_IDS` | 空 | 逗号分隔的成员 ID；为空时按订阅自动发现 |
| `NOGI_ACCEPT_LANGUAGE` | `zh-CN,en-US,ja` | 请求头 `Accept-Language` |
| `NOGI_POLL_INTERVAL_SECONDS` | `60` | 轮询间隔，最小 15 |
| `NOGI_BACKFILL_ON_START` | `true` | 是否在启动、新订阅和会话更新时导入历史 |
| `NOGI_BLOG_POLL_INTERVAL_SECONDS` | `60` | 公开 BLOG 更新轮询间隔，最小 15 秒 |
| `NOGI_BLOG_PAGE_SIZE` | `5` | 每次 BLOG API 分页数量，最小 5；首次基线和后续增量都会按页翻完所需范围 |
| `NOGI_BROWSER_STATE_FILE` | `/data/nogi-browser-state.json` | 浏览器会话文件 |
| `NOGI_ACCESS_TOKEN_STATE_FILE` | 会话同目录 `nogi-access-token.json` | 访问令牌缓存 |
| `NOGI_BROWSER_HEADLESS` | `true` | 是否无头运行 Chromium |
| `NOGI_BROWSER_BLOCK_MEDIA` | `true` | 阻止页面加载图片/媒体/字体 |
| `NOGI_BROWSER_SETTLE_SECONDS` | `8` | 会话刷新后等待页面稳定，最小 2 |
| `NOGI_BROWSER_AUTH_WAIT_SECONDS` | `30` | 等待页面发出 Authorization 的时间，最小 10 |
| `NOGI_BROWSER_REQUEST_TIMEOUT_SECONDS` | `30` | 单次官网 API 请求超时，最小 10 |
| `NOGI_BROWSER_RESTART_INTERVAL_SECONDS` | `0`（禁用） | 浏览器定时重启间隔；`0`、负数、未设置或非数字都表示禁用，仅保留 RSS 超过 850 MB 时的重启；正数最小 300 秒 |
| `NOGI_BROWSER_EXECUTABLE_PATH` | 无 | 指定 Chromium/Edge 可执行文件；`PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` 为等价回退 |
| `NOGI_MAX_TOKEN_REFRESH_FAILURES` | `3` | `/v2/update_token` 连续失败进入 `authPaused` 的阈值 |
| `NOGI_MAX_AUTH_FAILURES` | 无 | 上一项的旧配置名回退 |
| `LOG_STORAGE_DIR` | 生产 `/data/nogi-logs`，否则 `./logs` | JSONL 错误日志目录 |
| `LOG_MAX_FILE_BYTES` | `26214400` | 单个日志文件上限（25 MB），超限轮转 |
| `LOG_RETENTION_DAYS` | `30` | 日志保留天数 |
| `DB_RETRY_ATTEMPTS` | `5` | 瞬时数据库错误的尝试次数 |
| `DB_RETRY_BASE_DELAY_MS` | `1000` | 指数退避基数，最小 100 |
| `DB_RETRY_MAX_DELAY_MS` | `15000` | 退避上限 |
| `DB_CONNECTION_TIMEOUT_MS` | `10000` | 建连超时 |
| `DB_POOL_MIN` / `DB_POOL_MAX` | `1` / `20` | 连接池大小 |

以下变量曾出现在历史配置中，但当前代码从不读取，已从 `fly.toml` 和 `server/.env.example` 中移除：`NOGI_MESSAGE_COUNT`、`NOGI_MONITOR_MODE`（时间线条数由源码中的 `count=200` 固定）、`NOGI_ACCESS_TOKEN`、`NOGI_REFRESH_TOKEN`、`NOGI_AUTH_TKN`、`LOG_LEVEL`。浏览器模式由 monitor 自动管理令牌，不再需要手工填写官网令牌。

## 2. Fly.io 部署

### 2.1 设置 Secret

```powershell
flyctl auth login
flyctl secrets set DATABASE_URL='YOUR_DATABASE_URL' -a nogi-relay
flyctl secrets set ACCESS_TOKEN='YOUR_RANDOM_ACCESS_TOKEN' -a nogi-relay
flyctl secrets set FIREBASE_PROJECT_ID='YOUR_FIREBASE_PROJECT_ID' -a nogi-relay
flyctl secrets set FIREBASE_PRIVATE_KEY_BASE64='YOUR_BASE64_FIREBASE_JSON' -a nogi-relay
```

检查 Secret(不显示原文):
```powershell
flyctl secrets list -a nogi-relay
```

**重要:** 不要把 Secret 写入 fly.toml、代码或日志。

### 2.2 初始化数据库

新数据库优先执行当前 schema：

```powershell
flyctl postgres connect -a YOUR_POSTGRES_APP
psql $env:DATABASE_URL -f .\server\database\schema.sql
```

也可以在 API 可访问后运行幂等初始化接口：

```powershell
$token = Read-Host 'ACCESS_TOKEN'
Invoke-RestMethod -Method Post -Uri 'https://YOUR_APP_NAME.fly.dev/init-db' -Headers @{ Authorization = "Bearer $token" }
```

schema 会创建媒体字段 `media_local_path`、`thumbnail_local_path` 和来电背景字段 `phone_image_local_path`。API 启动时还会执行兼容性迁移。

数据库机器不能使用自动休眠。`nogi-db` 是独立的 Fly 应用，部署 `nogi-relay` 不会覆盖它的机器配置；请保持数据库机器的 autostop 关闭，并清除 Postgres Flex 的 scale-to-zero 倒计时：

```powershell
flyctl machine update 683557df504348 -a nogi-db --autostop off '--env=FLY_SCALE_TO_ZERO=' --yes
```

这里的空值是有意的：Postgres Flex 源码会在变量未配置/为空时不启动 scale-to-zero worker；`0s` 反而会导致 ticker 崩溃。执行后确认 `flyctl logs -a nogi-db` 不再出现 `Configured scale to zero with duration`，并确认数据库保持 `started`。服务端仍会对 PostgreSQL 连接和读写做指数退避重试。

### 2.4 上传官网浏览器会话

浏览器模式必须先在可信本机生成登录状态：

```powershell
Set-Location .\server
$env:NOGI_BROWSER_EXECUTABLE_PATH = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
$env:NOGI_BROWSER_STATE_FILE = '.\nogi-browser-state.json'
npm ci
npm run bootstrap:browser
```

**重要**: `bootstrap:browser` 每次运行都会清除旧的会话文件,强制用户重新登录。这确保了会话的新鲜度和安全性。在官网窗口完成登录并确认能看到消息后,回到终端按回车保存新会话。

生成的 state 文件包含 cookies、localStorage 和 IndexedDB，必须视为密码处理。

**上传会话到生产环境(推荐使用 API 方式):**

```powershell
node upload-session.js .\nogi-browser-state.json https://YOUR_APP_NAME.fly.dev YOUR_ACCESS_TOKEN
```

脚本先上传完整会话文件，然后等待 monitor 激活结果。正常输出依次为 `Session upload accepted` 和 `Session activated`；仅出现前者表示文件已经写入，但官网认证尚未验证。激活过程：

1. API 使用临时文件和原子替换写入会话，并记录唯一 `requestId`。
2. monitor 的目录监听器同时处理 `change` 和 `rename` 事件。
3. monitor 直接使用已读取的完整快照重建浏览器上下文，旧上下文不能回写覆盖它。
4. monitor 打开官网并执行一次官网 API 请求；成功后状态才变为 `active`。
5. 激活成功会清空成员历史回填完成标记；下一轮为全部当前有效订阅成员重新导入 `past_messages` 并遍历 timeline continuation。

上传脚本最多等待 90 秒。状态变为 `failed` 时会输出 `activationError` 并以非零状态退出。

**备用方式(使用 SSH):**

如果 API 方式不可用，可以通过 SSH 直接上传到持久卷：

```powershell
Set-Location ..
flyctl status -a nogi-relay
flyctl ssh sftp put .\server\nogi-browser-state.json /data/nogi-browser-state.json -a nogi-relay --machine MONITOR_MACHINE_ID --mode 0600
```

SSH 方式也会触发目录监听器，但没有 API `requestId`，因此推荐优先使用上传脚本，以获得端到端激活确认。会话过期后重新生成并上传；不要把会话文件或配套状态文件提交到 Git。

### 2.5 机器配置

**默认配置:**
- Memory: 1024 MB (1 GB)
- CPUs: 1 shared vCPU
- 类型: shared-cpu-1x (免费层)

**查看当前配置:**
```powershell
flyctl scale show --app nogi-relay
```

**内存使用情况:**
- 正常运行: 500-700 MB
- 刷新会话峰值: 800-900 MB
- 主动重启阈值: > 850 MB

服务器已优化为在 1GB 内存下稳定运行:
- 使用轻量级 `chromium-headless-shell`
- 启用 13+ 个内存优化参数
- 主动监控并在接近限制时重启浏览器
- 强制垃圾回收清理内存

**如遇频繁 OOM,可升级内存 (需付费):**
```powershell
flyctl scale memory 2048 -a nogi-relay  # 升级到 2GB
```

### 2.6 部署和回滚

```powershell
flyctl deploy --remote-only --app nogi-relay
flyctl status --app nogi-relay
Invoke-RestMethod 'https://YOUR_APP_NAME.fly.dev/health'
flyctl logs --app nogi-relay --no-tail
```

**启动顺序和 Fly 健康检查:**

- `server/start-all.sh` 先启动 API，并等待本机 `/health` 返回 `200`。
- API 健康后才启动 monitor；monitor 再启动 8081 媒体服务和 Chromium。
- monitor 第一次取得有效官网会话、发现新订阅成员或成功激活更新后的会话文件时，会同步相应成员的 `past_messages`，并沿 timeline `continuation` 拉完全部页面。包含历史回填的轮询不受常规 120 秒轮询总超时限制。
- Fly 会在机器进入 `started` 后独立探测端口，因此应用监听前可能出现瞬时 `Health check ... failed` 日志。
- API HTTP、API TCP 和媒体 TCP 检查均使用 30 秒 `grace_period`。它避免启动窗口导致部署失败，但不会隐藏平台首次探测日志。

部署验收：

```powershell
flyctl status --app nogi-relay
flyctl checks list --app nogi-relay
Invoke-RestMethod 'https://YOUR_APP_NAME.fly.dev/health'
```

正常结果是机器 `started`、checks `3/3 passing`、`/health` 返回 `status=ok`。只要最终 checks 全部 passing，启动前几秒的 failed 记录不属于持续服务故障。

需要回滚时先列出历史版本，再选择已验证的镜像版本：

```powershell
flyctl releases --app nogi-relay
flyctl deploy --app nogi-relay --image registry.fly.io/nogi-relay:IMAGE_TAG
```

### 2.7 持久卷配置

**默认配置:**
- 大小: 3 GB
- 挂载点: `/data`
- 加密: 已启用
- 在免费额度内 (最多 3GB)

**查看当前卷:**
```powershell
flyctl volumes list --app nogi-relay
```

**存储用途分配:**
- 会话文件: `nogi-browser-state.json` (~几百 KB)
- Access token: `nogi-access-token.json` (~几 KB)
- 日志文件: `/data/nogi-logs/` (~100-200 MB)
- 媒体缓存: `/data/nogi-media/` (~2.7 GB)

**存储空间监控:**
```powershell
flyctl ssh console -a nogi-relay
df -h /data
du -sh /data/*
```

**如需扩容:**
```powershell
# 扩展到 5GB (超出免费额度,需付费)
flyctl volumes extend VOLUME_ID --size 5 -a nogi-relay

# 扩容会立即生效,无需重启服务
```

**注意:** `fly.toml` 中的 `initial_size = "3gb"` 只对新卷生效,不会自动扩展现有卷。

### 2.8 媒体存储

正式图片、语音、视频、缩略图和来电背景按内容哈希存储在 `/data/nogi-media/objects/<sha256>.<扩展名>`；数据库中的 `media_local_path`、`thumbnail_local_path` 和 `phone_image_local_path` 分别指向对应对象。相同字节只保存一份，因此多条来电复用同一张成员照片时不会重复占用空间；图片类媒体的扩展名按文件头识别。升级前按消息目录归档的旧文件仍保留在 `/data/nogi-media/<消息ID>/`，继续可读。

单个媒体文件限制: 100 MB

来电背景通过 `/v1/messages/:id/media/phone_image` 提供，和其他媒体一样需要 Relay Bearer Token。持久卷不能跨应用自动复制，变更应用或区域前应先备份。

## 3. 生产验证

### 3.1 API 健康检查

```powershell
Invoke-RestMethod 'https://YOUR_APP_NAME.fly.dev/health'
```

正常返回 `status: ok`。Fly 机器状态应显示单个 `app` 进程组为 `started` 且 checks 全部通过；API 与 monitor 是该容器内的两个 Node.js 进程。

### 3.2 验证 Relay API Token

```powershell
$token = Read-Host 'ACCESS_TOKEN'
try {
  Invoke-RestMethod -Uri 'https://YOUR_APP_NAME.fly.dev/v1/devices' -Headers @{ Authorization = "Bearer $token" } | ConvertTo-Json -Depth 6
} catch {
  $_.Exception.Response.StatusCode.value__
}
```

`200` 表示 Token 有效，`401` 表示缺失或错误，`429` 表示触发限流。所有 `/v1/*` 接口按来源 IP 限制为 15 分钟最多 100 次请求。

### 3.3 验证官网会话

浏览器模式下，短期官网 Token 只在 monitor 内存中使用，由官网页面维护刷新流程。查看日志：

```powershell
flyctl logs --app nogi-relay --no-tail
```

正常轮询会出现：

```text
Nogi browser monitor poll complete: groups=..., fetched=..., stored=..., pushed=...
```

每次成员全量同步还会按触发原因输出：

```text
Nogi history fetched: reason=startup|new_subscription|session_reload, group=..., timeline_pages=..., timeline_messages=..., past_messages=..., unique_messages=...
```

它先请求 `/v2/groups/{groupId}/past_messages`，然后请求最新 200 条 timeline；只要响应仍有 `continuation` 就继续请求下一页。两路结果按消息 ID 去重并写入 PostgreSQL。生产配置 `NOGI_BACKFILL_ON_START=true` 会抑制启动、新订阅和会话更新触发的历史消息 FCM；数据库中已存在的消息也不会再次推送。单个成员同步或持久化中途失败时不会写入该成员的完成标记，后续轮询会重新尝试。

**自动维护机制:**

monitor 会自动维护官网会话的有效性，无需人工干预：

1. **按官网逻辑刷新:** 保留当前有效 Token；临近过期时预刷新，真实 401 时等待官网产生不同的新 Token，并只重试原请求一次
2. **按内存重启浏览器:** 进程 RSS 超过 850 MB 时重启浏览器释放内存。默认不再定时重启（`NOGI_BROWSER_RESTART_INTERVAL_SECONDS=0`）；如需额外兜底，可设为正数秒数（例如 `43200` 表示 12 小时）
3. **认证状态隔离:** `/v2/update_token` 返回 `400` 时立即进入 `signedOut`；其他 4xx/5xx 连续达到 `NOGI_MAX_TOKEN_REFRESH_FAILURES`（默认 3）后进入 `authPaused`。两种状态都会关闭 Chromium 并停止官网轮询，只等待新会话文件。`signedOut` 期间每 5 分钟输出一次 `[NOGI_SESSION_UPDATE_REQUIRED]`。

这些机制确保了服务的长期稳定运行。

**常见失效信号:**

- `[NOGI_AUTH_SIGNED_OUT]` 或 `/v2/update_token 返回 400`：官网会话已退出，立即进入 `signedOut`。
- `[NOGI_SESSION_UPDATE_REQUIRED]`：会话仍处于 `signedOut`，需要上传新会话文件。
- `Nogi API 401`：业务请求触发了刷新路径，不单独代表最终失效。
- 页面没有带 Authorization 的请求：浏览器状态未登录或已过期。
- `No active subscribed groups found`：没有有效订阅或账号配置不匹配。
- 浏览器反复重启：重新生成并上传 state 文件。
- **在其他浏览器登录过**：网页端登录互斥，在其他浏览器登录后，服务器会话会很快失效。

不要只解析 JWT 的 `exp` 判断官网会话，真实 API 请求成功才是最终判断。

### 3.4 官网会话过期处理

**判断会话过期的信号:**

1. **日志中出现会话状态或认证错误:**
   ```text
   [NOGI_AUTH_SIGNED_OUT] /v2/update_token 返回 400，会话已退出。
   [NOGI_SESSION_UPDATE_REQUIRED] 会话已退出，需要更新会话文件。
   官网页面没有发出带 Authorization 的 API 请求，请先在浏览器会话中登录
   Error fetching member timeline
   ```

2. **在其他设备网页端登录过:**
   - 官网网页端登录是互斥的，在其他浏览器登录后，服务器的会话会很快被注销
   - 移动端 App 不受影响

3. **monitor 停止获取新消息:**
   - 日志中 `fetched=0` 持续出现
   - 或完全没有轮询日志输出

4. **认证状态告警:**
   - `/v2/update_token` 返回 400 时立即显示:
     ```text
     [NOGI_AUTH_SIGNED_OUT] /v2/update_token 返回 400，会话已退出。
     ```
   - `signedOut` 期间每 5 分钟显示:
     ```text
     [NOGI_SESSION_UPDATE_REQUIRED] 会话已退出，需要更新会话文件。
     ```
   - 其他刷新错误连续达到 `NOGI_MAX_TOKEN_REFRESH_FAILURES`（默认 3）后显示:
     ```text
     [NOGI_AUTH_PAUSED] 官网 access token 已失效，且无法通过官网获取新 token。
     已停止 Chromium、官网认证请求和消息轮询；HTTP 健康检查、管理接口、媒体服务及会话文件监听保持运行。
     请上传新的浏览器会话文件；新会话通过官网 API 验证后，监控会自动恢复。
     ```
   - 结构化错误 scope 为 `monitor.auth_paused`，context 中包含 `requires_session_update=true`；400 还包含 `auth_state=signedOut`

**重新上传会话的步骤:**

1. 在本地生成新的会话文件(会自动清除旧会话):
   ```powershell
   Set-Location .\server
   $env:NOGI_BROWSER_EXECUTABLE_PATH = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
   $env:NOGI_BROWSER_STATE_FILE = '.\nogi-browser-state.json'
   npm run bootstrap:browser
   ```

2. 在打开的浏览器窗口中完成登录,确认能看到消息后按回车保存新会话

3. 使用上传脚本(推荐):
   ```powershell
   node upload-session.js .\nogi-browser-state.json https://YOUR_APP_NAME.fly.dev YOUR_ACCESS_TOKEN
   ```
   
   脚本只有在 monitor 完成浏览器重载和官网 API 验证后才报告成功。
   
   **自动恢复机制:**
   - 目录监听器会检测原子替换产生的 `rename`/`change` 事件
   - 自动关闭旧的浏览器实例（释放所有资源和过期状态）
   - 使用预先读取的完整快照重新打开浏览器，避免旧状态覆盖新文件
   - 请求官网 API 验证新会话
   - 重置 `/v2/update_token` 连续失败计数器为 0，并清除 `signedOut`/`authPaused`
   - 写入 `active` 或 `failed` 激活状态
   - **无需手动重启服务或机器**

4. 观察日志确认恢复正常:
   ```powershell
   flyctl logs --app nogi-relay
   ```
   
   应该看到：
   ```text
   检测到外部会话文件更新,准备重载浏览器上下文...
   开始重载浏览器会话...
   关闭当前浏览器实例...
   使用新会话重新打开浏览器...
   正在验证前端会话...
   前端会话验证成功
   ✓ 浏览器会话已激活并验证: <version>
   Nogi browser monitor poll complete: groups=X, fetched=X, stored=X, pushed=X
   ```
   
   如果之前已经进入 `[NOGI_AUTH_PAUSED]`，上传的新会话通过验证后会自动恢复正常轮询，不再显示告警。

**⚠️ 重要：多设备登录互斥**

官网的网页端登录是**互斥**的：
- 当账号在设备 A 网页端登录后，再在设备 B 网页端登录，设备 A 的会话会**很快被注销**
- 这意味着如果上传会话后在其他浏览器（电脑/手机浏览器）登录了官网，服务器的会话会失效
- **移动端 App 登录不影响**网页端会话

因此：
- ✅ 可以同时使用：服务器会话 + 移动端 App
- ❌ 不能同时使用：服务器会话 + 其他浏览器登录
- 如果在其他浏览器登录过，必须重新生成并上传新的会话文件

**备用方式(使用 SSH):**

如果 API 方式不可用,可以通过 SSH 直接上传:

```powershell
Set-Location ..
flyctl status -a nogi-relay
flyctl ssh sftp put .\server\nogi-browser-state.json /data/nogi-browser-state.json -a nogi-relay --machine MONITOR_MACHINE_ID --mode 0600
```

上传后 monitor 会自动重新加载会话，无需重启。

**检查会话状态:**

使用上传脚本的 status 命令:
```powershell
node .\server\upload-session.js --status https://YOUR_APP_NAME.fly.dev YOUR_ACCESS_TOKEN
```

或使用 PowerShell 直接调用 API:
```powershell
$token = 'YOUR_ACCESS_TOKEN'
Invoke-RestMethod `
  -Uri 'https://YOUR_APP_NAME.fly.dev/v1/admin/browser-session/status' `
  -Headers @{ Authorization = "Bearer $token" }
```

状态判定：

- `activationStatus=active` 且 `activated=true`：最新上传已经生效。
- `pending` 或 `activating`：稍后再次查询。
- `failed`：查看 `activationError`，重新生成会话后再次上传。
- `unknown`：会话文件存在，但没有 API 上传状态记录，通常来自旧版本或 SSH 上传。

**安全注意事项:**
- 会话文件包含完整登录凭证,必须妥善保管
- 不要提交到 Git 或分享给他人
- 定期轮换会话文件(建议每月至少一次)

### 3.5 获取服务器媒体文件

**完整工作流程：**

1. **查询消息列表获取消息 ID**
2. **下载对应的媒体文件**
3. **（可选）按成员 ID 过滤消息**

**步骤 0: 获取成员 ID（可选）**

消息查询可以按成员 ID 过滤。获取成员 ID 的方法：

```powershell
$token = Read-Host 'ACCESS_TOKEN'

# 方法 1: 从任意消息中查看成员信息
$response = Invoke-RestMethod `
  -Uri 'https://YOUR_APP_NAME.fly.dev/v1/messages?limit=20' `
  -Headers @{ Authorization = "Bearer $token" }

# 查看所有消息的成员 ID 和名称
$response.messages | Select-Object member_id, member_name | Sort-Object member_id -Unique

# 方法 2: 查看特定成员的消息来确认 ID
$response.messages | Where-Object { $_.member_name -like "*成员名*" } | Select-Object member_id, member_name -First 1
```

输出示例：
```
member_id member_name
--------- -----------
29        遠藤 さくら
45        乃木坂46   
47        池田 瑛紗  
48        一ノ瀬 美空
```

记下你关注的成员 ID，用于后续查询。

**步骤 1: 查询消息列表**

```powershell
$token = Read-Host 'ACCESS_TOKEN'

# 获取最新 50 条语音消息
$response = Invoke-RestMethod `
  -Uri 'https://YOUR_APP_NAME.fly.dev/v1/messages?limit=50&type=audio' `
  -Headers @{ Authorization = "Bearer $token" }

# 查看消息信息
$response.messages | Format-Table id, member_name, member_id, created_at, text
```

**API 查询参数：**
- `limit`: 返回数量（默认 50，最大 1000）
- `offset`: 跳过前 N 条（用于分页）
- `type`: 消息类型
  - `text`: 文字消息
  - `image`: 图片消息
  - `audio`: 语音消息
  - `video`: 视频消息
- `member_id`: 按成员 ID 过滤

结果固定按 `sent_at DESC` 排序，不支持自定义排序参数。

**查询示例：**

```powershell
# 获取指定成员的最新 30 条消息
$response = Invoke-RestMethod `
  -Uri 'https://YOUR_APP_NAME.fly.dev/v1/messages?member_id=29&limit=30' `
  -Headers @{ Authorization = "Bearer $token" }
$response.messages | Format-Table id, member_name, type, text

# 获取所有类型的消息（分页）
$page1 = Invoke-RestMethod `
  -Uri 'https://YOUR_APP_NAME.fly.dev/v1/messages?limit=100&offset=0' `
  -Headers @{ Authorization = "Bearer $token" }
$page2 = Invoke-RestMethod `
  -Uri 'https://YOUR_APP_NAME.fly.dev/v1/messages?limit=100&offset=100' `
  -Headers @{ Authorization = "Bearer $token" }
Write-Host "第1页: $($page1.messages.Count) 条, 第2页: $($page2.messages.Count) 条"
```

**步骤 2: 下载媒体文件**

**媒体类型说明：**
- `media`: 原始文件（图片/语音/视频）
- `thumbnail`: 缩略图（仅图片和视频有）
- `phone_image`: 来电背景图（仅语音消息有）

**查询哪些消息有来电背景图：**

```powershell
$token = Read-Host 'ACCESS_TOKEN'

# 查询语音消息并查看来电背景图
$response = Invoke-RestMethod `
  -Uri 'https://YOUR_APP_NAME.fly.dev/v1/messages?type=audio&limit=50' `
  -Headers @{ Authorization = "Bearer $token" }

# 显示消息 ID 和背景图 URL
$response.messages | Select-Object id, member_name, phone_image_url | Format-Table -AutoSize
```

**下载单个文件：**

```powershell
$token = Read-Host 'ACCESS_TOKEN'
$messageId = '12345'  # 从上面查询到的消息 ID

# 下载语音文件
Invoke-WebRequest `
  -Uri "https://YOUR_APP_NAME.fly.dev/v1/messages/$messageId/media/media" `
  -Headers @{ Authorization = "Bearer $token" } `
  -OutFile "voice_$messageId.wav"

# 下载来电背景图
Invoke-WebRequest `
  -Uri "https://YOUR_APP_NAME.fly.dev/v1/messages/$messageId/media/phone_image" `
  -Headers @{ Authorization = "Bearer $token" } `
  -OutFile "phone_image_$messageId.jpg"
```

**批量下载脚本：**

```powershell
$token = Read-Host 'ACCESS_TOKEN'

# 创建下载目录
New-Item -ItemType Directory -Force -Path "downloads"

# 查询最新 100 条语音消息
$response = Invoke-RestMethod `
  -Uri 'https://YOUR_APP_NAME.fly.dev/v1/messages?limit=100&type=audio' `
  -Headers @{ Authorization = "Bearer $token" }

Write-Host "找到 $($response.messages.Count) 条语音消息"

foreach ($msg in $response.messages) {
  $id = $msg.id
  $memberName = $msg.member_name
  $date = $msg.created_at
  
  Write-Host "下载: [$memberName] $date - ID: $id"
  
  try {
    # 下载语音文件
    $audioPath = "downloads/${id}_${memberName}.wav"
    Invoke-WebRequest `
      -Uri "https://YOUR_APP_NAME.fly.dev/v1/messages/$id/media/media" `
      -Headers @{ Authorization = "Bearer $token" } `
      -OutFile $audioPath
    
    # 下载来电背景图
    $imagePath = "downloads/${id}_${memberName}_phone.jpg"
    Invoke-WebRequest `
      -Uri "https://YOUR_APP_NAME.fly.dev/v1/messages/$id/media/phone_image" `
      -Headers @{ Authorization = "Bearer $token" } `
      -OutFile $imagePath
    
    Write-Host "  ✓ 成功" -ForegroundColor Green
  } catch {
    Write-Warning "  ✗ 失败: $_"
  }
  
  Start-Sleep -Milliseconds 100  # 避免请求过快
}

Write-Host "`n下载完成，文件保存在 downloads 目录"
```

**响应说明:**
- **HTTP 200**: 成功，返回文件二进制流
- **HTTP 404**: 消息 ID 不存在，或该消息没有对应的媒体类型，或 monitor 还未下载归档该文件
- **HTTP 401**: Bearer Token 缺失或无效
- **HTTP 429**: 触发限流（15 分钟内同一 IP 最多 100 次请求）

**常见问题：**

1. **下载失败 404 - 媒体未就绪**
   - monitor 可能还没有下载该媒体文件
   - 查看服务器日志确认 monitor 是否正常运行

2. **文件扩展名不对**
   - 归档时图片按文件头识别扩展名（jpg/png/gif/webp），音频和视频沿用 URL 扩展名；服务端按归档文件的扩展名返回 `Content-Type`
   - 可以根据响应头调整文件扩展名

3. **批量下载太慢**
   - 可以使用并发下载，但注意不要超过限流阈值
   - 推荐每次请求间隔至少 100ms

## 4. 推送验收

### 4.1 普通消息

1. 在客户端允许通知，打开应用一次。
2. 在设置页保存 Relay 地址和 Token，点击保存并注册推送。
3. 调用 `/v1/push/test-message`。
4. 检查 `successCount >= 1` 且 `failureCount == 0`。
5. 分别测试前台、后台、锁屏和厂商系统清理后的通知到达情况。

### 4.2 全屏来电

1. 在概览页确认通知和全屏来电权限。
2. OPPO、vivo、小米等设备开启自启动、后台运行、锁屏显示和忽略电池优化。
3. 调用 `/v1/push/test-call`。
4. 等待客户端完成音频下载；准备阶段不应出现来电通知。
5. 验证锁屏、后台和应用前台时的来电页、铃声、背景图、接听和拒接。

测试接口生成的消息 ID 以 `test-call-` 开头，不写入正式消息表或推送日志；服务端会从受认证保护的 `/v1/push/test-call-audio.wav` 提供内置短 WAV，客户端在下载完成前不会显示来电页面。旧版本遗留的 `test-`/`test_` 行会在服务端启动时清理。

### 4.3 历史补偿

1. 临时关闭客户端网络。
2. 等待 monitor 收到一条正式消息。
3. 恢复网络并打开客户端。
4. 确认客户端前台同步补齐消息，即使此前没有收到 FCM。

## 5. 生产故障排查

### API 返回 401 或 429

- 确认请求头为 `Authorization: Bearer ...`。
- 确认客户端 Token 与 Fly Secret `ACCESS_TOKEN` 完全一致。
- Secret 更新后等待部署完成。
- 429 表示同一 IP 触发 15 分钟限流窗口。

### 设备注册成功但收不到 FCM

- 确认 APK 使用了与 `com.nogirelay.app` 匹配的 `google-services.json`。
- 确认 Firebase Admin 服务账号属于同一 Firebase 项目。
- 查询 `/v1/devices`，确认 `last_seen_at` 更新。
- 检查 FCM `successCount`/`failureCount` 和 Fly 日志。
- 检查国产系统通知、自启动、后台运行和电池限制。

### 来电只有通知栏，没有自动全屏

- Android 14 及更高版本开启应用的“使用全屏通知”特殊权限。
- 确认 `incoming_calls_v2` 通知通道为高重要性。
- 锁屏显示、后台弹出和自启动权限必须允许。
- 来电页只会在语音下载成功后显示；准备阶段的低重要性服务状态不等同于来电。
- 屏幕处于使用状态时，系统或厂商可能优先显示 heads-up 通知；应用会同时尝试直接启动来电页，失败时保留通知兜底。

### 媒体或来电背景无法显示

- 确认 `PUBLIC_MEDIA_BASE_URL` 指向 monitor 的 8081 服务。
- 确认请求携带 Relay Bearer Token。
- 检查该消息 `phone_image_local_path` 指向的 `/data/nogi-media/objects/<sha256>.<扩展名>` 是否存在（升级前的旧消息仍位于 `/data/nogi-media/<消息ID>/`）。
- 确认消息字段 `phone_image_local_path` 已填充。
- 检查媒体下载日志和 100 MB 单文件限制。

### 官网监控停止更新

**症状:**
- 查看 monitor 日志中的 401、会话和订阅错误
- 日志中出现 `[NOGI_AUTH_SIGNED_OUT]` 或 `Nogi API 401`
- `/v2/update_token` 返回 400 后出现 `[NOGI_SESSION_UPDATE_REQUIRED]`
- 其他刷新错误达到阈值后出现 `[NOGI_AUTH_PAUSED]`
- `fetched=0` 持续出现或完全没有轮询日志

**排查步骤:**

1. **检查会话文件是否存在:**
   ```powershell
   flyctl ssh console -a nogi-relay
   ls -lh /data/nogi-browser-state.json
   ```
   确认文件存在且权限为 `-rw-------` (0600)

2. **查看日志确认失败原因:**
   ```powershell
   flyctl logs --app nogi-relay
   ```
   
   关注以下错误信息:
   - `官网页面没有发出带 Authorization 的 API 请求` - 会话未登录或已过期
   - `Nogi API 401` - Token 无效,需要刷新会话
   - `No active subscribed groups found` - 账号无有效订阅
   - `连续失败 X/3 次` - 接近默认告警阈值,需尽快处理

3. **检查是否在其他设备登录过:**
   - 官网网页端登录是互斥的
   - 如果在其他浏览器登录过,服务器会话会失效
   - 移动端 App 登录不影响服务器会话

4. **重新生成并上传会话文件:**
   
   参考 [3.4 官网会话过期处理](#34-官网会话过期处理) 完整步骤:
   
   ```powershell
   # 本地生成新会话
   cd .\server
   npm run bootstrap:browser
   
   # 上传到服务器
   node upload-session.js .\nogi-browser-state.json https://YOUR_APP_NAME.fly.dev YOUR_ACCESS_TOKEN
   ```
   
   上传并激活成功后:
   - monitor 自动检测目录中的原子文件替换
   - 完全重启浏览器实例
   - 重置 `/v2/update_token` 连续失败计数器并清除认证暂停状态
   - 官网 API 验证通过并记录 `active`
   - 无需手动重启服务

5. **观察恢复情况:**
   ```powershell
   flyctl logs --app nogi-relay
   ```
   
   成功恢复应该看到:
   ```text
    检测到外部会话文件更新,准备重载浏览器上下文...
    前端会话验证成功
    ✓ 浏览器会话已激活并验证: <version>
    Nogi browser monitor poll complete: groups=X, fetched=X, stored=X, pushed=X
   ```

6. **确认账号有有效成员订阅:**
   - 登录官网检查订阅状态
   - 确认至少有一个成员的订阅处于激活状态

**预防措施:**
- 定期检查监控日志,关注失败次数
- 看到连续认证失败时应及时准备新会话文件
- 避免在其他浏览器登录官网
- 建议每月主动更新一次会话文件

### 服务器内存不足 (OOM)

**症状:**
- 日志中出现 `Out of memory: Killed process` 或 `OOM killed`
- 服务反复重启
- 健康检查失败
- 浏览器启动或会话刷新时崩溃

**原因:**
- Chromium + Node.js 内存占用超过机器限制 (1GB)
- 会话刷新时内存激增
- 内存泄漏累积

**当前优化措施:**

1. **使用轻量浏览器**
   - 强制使用 `chromium-headless-shell` (比标准版轻 30-40%)
   
2. **Chromium 启动参数优化**
   ```
   --disable-gpu                    禁用 GPU 渲染
   --disable-extensions             禁用扩展
   --single-process                 单进程模式
   --js-flags=--max-old-space-size=256  限制 V8 堆内存
   ```

3. **主动内存监控**
   - RSS 超过 850MB (85%) 时自动重启浏览器
   - 每 10 次循环记录内存状态
   - 关闭浏览器后强制垃圾回收

**排查步骤:**

1. **查看内存使用日志:**
   ```powershell
   flyctl logs --app nogi-relay | Select-String "内存|OOM|RSS"
   ```
   
   正常日志示例:
   ```text
   内存状态: RSS=650MB, Heap=180MB/256MB
   ```
   
   异常日志示例:
   ```text
   内存使用过高 (RSS: 920MB, Heap: 240MB), 触发浏览器重启
   Out of memory: Killed process
   ```

2. **检查当前机器配置:**
   ```powershell
   flyctl scale show --app nogi-relay
   ```
   
   确认:
   - Memory: 1024 MB
   - CPUs: 1 shared

3. **如需增加内存 (需付费):**
   ```powershell
   # 升级到 2GB (超出免费额度)
   flyctl scale memory 2048 -a nogi-relay
   ```

**预防措施:**
- 监控日志中的内存状态
- RSS 经常超过 800MB 时考虑优化或升级
- 定期重启可以清理累积的内存泄漏

**内存使用参考:**
- 正常运行: 500-700 MB
- 刷新会话时: 峰值可达 800-900 MB
- 触发主动重启: > 850 MB
- OOM 阈值: ~1000 MB

### 监控健康状态检查

**日常健康检查命令:**

```powershell
# 检查服务状态
flyctl status --app nogi-relay

# 查看最近日志
flyctl logs --app nogi-relay --no-tail

# 检查 API 健康
Invoke-RestMethod 'https://YOUR_APP_NAME.fly.dev/health'
```

**正常运行的标志:**

1. **轮询日志定期出现 (每分钟):**
   ```text
   Nogi browser monitor poll complete: groups=1, fetched=3, stored=2, pushed=2
   ```

2. **访问令牌按需刷新：** 只有临近过期或 401 恢复时才会出现 `Nogi browser session supplied a refreshed access token`；有效 Token 不再触发定时页面验证。

3. **浏览器自动重启（进程 RSS 超过 850 MB；默认无定时重启）:**
   ```text
   Nogi browser monitor restarting browser context to release memory
   ```

4. **健康检查响应正常:**
   ```json
   {
     "status": "ok",
     "timestamp": "2026-09-09T...",
     "uptime": 86400
   }
   ```

**异常状态识别:**

| 症状 | 可能原因 | 处理方法 |
|------|---------|---------|
| 日志中出现 `[NOGI_SESSION_UPDATE_REQUIRED]` | 会话已进入 `signedOut` | 上传新会话文件 |
| 出现 `[NOGI_AUTH_PAUSED]` | 会话已确认失效，官网刷新失败 | 上传新会话文件；无需重启服务 |
| `fetched=0` 持续多次 | 会话过期或无新消息 | 检查会话状态和订阅 |
| 无轮询日志输出超过 5 分钟 | 进程卡死或崩溃 | 检查机器状态,必要时重启 |
| 健康检查超时或 502 | 事件循环阻塞 | 查看日志,可能需要上传新会话或重启 |
| `No active subscribed groups found` | 账号无有效订阅 | 检查官网订阅状态 |

**监控恢复时间线:**

上传新会话后的预期恢复流程（具体耗时取决于官网响应）：
```
T+0s   : API 原子保存会话，状态为 pending
T+1s   : monitor 检测到目录变化，状态为 activating
T+2s   : monitor 开始重载浏览器会话
T+5s   : 旧浏览器实例关闭
T+10s  : 新浏览器实例打开并加载上传快照
T+15s  : 官网 API 验证成功，状态变为 active，上传脚本返回成功
T+60s内: 下一次正常轮询完成
```

上传脚本最多等待 90 秒。如果超时或返回失败，先运行 `upload-session.js --status` 查看 `activationStatus` 和 `activationError`，再检查日志。

### 设备管理

**查询所有注册设备:**

```powershell
$token = Read-Host 'ACCESS_TOKEN'
Invoke-RestMethod -Uri 'https://YOUR_APP_NAME.fly.dev/v1/devices' -Headers @{ Authorization = "Bearer $token" } | ConvertTo-Json -Depth 6
```

返回示例:
```json
{
  "success": true,
  "devices": [
    {
      "id": 1,
      "platform": "android",
      "label": "Pixel 8 Pro",
      "last_seen_at": "2026-09-08T09:30:15.123Z",
      "created_at": "2026-09-01T12:00:00.000Z"
    }
  ]
}
```

**删除指定设备:**

```powershell
$token = Read-Host 'ACCESS_TOKEN'
$deviceId = Read-Host '设备 ID'
Invoke-RestMethod -Uri "https://YOUR_APP_NAME.fly.dev/v1/devices/$deviceId" -Method DELETE -Headers @{ Authorization = "Bearer $token" }
```

**批量清理长期未活跃设备:**

使用 SQL 查询找出超过 90 天未活跃的设备:
```sql
SELECT id, platform, label, last_seen_at, created_at
FROM devices
WHERE last_seen_at < NOW() - INTERVAL '90 days'
ORDER BY last_seen_at;
```

删除指定设备后,客户端会在下次启动时自动重新注册。如果设备的 FCM token 失效,推送会失败但设备记录不会自动删除,需要手动清理。

**设备 token 更新:**

客户端检测到 FCM token 变化时会自动调用 `POST /v1/devices` 更新。如果同一 FCM token 已存在,API 会更新 `last_seen_at` 和 `label`,不会创建重复记录。

## 6. 日志、备份和恢复

### 日志

```powershell
flyctl logs --app nogi-relay --no-tail
flyctl machine status MONITOR_MACHINE_ID -a nogi-relay
```

monitor 会把错误和告警追加到持久化卷的 `/data/nogi-logs/errors-YYYY-MM-DD.jsonl`，并将结构化错误（堆栈、错误码、消息 ID、进程和机器信息）写入 PostgreSQL 的 `error_logs` 表。数据库暂时不可用时，JSONL 文件仍会保留；可用以下查询检查最近错误：

```sql
SELECT created_at, scope, message, error_code, context
FROM error_logs
ORDER BY created_at DESC
LIMIT 100;
```

不要在日志中输出官网 Token、Firebase 私钥、FCM Token 或浏览器 state 内容。

### 数据库备份

使用 PostgreSQL 官方工具定期备份：

```powershell
$env:PGPASSWORD = 'YOUR_DATABASE_PASSWORD'
pg_dump $env:DATABASE_URL --format=custom --file .\backup\nogi-relay-$(Get-Date -Format yyyyMMdd-HHmm).dump
```

备份目录不要提交 Git。恢复前停止 monitor，避免恢复期间继续写入消息：

```powershell
pg_restore --clean --if-exists --dbname $env:DATABASE_URL .\backup\YOUR_BACKUP.dump
```

### 媒体卷备份

数据库备份不包含 `/data/nogi-media`。使用 Fly Volume Snapshot 或同等方式备份持久卷，恢复时确保 `MEDIA_STORAGE_DIR` 与 `PUBLIC_MEDIA_BASE_URL` 一致。

## 7. 国产系统推送扩展

当前实现只接入 FCM。若需要提高应用被系统清理后的到达率，可增加：

```text
OPPO / ColorOS       -> OPPO PUSH / HeyTap Push
vivo / OriginOS      -> vivo Push
小米 / HyperOS       -> MiPush
其他支持 Google 的设备 -> FCM
```

接入厂商通道时，设备表应增加 `push_provider`、`provider_token` 和设备能力字段。厂商通知只携带消息 ID，客户端打开或回到前台后从 Relay 同步完整消息；不要无条件同时发送 FCM 和厂商通知，避免重复通知。

## 8. 限制与发布前检查

- 只实现 FCM，尚未接入 OPPO、vivo、小米或华为系统推送。
- API 使用单个共享 Token，没有用户级身份和权限体系。
- 默认构建不预置服务器地址、也不注入 Relay Token（`BuildConfig.DEFAULT_RELAY_URL` 与 `RELAY_ACCESS_TOKEN` 默认为空）；只有在未提交的 `local.properties` 中提供 `relay.baseUrl` / `relay.access.token` 时才会被编译进包内，带内置地址或令牌的调试包不适合公开分发。
- 服务端消息列表没有总数、游标和完整参数范围校验。
- FCM 无效设备 Token 需要运维清理。
- 客户端历史同步没有固定页数上限，历史很大时会连续请求并可能触发 `/v1/*` 15 分钟 100 次的限流。
- 媒体归档没有保留策略或垃圾回收；卷写满后 `media.archive` 会失败，消息仍会入库但媒体地址回退到上游 URL。
- 媒体对象按内容哈希共享，多个消息行指向同一文件；未来若增加删除功能必须先做引用计数。
- `test-call` 使用服务端内置短 WAV，只用于验证下载、推送和来电界面，不代表正式成员语音内容。
- Release APK 需要正式签名后才能对外发布。

发布前至少完成：数据库备份、媒体卷备份、健康检查、Token 验证、普通推送、全屏来电、历史补偿和旧测试数据清理验证。

## 9. 许可证与使用范围

服务端代码和依赖按各自许可证使用，项目服务端依赖声明 MIT。乃木坂46官方图片、音频、视频、商标和账号内容的再分发权不因代码许可证自动获得，部署和使用须遵守相关服务条款、订阅规则和当地法律。
