import { execFile } from "node:child_process";
import { ApiError } from "../errors.js";
import type { VideoStream } from "../types.js";
import { fetchWithTimeout } from "./fetchWithTimeout.js";

export type ExtractedVodFormat = {
  format_id?: string;
  format_note?: string;
  url?: string;
  ext?: string;
  protocol?: string;
  width?: number;
  height?: number;
  filesize?: number;
  filesize_approx?: number;
  vcodec?: string;
  acodec?: string;
};

export type ExtractedVod = {
  id?: string;
  title?: string;
  duration?: number;
  is_live?: boolean;
  live_status?: string;
  formats?: ExtractedVodFormat[];
};

export interface VodExtractor {
  extract(url: string): Promise<ExtractedVod>;
}

const DEFAULT_EXTRACTOR_BIN = process.env.VOD_EXTRACTOR_BIN ?? "yt-dlp";
const DEFAULT_EXTRACTOR_URL = process.env.VOD_EXTRACTOR_URL?.trim();
const DEFAULT_EXTRACTOR_TIMEOUT_MS = Number(
  process.env.VOD_EXTRACTOR_TIMEOUT_MS ?? 30_000,
);
const MAX_EXTRACTOR_OUTPUT_BYTES = 20 * 1024 * 1024;

export function buildVodExtractor(): VodExtractor {
  if (DEFAULT_EXTRACTOR_URL) {
    return new HttpVodExtractor(DEFAULT_EXTRACTOR_URL);
  }
  return new YtDlpVodExtractor();
}

export class HttpVodExtractor implements VodExtractor {
  private readonly endpoint: string;

  constructor(
    endpoint: string,
    private readonly timeoutMs = DEFAULT_EXTRACTOR_TIMEOUT_MS,
    private readonly fetcher: typeof fetch = fetch,
  ) {
    const parsed = new URL(endpoint);
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
      throw new Error("VOD_EXTRACTOR_URL must use http or https");
    }
    this.endpoint = parsed.toString();
  }

  async extract(url: string): Promise<ExtractedVod> {
    let response: Response;
    try {
      response = await fetchWithTimeout(
        this.fetcher,
        this.endpoint,
        {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ url }),
        },
        this.timeoutMs,
      );
    } catch (error) {
      throw new ApiError(
        "PROVIDER_UNAVAILABLE",
        "点播解析服务不可用或解析超时。",
        502,
        error,
      );
    }

    const text = await readExtractorResponse(response);
    if (!response.ok) {
      throw new ApiError(
        "PROVIDER_UNAVAILABLE",
        "点播解析服务返回失败。",
        502,
        { status: response.status, body: text },
      );
    }

    try {
      const parsed = JSON.parse(text) as unknown;
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
        throw new Error("extractor response is not an object");
      }
      return parsed as ExtractedVod;
    } catch (error) {
      throw new ApiError(
        "PROVIDER_UNAVAILABLE",
        "点播解析服务返回了无法识别的数据。",
        502,
        error,
      );
    }
  }
}

export class YtDlpVodExtractor implements VodExtractor {
  constructor(
    private readonly bin = DEFAULT_EXTRACTOR_BIN,
    private readonly timeoutMs = DEFAULT_EXTRACTOR_TIMEOUT_MS,
  ) {}

  async extract(url: string): Promise<ExtractedVod> {
    const stdout = await new Promise<string>((resolve, reject) => {
      execFile(
        this.bin,
        ["--dump-single-json", "--no-playlist", "--no-warnings", url],
        {
          timeout: this.timeoutMs,
          maxBuffer: MAX_EXTRACTOR_OUTPUT_BYTES,
        },
        (error, stdoutData, stderrData) => {
          if (error) {
            reject(
              new ApiError(
                "PROVIDER_UNAVAILABLE",
                "点播解析器不可用或解析失败。请安装 yt-dlp，或通过 VOD_EXTRACTOR_BIN 指定兼容工具。",
                502,
                { error, stderr: stderrData },
              ),
            );
            return;
          }
          resolve(stdoutData);
        },
      );
    });

    try {
      return JSON.parse(stdout) as ExtractedVod;
    } catch (error) {
      throw new ApiError(
        "PROVIDER_UNAVAILABLE",
        "点播解析器返回了无法识别的数据。",
        502,
        error,
      );
    }
  }
}

async function readExtractorResponse(response: Response): Promise<string> {
  const contentLength = Number(response.headers.get("content-length"));
  if (
    Number.isFinite(contentLength) &&
    contentLength > MAX_EXTRACTOR_OUTPUT_BYTES
  ) {
    throw new ApiError(
      "PROVIDER_UNAVAILABLE",
      "点播解析服务响应过大。",
      502,
    );
  }
  const text = await response.text();
  if (text.length > MAX_EXTRACTOR_OUTPUT_BYTES) {
    throw new ApiError(
      "PROVIDER_UNAVAILABLE",
      "点播解析服务响应过大。",
      502,
    );
  }
  return text;
}

export function extractedVodToStreams(
  extracted: ExtractedVod,
  idPrefix: string,
): VideoStream[] {
  if (isLiveVideo(extracted)) {
    throw new ApiError(
      "NO_PLAYABLE_STREAM",
      "当前只支持普通视频点播，不支持直播视频。",
      422,
    );
  }

  const formats = extracted.formats ?? [];
  return formats
    .filter(isProgressiveVodFormat)
    .sort(compareFormatQuality)
    .slice(0, 6)
    .map((format, index) => {
      const mimeType = format.ext === "webm" ? "video/webm" : "video/mp4";
      const height = format.height ?? undefined;
      const label = format.format_note ?? (height ? `${height}p` : "点播");
      return {
        id: `${idPrefix}-${format.format_id ?? index}`,
        url: format.url ?? "",
        originalUrl: format.url ?? "",
        mimeType,
        quality: label,
        width: format.width,
        height,
        sizeBytes: format.filesize ?? format.filesize_approx,
        supportsRange: true,
      };
    });
}

export function isLiveVideo(extracted: ExtractedVod): boolean {
  if (extracted.is_live) {
    return true;
  }
  const status = extracted.live_status;
  return Boolean(status && status !== "not_live");
}

function isProgressiveVodFormat(format: ExtractedVodFormat): boolean {
  if (!format.url) {
    return false;
  }
  if (format.vcodec === "none" || format.acodec === "none") {
    return false;
  }
  if (format.protocol && !/^https?$/i.test(format.protocol)) {
    return false;
  }
  return format.ext === "mp4" || format.ext === "webm";
}

function compareFormatQuality(
  a: ExtractedVodFormat,
  b: ExtractedVodFormat,
): number {
  return (b.height ?? 0) - (a.height ?? 0);
}
