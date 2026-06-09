import { ApiError } from "../errors.js";
import type {
  ProviderName,
  SearchResult,
  VideoDetails,
  VideoStream,
  VideoSummary,
} from "../types.js";
import type { VideoProvider } from "./VideoProvider.js";
import {
  buildVodExtractor,
  extractedVodToStreams,
  type VodExtractor,
} from "../services/VodExtractor.js";
import { fetchWithTimeout } from "../services/fetchWithTimeout.js";

type FetchLike = typeof fetch;

// ── YouTube 页面 JSON 中的类型（只取需要的字段）───────────────────

type YtSearchVideo = {
  videoId?: string;
  title?: { runs?: Array<{ text?: string }> };
  lengthText?: { simpleText?: string };
  ownerText?: { runs?: Array<{ text?: string }> };
  publishedTimeText?: { simpleText?: string };
  thumbnail?: {
    thumbnails?: Array<{ url?: string; width?: number; height?: number }>;
  };
  descriptionSnippet?: { runs?: Array<{ text?: string }> };
};

type YtOEmbed = {
  title?: string;
  author_name?: string;
  thumbnail_url?: string;
};

// ── 常量 ──────────────────────────────────────────────────────────

const PROVIDER: ProviderName = "youtube";
const USER_AGENT =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
const DEFAULT_CLIENT_VERSION = "2.20240601.01.00";
const MAX_CONTINUATION_PAGES = 120;

// ── 工具函数 ──────────────────────────────────────────────────────

/** 解析 "HH:MM:SS" 或 "MM:SS" 为秒数 */
function parseHms(d?: string): number | undefined {
  if (!d) return undefined;
  const parts = d.split(":").map(Number);
  if (parts.some(isNaN)) return undefined;
  if (parts.length === 3) return parts[0] * 3600 + parts[1] * 60 + parts[2];
  if (parts.length === 2) return parts[0] * 60 + parts[1];
  return undefined;
}

/** 从搜索页面 HTML 提取 ytInitialData JSON */
function extractYtInitialData(html: string): unknown | null {
  const match = html.match(/var ytInitialData\s*=\s*(\{.+?\});\s*<\/script>/s);
  if (!match) return null;
  try {
    return JSON.parse(match[1]);
  } catch {
    return null;
  }
}

function extractInnertubeApiKey(html: string): string | undefined {
  return html.match(/"INNERTUBE_API_KEY"\s*:\s*"([^"]+)"/)?.[1];
}

function extractInnertubeClientVersion(html: string): string {
  return (
    html.match(/"INNERTUBE_CLIENT_VERSION"\s*:\s*"([^"]+)"/)?.[1] ??
    DEFAULT_CLIENT_VERSION
  );
}

function extractSearchVideos(data: unknown): YtSearchVideo[] {
  const videos: YtSearchVideo[] = [];
  const seen = new Set<string>();
  walkObjects(data, (record) => {
    const video = record.videoRenderer as YtSearchVideo | undefined;
    if (!video?.videoId || seen.has(video.videoId)) {
      return;
    }
    seen.add(video.videoId);
    videos.push(video);
  });
  return videos;
}

function extractContinuationToken(data: unknown): string | undefined {
  let token: string | undefined;
  walkObjects(data, (record) => {
    if (token) {
      return;
    }
    const continuation = record.continuationItemRenderer as
      | {
          continuationEndpoint?: {
            continuationCommand?: { token?: string };
          };
        }
      | undefined;
    const endpoint = record.continuationEndpoint as
      | {
          continuationCommand?: { token?: string };
        }
      | undefined;
    const command = record.continuationCommand as
      | { token?: string }
      | undefined;
    token =
      continuation?.continuationEndpoint?.continuationCommand?.token ??
      endpoint?.continuationCommand?.token ??
      command?.token;
  });
  return token;
}

function walkObjects(
  value: unknown,
  visitor: (record: Record<string, unknown>) => void,
): void {
  if (!value || typeof value !== "object") {
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((item) => walkObjects(item, visitor));
    return;
  }
  const record = value as Record<string, unknown>;
  visitor(record);
  Object.values(record).forEach((child) => walkObjects(child, visitor));
}

