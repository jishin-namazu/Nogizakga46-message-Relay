# Nogi Relay 部署文档

本文件面向部署和维护人员,覆盖生产环境配置、Fly.io 部署、官网会话管理、推送验证和故障排查。

开发环境配置、代码结构、API 细节、环境变量完整列表请查看 [DEVELOPMENT.md](DEVELOPMENT.md)。

## 1. 生产环境必需配置

部署到 Fly.io 前必须配置以下 Secret:

| Secret | 用途 |
| --- | --- |
| `DATABASE_URL` | PostgreSQL 连接字符串 |
| `ACCESS_TOKEN` | API 认证令牌 |
| `FIREBASE_PROJECT_ID` | Firebase 项目 ID |
| `FIREBASE_PRIVATE_KEY_BASE64` | Base64 编码的 Firebase Admin JSON |

完整的环境变量列表和说明请查看 [DEVELOPMENT.md](DEVELOPMENT.md)。

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
Invoke-RestMethod -Method Post -Uri 'https://nogi-relay.fly.dev/init-db' -Headers @{ Authorization = "Bearer $token" }
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
node upload-session.js .\nogi-browser-state.json https://nogi-relay.fly.dev YOUR_ACCESS_TOKEN
```

上传成功后，monitor 会自动检测文件变化并重新加载会话，无需手动重启。

**备用方式(使用 SSH):**

如果 API 方式不可用，可以通过 SSH 直接上传到持久卷：

```powershell
Set-Location ..
flyctl status -a nogi-relay
flyctl ssh sftp put .\server\nogi-browser-state.json /data/nogi-browser-state.json -a nogi-relay --machine MONITOR_MACHINE_ID --mode 0600
```

上传后 monitor 会自动检测文件变化并重新加载，无需重启。会话过期后重新生成并上传；不要把 `nogi-browser-state.json` 提交到 Git。

### 2.5 部署和回滚

```powershell
flyctl deploy --remote-only --app nogi-relay
flyctl status --app nogi-relay
Invoke-RestMethod 'https://nogi-relay.fly.dev/health'
flyctl logs --app nogi-relay --no-tail
```

需要回滚时先列出历史版本，再选择已验证的镜像版本：

```powershell
flyctl releases --app nogi-relay
flyctl deploy --app nogi-relay --image registry.fly.io/nogi-relay:IMAGE_TAG
```

### 2.6 媒体卷

正式图片、语音、视频、缩略图和来电背景存储在 `/data/nogi-media/<消息ID>/`：

```text
media.<扩展名>
thumbnail.<扩展名>
phone_image.<扩展名>
```

来电背景通过 `/v1/messages/:id/media/phone_image` 提供，和其他媒体一样需要 Relay Bearer Token。持久卷不能跨应用自动复制，变更应用或区域前应先备份。

## 3. 生产验证

### 3.1 API 健康检查

```powershell
Invoke-RestMethod 'https://nogi-relay.fly.dev/health'
```

正常返回 `status: ok`。Fly 机器状态应显示 `app` 和 `monitor` 为 `started`，相应健康检查通过。

### 3.2 验证 Relay API Token

```powershell
$token = Read-Host 'ACCESS_TOKEN'
try {
  Invoke-RestMethod -Uri 'https://nogi-relay.fly.dev/v1/devices' -Headers @{ Authorization = "Bearer $token" } | ConvertTo-Json -Depth 6
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

常见失效信号：

- `Nogi API 401`：官网短期 Token 失效且页面刷新未恢复。
- 页面没有带 Authorization 的请求：浏览器状态未登录或已过期。
- `No active subscribed groups found`：没有有效订阅或账号配置不匹配。
- 浏览器反复重启：重新生成并上传 state 文件。

不要只解析 JWT 的 `exp` 判断官网会话，真实 API 请求成功才是最终判断。

### 3.4 官网会话过期处理

**判断会话过期的信号:**

1. **日志中出现 401 错误:**
   ```text
   Nogi API 401: Unauthorized
   Error fetching member timeline
   ```

2. **monitor 停止获取新消息:**
   - 日志中 `fetched=0` 持续出现
   - 或完全没有轮询日志输出

3. **浏览器状态文件失效:**
   - 日志显示浏览器反复重启
   - 页面没有带 Authorization 的请求

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
   node upload-session.js .\nogi-browser-state.json https://nogi-relay.fly.dev YOUR_ACCESS_TOKEN
   ```
   
   上传成功后,monitor 会自动重新加载新会话,无需手动重启。

4. 观察日志确认恢复正常:
   ```powershell
   flyctl logs --app nogi-relay
   ```

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
node .\server\upload-session.js --status https://nogi-relay.fly.dev YOUR_ACCESS_TOKEN
```

或使用 PowerShell 直接调用 API:
```powershell
$token = 'YOUR_ACCESS_TOKEN'
Invoke-RestMethod `
  -Uri 'https://nogi-relay.fly.dev/v1/admin/browser-session' `
  -Headers @{ Authorization = "Bearer $token" }
```

### 3.5 媒体文件访问
Invoke-RestMethod `
  -Uri 'https://nogi-relay.fly.dev/v1/admin/browser-session/status' `
  -Headers @{ Authorization = "Bearer $token" }
```

**安全注意事项:**
- 会话文件包含完整登录凭证,必须妥善保管
- 不要提交到 Git 或分享给他人
- 定期轮换会话文件(建议每月至少一次)

### 3.5 获取服务器媒体文件

**API 端点:**
```
GET /v1/messages/:id/media/:kind
Authorization: Bearer YOUR_ACCESS_TOKEN
```

**kind 参数说明:**
- `media`: 原始图片、语音或视频文件
- `thumbnail`: 图片或视频的缩略图
- `phone_image`: 语音来电的背景图片

**获取语音文件示例:**
```powershell
$token = Read-Host 'ACCESS_TOKEN'
$messageId = 'abc123'
Invoke-WebRequest `
  -Uri "https://nogi-relay.fly.dev/v1/messages/$messageId/media/media" `
  -Headers @{ Authorization = "Bearer $token" } `
  -OutFile "voice_$messageId.wav"
```

**获取来电背景图示例:**
```powershell
$token = Read-Host 'ACCESS_TOKEN'
$messageId = 'abc123'
Invoke-WebRequest `
  -Uri "https://nogi-relay.fly.dev/v1/messages/$messageId/media/phone_image" `
  -Headers @{ Authorization = "Bearer $token" } `
  -OutFile "phone_image_$messageId.jpg"
```

**批量下载消息媒体:**
```powershell
$token = Read-Host 'ACCESS_TOKEN'
$messages = Invoke-RestMethod `
  -Uri 'https://nogi-relay.fly.dev/v1/messages?limit=100&type=audio' `
  -Headers @{ Authorization = "Bearer $token" }

foreach ($msg in $messages) {
  $id = $msg.id
  try {
    Invoke-WebRequest `
      -Uri "https://nogi-relay.fly.dev/v1/messages/$id/media/media" `
      -Headers @{ Authorization = "Bearer $token" } `
      -OutFile "downloads/$id.wav"
    Write-Host "Downloaded: $id"
  } catch {
    Write-Warning "Failed: $id - $_"
  }
}
```

**响应说明:**
- 成功: HTTP 200,返回文件二进制流,Content-Type 根据文件类型设置
- 未找到: HTTP 404,消息 ID 不存在或该消息没有对应的媒体类型
- 未授权: HTTP 401,Bearer Token 缺失或无效
- 媒体未就绪: HTTP 404,monitor 还未下载归档该媒体文件

**存储位置:**
所有媒体文件存储在 monitor 机器的 `/data/nogi-media/<消息ID>/` 目录:
```text
/data/nogi-media/abc123/
  ├── media.wav           # 原始语音文件
  └── phone_image.jpg     # 来电背景图
```

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
- 检查 `/data/nogi-media/<消息ID>/phone_image.*` 是否存在。
- 确认消息字段 `phone_image_local_path` 已填充。
- 检查媒体下载日志和 100 MB 单文件限制。

### 官网监控停止更新

- 查看 monitor 日志中的 401、会话和订阅错误。
- 确认 `/data/nogi-browser-state.json` 存在且权限为 0600。
- 重新生成并上传 state (monitor 会自动重新加载)。
- 确认账号仍有有效成员订阅。

### 设备管理

**查询所有注册设备:**

```powershell
$token = Read-Host 'ACCESS_TOKEN'
Invoke-RestMethod -Uri 'https://nogi-relay.fly.dev/v1/devices' -Headers @{ Authorization = "Bearer $token" } | ConvertTo-Json -Depth 6
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
Invoke-RestMethod -Uri "https://nogi-relay.fly.dev/v1/devices/$deviceId" -Method DELETE -Headers @{ Authorization = "Bearer $token" }
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
- APK 默认 Token 会编译进包内，不适合公开分发。
- 服务端消息列表没有总数、游标和完整参数范围校验。
- FCM 无效设备 Token 需要运维清理。
- `test-call` 使用服务端内置短 WAV，只用于验证下载、推送和来电界面，不代表正式成员语音内容。
- Release APK 需要正式签名后才能对外发布。

发布前至少完成：数据库备份、媒体卷备份、健康检查、Token 验证、普通推送、全屏来电、历史补偿和旧测试数据清理验证。

## 9. 许可证与使用范围

服务端代码和依赖按各自许可证使用，项目服务端依赖声明 MIT。乃木坂46官方图片、音频、视频、商标和账号内容的再分发权不因代码许可证自动获得，部署和使用须遵守相关服务条款、订阅规则和当地法律。
