ARG PYTHON_VERSION=3.14.7

FROM python:${PYTHON_VERSION}-alpine

WORKDIR /app

# yt-dlp 版本唯一来源是 deploy/versions.env(经 compose build args 注入);
# 此默认仅用于脱离编排直接 docker build 的场景。
ARG YT_DLP_VERSION=2026.07.04
# GIT_VERSION 由编排层传入(仓库 git describe),写入 /app/version 供 /health
# 暴露;未传时为 "dev"。
ARG GIT_VERSION=dev
RUN echo "${GIT_VERSION}" > /app/version

RUN pip install --no-cache-dir --disable-pip-version-check "yt-dlp==${YT_DLP_VERSION}" \
    && addgroup -S app \
    && adduser -S -G app app

COPY server.py .

LABEL org.opencontainers.image.revision="${GIT_VERSION}"
LABEL org.opencontainers.image.version.yt-dlp="${YT_DLP_VERSION}"

ENV HOST=0.0.0.0 \
    PORT=8790 \
    VOD_EXTRACTOR_BIN=yt-dlp \
    VOD_EXTRACTOR_TIMEOUT_MS=30000 \
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
