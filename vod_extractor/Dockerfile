ARG PYTHON_VERSION=3.14.7

FROM python:${PYTHON_VERSION}-alpine

WORKDIR /app

# yt-dlp 版本唯一来源是 deploy/versions.env(经 compose build args 注入);
# 此默认仅用于脱离编排直接 docker build 的场景,因此必须与 versions.env
# 保持同值——否则裸 docker build 会静默装入旧版 yt-dlp,而 yt-dlp 正是
# YouTube 风控对抗最关键的一环。
ARG YT_DLP_VERSION=2026.10.26
# YouTube 签名/n challenge 求解需要 JS runtime(Deno)与 yt-dlp-ejs；
# PO Token 由 bgutil-ytdlp-pot-provider 插件向独立 bgutil 容器请求。
ARG DENO_VERSION=2.7.4-r2
ARG BGUTIL_POT_VERSION=2.0.2
# GIT_VERSION 由编排层传入(仓库 git describe),写入 /app/version 供 /health
# 暴露;未传时为 "dev"。
ARG GIT_VERSION=dev
RUN echo "${GIT_VERSION}" > /app/version

RUN apk add --no-cache "deno=${DENO_VERSION}" \
    && pip install --no-cache-dir --disable-pip-version-check \
        "yt-dlp[default]==${YT_DLP_VERSION}" \
        "bgutil-ytdlp-pot-provider==${BGUTIL_POT_VERSION}" \
    && addgroup -S app \
    && adduser -S -G app app

COPY server.py .

LABEL org.opencontainers.image.revision="${GIT_VERSION}"
LABEL org.opencontainers.image.version.yt-dlp="${YT_DLP_VERSION}"
LABEL org.opencontainers.image.version.bgutil-pot="${BGUTIL_POT_VERSION}"

ENV HOST=0.0.0.0 \
    PORT=8790 \
    VOD_EXTRACTOR_BIN=yt-dlp \
    VOD_EXTRACTOR_TIMEOUT_MS=60000 \
    VOD_EXTRACTOR_MAX_OUTPUT_BYTES=20971520 \
    VOD_EXTRACTOR_MAX_CONCURRENT=2 \
    VOD_EXTRACTOR_EXTRACTOR_ARGS="" \
    VOD_EXTRACTOR_COOKIES_FILE="" \
    VOD_EXTRACTOR_ALLOWED_EXTRACT_HOSTS="" \
    VOD_EXTRACTOR_MAX_CONNECTIONS="32"

EXPOSE 8790

# 编排层感知服务假死(yt-dlp 卡死/Python 死锁):/health 端点在 server.py 提供。
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8790/health || exit 1

USER app
CMD ["python", "/app/server.py"]
