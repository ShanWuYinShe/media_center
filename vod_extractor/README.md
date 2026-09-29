# VOD Extractor

Internal HTTP wrapper around `yt-dlp` for resolving public VOD playback metadata.

`media_server` calls `POST /extract` on the internal Docker network (via `GET /api/v1/providers/youtube/resolve`, see `media_server/youtube.go`) when resolving YouTube playback URLs for the app. Keeping `yt-dlp` here avoids shipping Python and extractor dependencies in the main media API image.

**Deployment requirement**: YouTube serves bot-check failures ("Sign in to confirm you're not a bot") to datacenter IPs on every player client, so server deployments MUST provide a YouTube cookies file (see `VOD_EXTRACTOR_COOKIES_FILE` below). Without cookies, every extraction fails regardless of `VOD_EXTRACTOR_EXTRACTOR_ARGS`.

## API

- `GET /health`
- `POST /extract` with JSON body `{ "url": "https://..." }`
- `POST /search` with JSON body `{ "query": "...", "limit": 20 }` — YouTube search via
  yt-dlp `ytsearch{N}` with `--flat-playlist` (search results page only, no per-video
  extraction). Shares the Data-API-free public frontend path with regular viewers, so
  there is no quota; costs a few seconds per call and requires cookies just like /extract.
  Response is the raw yt-dlp playlist JSON (`entries[]`).

## Runtime limits

- `VOD_EXTRACTOR_TIMEOUT_MS` caps each yt-dlp run.
- `VOD_EXTRACTOR_MAX_OUTPUT_BYTES` caps stdout retained from yt-dlp.
- `VOD_EXTRACTOR_MAX_CONCURRENT` caps concurrent yt-dlp processes.
- `VOD_EXTRACTOR_EXTRACTOR_ARGS` optionally passes one `--extractor-args` value to yt-dlp without using a shell, for example `youtube:player_client=android` or a YouTube PO token argument when available.
- `VOD_EXTRACTOR_COOKIES_FILE` optionally points to a Netscape cookies file for yt-dlp. In the `deploy/` compose deployment, place YouTube cookies at `deploy/data/vod/youtube-cookies.txt`; the container mounts that directory read-only at `/config`, and `/config/youtube-cookies.txt` is used automatically when the file exists. If `VOD_EXTRACTOR_COOKIES_FILE` is set explicitly, the file must exist and be readable (otherwise requests fail with 503). `GET /health` exposes a `cookies` object (`configured`/`present`/`readable`/`age_seconds`) for expiry alerting.
- `GET /health` also exposes a `failures` object (`total`/`counts`/`last_reason`/`last_at`): yt-dlp failures are classified by stderr signatures (`bot_check` = cookies expired / bot check, `rate_limited`, `unavailable`, `geo_blocked`, `timeout`, ...). A rising `bot_check` count with a fresh cookies file means the session itself has expired — replace the cookies file; the 502 error body of `/extract` and `/search` carries the same `reason` field for callers to log.
- `VOD_EXTRACTOR_ALLOWED_EXTRACT_HOSTS` optionally overrides the `/extract` target-host allowlist (comma-separated). Default: YouTube domains only (`youtube.com`, `www.youtube.com`, `youtu.be`, `m.youtube.com`, `music.youtube.com`) — this service currently only serves the YouTube provider, and the allowlist keeps a hijacked/misused `/extract` from being pointed at arbitrary hosts.
