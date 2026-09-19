# GO_VERSION 需与 .github/workflows/ci.yml 的 setup-go go-version 保持一致
ARG GO_VERSION=1.26.5
ARG ALPINE_VERSION=3.24.1

FROM golang:${GO_VERSION}-alpine AS builder

WORKDIR /app

ARG GOPROXY
ENV GOPROXY=${GOPROXY:-https://goproxy.cn,direct}

COPY go.mod go.sum ./
RUN go mod download

COPY *.go ./
# TARGETARCH 由 BuildKit 自动注入(buildx --platform),arm64 主机也能构建;
# 无注入时回退 amd64。
ARG TARGETARCH=amd64
# GIT_VERSION 由编排层传入(仓库 git describe),烙进二进制并经 /health 与
# 启动日志暴露;未传时二进制内为 "dev"。
ARG GIT_VERSION=dev
RUN CGO_ENABLED=0 GOOS=linux GOARCH=${TARGETARCH} go build -trimpath \
    -ldflags="-s -w -X main.version=${GIT_VERSION}" -o /out/media_server .

FROM alpine:${ALPINE_VERSION}

WORKDIR /app

RUN sed -i 's#dl-cdn.alpinelinux.org#mirrors.aliyun.com#g' /etc/apk/repositories \
    && apk add --no-cache ca-certificates wget \
    && adduser -D -u 10001 appuser \
    && mkdir -p /config /cache /app/data \
    && chown -R appuser:appuser /app /config /cache

ENV HOST=0.0.0.0 \
    PORT=8787 \
    CONTENT_CATEGORIES_FILE=/config/contentCategories.json \
    MEDIA_SERVER_CATALOG_DB=/app/data/catalog.db

COPY --from=builder /out/media_server /app/media_server

# OCI 标准 Revision 标签:docker image inspect 可查构建源版本。
# ARG 按阶段作用域:最终阶段需重新声明才能用于 LABEL。
ARG GIT_VERSION=dev
LABEL org.opencontainers.image.revision="${GIT_VERSION}"

VOLUME ["/config", "/cache", "/app/data"]
EXPOSE 8787

# 编排层感知服务假死(Go 死锁/卡住):/health 端点在 main.go ServeHTTP 提供。
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8787/health || exit 1

USER appuser
CMD ["/app/media_server"]
