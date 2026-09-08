# Dockerfile for Fly.io deployment

FROM mcr.microsoft.com/playwright:v1.62.1-noble

# The base image includes the Playwright-matched Chromium/headless shell.
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

# 设置工作目录
WORKDIR /app

# 复制 package 文件
COPY server/package*.json ./

# 安装依赖
RUN npm ci --only=production

# 复制应用代码
COPY server/ ./

# 设置启动脚本权限
RUN chmod +x start-all.sh

# 创建非 root 用户
RUN chown -R pwuser:pwuser /app

USER pwuser

# 暴露端口
EXPOSE 8080 8081

# 启动命令 - 同时运行 API 服务和 monitor
CMD ["sh", "start-all.sh"]
