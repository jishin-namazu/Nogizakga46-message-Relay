# Nogi Relay Server 文档入口

服务端开发、接口和本地运行说明请查看项目根目录的 [DEVELOPMENT.md](../DEVELOPMENT.md)。

生产环境变量、Fly.io 部署、官网会话上传、推送验收和运维说明请查看 [DEPLOYMENT.md](../DEPLOYMENT.md)。

服务端代码入口：

- `src/index.js`：REST API、健康检查和数据库兼容迁移。
- `src/monitor/index.js`：监控进程入口，启动浏览器会话监控和媒体服务。
- `src/monitor/nogi-browser.js`：浏览器会话监控模式，维护官网登录；启动、新订阅或会话更新时导入 `past_messages` 并遍历全部 timeline continuation 页面，之后轮询最新消息。
- `start-all.sh`：生产容器启动编排；API `/health` 成功后才启动 monitor、媒体服务和 Chromium。
- `src/services/browser-session.js`：会话文件原子写入、内容版本和激活状态文件。
- `upload-session.js`：上传会话并等待 monitor 完成官网 API 验证。
- `src/services/media.js`：媒体和来电背景归档。
- `database/schema.sql`：当前数据库初始化脚本。

认证状态摘要：`/v2/update_token` 返回 `400` 时立即进入 `signedOut` 并停止 Chromium/轮询；等待新会话期间每 5 分钟输出一次 `[NOGI_SESSION_UPDATE_REQUIRED]`。其他刷新错误达到配置阈值后进入 `authPaused`。新会话只有通过官网 API 验证后才恢复轮询。
