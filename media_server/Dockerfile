FROM golang:1.24-alpine AS builder

WORKDIR /app

COPY go.mod ./
RUN go mod download

COPY *.go ./
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o /out/media_server .

FROM alpine:3.22

WORKDIR /app

RUN apk add --no-cache ca-certificates wget \
    && adduser -D -u 10001 appuser \
    && mkdir -p /config /cache \
    && chown -R appuser:appuser /app /config /cache

ENV HOST=0.0.0.0 \
    PORT=8787 \
    CONTENT_CATEGORIES_FILE=/config/contentCategories.json

COPY --from=builder /out/media_server /app/media_server

VOLUME ["/config", "/cache"]
EXPOSE 8787

USER appuser
CMD ["/app/media_server"]