/** 从 watch 页面 HTML 提取视频时长（第一条 lengthText 是视频本身的） */
function extractDurationFromHtml(html: string): number | undefined {
  // watch 页面中有多条 lengthText（推荐视频也有），取最靠前的即为目标视频
  const match = html.match(
    /"lengthText".*?"simpleText"\s*:\s*"(\d+:\d+(?::\d+)?)"/,
  );
  return parseHms(match?.[1]);
}

/** 从 watch 页面 HTML 提取发布日期 */
function extractPublishDateFromHtml(html: string): string | undefined {
  const dataMatch = html.match(
    /var ytInitialData\s*=\s*(\{.+?\});\s*<\/script>/s,
  );
  if (!dataMatch) return undefined;
  try {
    const data = JSON.parse(dataMatch[1]);
    const contents =
      data?.contents?.twoColumnWatchNextResults?.results?.results?.contents;
    if (!Array.isArray(contents)) return undefined;
    for (const c of contents) {
      const dateText = c?.videoPrimaryInfoRenderer?.dateText?.simpleText;
      if (dateText) return dateText;
    }
  } catch {
    // 解析失败不影响主流程
  }
  return undefined;
}

function youtubeWatchUrl(id: string): string {
  return `https://www.youtube.com/watch?v=${encodeURIComponent(id)}`;
}

function bestThumbnail(
  thumbs?: Array<{ url?: string; width?: number; height?: number }>,
): string | undefined {
  if (!thumbs?.length) return undefined;
  // 优先取分辨率最高的
  const sorted = [...thumbs].sort((a, b) => (b.width ?? 0) - (a.width ?? 0));
  return sorted[0]?.url;
}

// ── Provider ──────────────────────────────────────────────────────

export class YouTubeProvider implements VideoProvider {
  readonly name = PROVIDER;

  constructor(
    private readonly fetcher: FetchLike = fetch,
    private readonly extractor: VodExtractor = buildVodExtractor(),
  ) {}

  async search(
    query: string,
    page: number,
    pageSize: number,
  ): Promise<SearchResult> {
    const url = new URL("https://www.youtube.com/results");
    url.searchParams.set("search_query", query);
    const html = await this.fetchText(url.toString());
    const data = extractYtInitialData(html);
    if (!data) {
      return { query, page, pageSize, total: 0, items: [] };
    }

    const requiredCount = page * pageSize;
    const apiKey = extractInnertubeApiKey(html);
    const clientVersion = extractInnertubeClientVersion(html);
    const videos: YtSearchVideo[] = [];
    const seen = new Set<string>();
    const appendVideos = (source: unknown) => {
      for (const video of extractSearchVideos(source)) {
        if (!video.videoId || seen.has(video.videoId)) {
          continue;
        }
        seen.add(video.videoId);
        videos.push(video);
      }
    };

    appendVideos(data);
    let continuationToken = extractContinuationToken(data);
    let continuationPages = 0;
    let vodVideos = videos.filter(
      (video) => parseHms(video.lengthText?.simpleText) !== undefined,
    );
    while (
      apiKey &&
      continuationToken &&
      vodVideos.length < requiredCount &&
      continuationPages < MAX_CONTINUATION_PAGES
    ) {
      const continuationData = await this.fetchContinuation(
        apiKey,
        clientVersion,
        continuationToken,
      );
      appendVideos(continuationData);
      continuationToken = extractContinuationToken(continuationData);
      continuationPages += 1;
      vodVideos = videos.filter(
        (video) => parseHms(video.lengthText?.simpleText) !== undefined,
      );
    }

    const start = (page - 1) * pageSize;
    const paged = vodVideos.slice(start, start + pageSize);
    const hasMore = Boolean(apiKey && continuationToken);

    return {
      query,
      page,
      pageSize,
      total: hasMore
        ? Math.max(vodVideos.length, start + paged.length + 1)
        : vodVideos.length,
      items: paged.map((v) => this.searchItemToSummary(v)),
    };
  }

