# VOD Extractor

Internal HTTP wrapper around `yt-dlp` for resolving public VOD playback metadata.

`media_server` calls `POST /extract` on the internal Docker network (via `GET /api/providers/youtube/resolve`, see `media_server/youtube.go`) when resolving YouTube playback URLs for the app. Keeping `yt-dlp` here avoids shipping Python and extractor dependencies in the main media API image.

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
- `VOD_EXTRACTOR_COOKIES_FILE` optionally points to a Netscape cookies file for yt-dlp. In the workwork compose deployment, place YouTube cookies at `.wework/vod_extractor/youtube-cookies.txt`; the container mounts that directory read-only at `/config`, and `/config/youtube-cookies.txt` is used automatically when the file exists. If `VOD_EXTRACTOR_COOKIES_FILE` is set explicitly, the file must exist and be readable.
