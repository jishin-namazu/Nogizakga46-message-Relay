# Nogi Relay Server 文档入口

服务端开发、接口和本地运行说明请查看项目根目录的 [DEVELOPMENT.md](../DEVELOPMENT.md)。

生产环境变量、Fly.io 部署、官网会话上传、推送验收和运维说明请查看 [DEPLOYMENT.md](../DEPLOYMENT.md)。

服务端代码入口：

- `src/index.js`：REST API、健康检查和数据库兼容迁移。
- `src/monitor/index.js`：监控进程入口，启动浏览器会话监控和媒体服务。
- `src/monitor/nogi-browser.js`：浏览器会话监控模式，维护官网登录和轮询消息。
- `src/services/browser-session.js`：会话文件原子写入、内容版本和激活状态文件。
- `upload-session.js`：上传会话并等待 monitor 完成官网 API 验证。
- `src/services/media.js`：媒体和来电背景归档。
- `database/schema.sql`：当前数据库初始化脚本。
