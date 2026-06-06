# 简单的开发 Dockerfile:基于 node 22-alpine(LTS,体积小)
# tsx watch 用于开发期热重载;OrbStack 让容器自动走宿主代理(用户无需配置)。
FROM node:22-alpine

WORKDIR /app

# yt-dlp 只用于解析普通视频点播播放地址,不下载媒体文件。
RUN apk add --no-cache yt-dlp

# 先复制 lock+package 以充分利用层缓存
COPY package.json package-lock.json* ./
RUN npm ci --no-audit --no-fund

# 应用代码:tsx watch 会监听 src/ 变化,因此通过 volume 挂载源码而不是 COPY,
# 这样开发期改 src/ 不需要重建镜像。下方 CMD 即基于 /app/src 启动。
EXPOSE 8787
ENV HOST=0.0.0.0 PORT=8787 VOD_EXTRACTOR_BIN=yt-dlp BACKEND_CACHE_DIR=/cache BACKEND_CACHE_MAX_BYTES=53687091200

VOLUME /cache

CMD ["npm", "run", "dev"]