  async getDetails(id: string): Promise<VideoDetails> {
    // 1. oEmbed 获取基础信息（无需 API Key）
    const oembedUrl = `https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=${encodeURIComponent(id)}&format=json`;
    const oembed = await this.fetchJson<YtOEmbed>(oembedUrl);

    // 2. watch 页面获取时长和日期
    const html = await this.fetchText(youtubeWatchUrl(id));
    const durationSeconds = extractDurationFromHtml(html);
    const year = extractPublishDateFromHtml(html)?.match(/\d{4}/)?.[0];
    const streams = await this.getStreams(id);

    return {
      provider: PROVIDER,
      id,
      videoKind: "vod",
      title: oembed.title?.trim() || id,
      description: "",
      year,
      creators: oembed.author_name ? [oembed.author_name] : [],
      thumbnailUrl:
        oembed.thumbnail_url ??
        `https://i.ytimg.com/vi/${encodeURIComponent(id)}/hqdefault.jpg`,
      durationSeconds,
      sourceUrl: youtubeWatchUrl(id),
      playable: streams.length > 0,
      streams,
    };
  }

  async getStreams(id: string): Promise<VideoStream[]> {
    const extracted = await this.extractor.extract(youtubeWatchUrl(id));
    const streams = extractedVodToStreams(extracted, `yt-${id}`);
    if (streams.length === 0) {
      throw new ApiError(
        "NO_PLAYABLE_STREAM",
        "该 YouTube 普通视频暂无可由 App 直接播放的点播媒体地址。",
        422,
      );
    }
    return streams;
  }

  // ── 内部方法 ──────────────────────────────────────────────────

  private searchItemToSummary(v: YtSearchVideo): VideoSummary {
    const videoId = v.videoId ?? "";
    const title = v.title?.runs?.[0]?.text?.trim() || videoId;
    return {
      provider: PROVIDER,
      id: videoId,
      videoKind: "vod",
      title,
      description:
        v.descriptionSnippet?.runs?.map((r) => r.text ?? "").join("") ?? "",
      creators: v.ownerText?.runs?.[0]?.text ? [v.ownerText.runs[0].text] : [],
      thumbnailUrl: bestThumbnail(v.thumbnail?.thumbnails),
      durationSeconds: parseHms(v.lengthText?.simpleText),
      sourceUrl: youtubeWatchUrl(videoId),
      playable: true,
    };
  }

  private async fetchText(url: string): Promise<string> {
    try {
      const resp = await fetchWithTimeout(this.fetcher, url, {
        headers: {
          "User-Agent": USER_AGENT,
          "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        },
      });
      if (!resp.ok) {
        throw new ApiError(
          "UPSTREAM_UNAVAILABLE",
          "YouTube 返回了错误响应。",
          502,
          { status: resp.status },
        );
      }
      return await resp.text();
    } catch (error) {
      if (error instanceof ApiError) throw error;
      throw new ApiError(
        "UPSTREAM_UNAVAILABLE",
        "无法连接 YouTube。",
        502,
        error,
      );
    }
  }

  private async fetchJson<T>(url: string): Promise<T> {
    try {
      const resp = await fetchWithTimeout(this.fetcher, url);
      if (!resp.ok) {
        throw new ApiError(
          "UPSTREAM_UNAVAILABLE",
          "YouTube oEmbed 返回了错误响应。",
          502,
          { status: resp.status },
        );
      }
      return (await resp.json()) as T;
    } catch (error) {
      if (error instanceof ApiError) throw error;
      throw new ApiError(
        "UPSTREAM_UNAVAILABLE",
        "无法连接 YouTube oEmbed API。",
        502,
        error,
      );
    }
  }

  private async fetchContinuation(
    apiKey: string,
    clientVersion: string,
    token: string,
  ): Promise<unknown> {
    const url = new URL("https://www.youtube.com/youtubei/v1/search");
    url.searchParams.set("key", apiKey);
    try {
      const resp = await fetchWithTimeout(this.fetcher, url.toString(), {
        method: "POST",
        headers: {
          "User-Agent": USER_AGENT,
          "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
          "Content-Type": "application/json",
          Origin: "https://www.youtube.com",
          Referer: "https://www.youtube.com/results",
        },
        body: JSON.stringify({
          context: {
            client: {
              clientName: "WEB",
              clientVersion,
            },
          },
          continuation: token,
        }),
      });
      if (!resp.ok) {
        throw new ApiError(
          "UPSTREAM_UNAVAILABLE",
          "YouTube 翻页接口返回了错误响应。",
          502,
          { status: resp.status },
        );
      }
      return await resp.json();
    } catch (error) {
      if (error instanceof ApiError) throw error;
      throw new ApiError(
        "UPSTREAM_UNAVAILABLE",
        "无法连接 YouTube 翻页接口。",
        502,
        error,
      );
    }
  }
}
