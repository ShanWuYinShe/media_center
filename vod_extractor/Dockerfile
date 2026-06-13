ARG PYTHON_VERSION=3.13

FROM python:${PYTHON_VERSION}-alpine

WORKDIR /app

ARG YT_DLP_VERSION=2026.3.17

RUN pip install --no-cache-dir --disable-pip-version-check "yt-dlp==${YT_DLP_VERSION}" \
    && addgroup -S app \
    && adduser -S -G app app

COPY server.py .

ENV HOST=0.0.0.0 \
    PORT=8790 \
    VOD_EXTRACTOR_BIN=yt-dlp \
    VOD_EXTRACTOR_TIMEOUT_MS=30000 \
    VOD_EXTRACTOR_MAX_OUTPUT_BYTES=20971520 \
    VOD_EXTRACTOR_MAX_CONCURRENT=2 \
    VOD_EXTRACTOR_EXTRACTOR_ARGS="" \
    VOD_EXTRACTOR_COOKIES_FILE=""

EXPOSE 8790

USER app
CMD ["python", "/app/server.py"]
