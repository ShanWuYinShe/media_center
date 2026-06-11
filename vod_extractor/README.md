# VOD Extractor

Internal HTTP wrapper around `yt-dlp` for resolving public VOD playback metadata.

`media_server` calls `POST /extract` on the internal Docker network. Keeping `yt-dlp` here avoids shipping Python and extractor dependencies in the main media API image.

## API

- `GET /health`
- `POST /extract` with JSON body `{ "url": "https://..." }`

## Runtime limits

- `VOD_EXTRACTOR_TIMEOUT_MS` caps each yt-dlp run.
- `VOD_EXTRACTOR_MAX_OUTPUT_BYTES` caps stdout retained from yt-dlp.
- `VOD_EXTRACTOR_MAX_CONCURRENT` caps concurrent yt-dlp processes.
- `VOD_EXTRACTOR_EXTRACTOR_ARGS` optionally passes one `--extractor-args` value to yt-dlp without using a shell, for example `youtube:player_client=android` or a YouTube PO token argument when available.
- `VOD_EXTRACTOR_COOKIES_FILE` optionally points to a Netscape cookies file for yt-dlp. In the workwork compose deployment, place YouTube cookies at `.wework/vod_extractor/youtube-cookies.txt`; the container mounts that directory read-only at `/config`, and `/config/youtube-cookies.txt` is used automatically when the file exists. If `VOD_EXTRACTOR_COOKIES_FILE` is set explicitly, the file must exist and be readable.
