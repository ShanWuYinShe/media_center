import { ApiError } from "../errors.js";
import type {
  ProviderName,
  SearchResult,
  VideoDetails,
  VideoStream,
  VideoSummary,
} from "../types.js";
import type { VideoProvider } from "./VideoProvider.js";

type FetchLike = typeof fetch;

type YouTubeSearchResponse = {
  pageInfo?: { totalResults?: number };
  items?: Array<{
    id?: { videoId?: string };
    snippet?: YouTubeSnippet;
  }>;
};

type YouTubeVideosResponse = {
  items?: Array<{
    id?: string;
    snippet?: YouTubeSnippet;
    contentDetails?: { duration?: string };
  }>;
};

type YouTubeSnippet = {
  title?: string;
  description?: string;
  channelTitle?: string;
  publishedAt?: string;
  thumbnails?: {
    maxres?: { url?: string };
    high?: { url?: string };
    medium?: { url?: string };
    default?: { url?: string };
  };
};

const PROVIDER: ProviderName = "youtube";
const BASE_URL = "https://www.googleapis.com/youtube/v3";

export class YouTubeProvider implements VideoProvider {
  readonly name = PROVIDER;

  constructor(
    private readonly apiKey: string,
    private readonly fetcher: FetchLike = fetch,
  ) {}

  async search(
    query: string,
    page: number,
    pageSize: number,
  ): Promise<SearchResult> {
    const url = new URL(`${BASE_URL}/search`);
    url.searchParams.set("key", this.apiKey);
    url.searchParams.set("part", "snippet");
    url.searchParams.set("type", "video");
    url.searchParams.set("videoEmbeddable", "true");
    url.searchParams.set("safeSearch", "moderate");
    url.searchParams.set("maxResults", String(Math.min(pageSize, 50)));
    url.searchParams.set("q", query);

    const data = await this.getJson<YouTubeSearchResponse>(url);
    const items = data.items ?? [];
    return {
      query,
      page,
      pageSize,
      total: data.pageInfo?.totalResults ?? items.length,
      items: items
        .filter(
          (
            item,
          ): item is { id: { videoId: string }; snippet?: YouTubeSnippet } =>
            Boolean(item.id?.videoId),
        )
        .map((item) => this.toSummary(item.id.videoId, item.snippet)),
    };
  }

  async getDetails(id: string): Promise<VideoDetails> {
    const url = new URL(`${BASE_URL}/videos`);
    url.searchParams.set("key", this.apiKey);
    url.searchParams.set("part", "snippet,contentDetails");
    url.searchParams.set("id", id);

    const data = await this.getJson<YouTubeVideosResponse>(url);
    const item = data.items?.[0];
    if (!item?.id) {
      throw new ApiError("NOT_FOUND", "未找到 YouTube 视频详情。", 404);
    }

    return {
      ...this.toSummary(item.id, item.snippet),
      durationSeconds: parseIsoDuration(item.contentDetails?.duration),
      sourceUrl: youtubeWatchUrl(item.id),
      streams: [],
    };
  }

  async getStreams(): Promise<VideoStream[]> {
    throw new ApiError(
      "NO_PLAYABLE_STREAM",
      "YouTube 官方 API 不提供可用于本地播放器的直链流，请在 YouTube 客户端或网页中播放。",
      422,
    );
  }

  private toSummary(id: string, snippet?: YouTubeSnippet): VideoSummary {
    return {
      provider: PROVIDER,
      id,
      title: snippet?.title?.trim() || id,
      description: snippet?.description ?? "",
      year: snippet?.publishedAt?.slice(0, 4),
      creators: snippet?.channelTitle ? [snippet.channelTitle] : [],
      thumbnailUrl:
        snippet?.thumbnails?.maxres?.url ??
        snippet?.thumbnails?.high?.url ??
        snippet?.thumbnails?.medium?.url ??
        snippet?.thumbnails?.default?.url,
      sourceUrl: youtubeWatchUrl(id),
      playable: false,
    };
  }

  private async getJson<T>(url: URL): Promise<T> {
    try {
      const response = await this.fetcher(url);
      if (!response.ok) {
        throw new ApiError(
          "PROVIDER_UNAVAILABLE",
          "YouTube API 返回了错误响应。",
          502,
          {
            status: response.status,
          },
        );
      }
      return (await response.json()) as T;
    } catch (error) {
      if (error instanceof ApiError) {
        throw error;
      }
      throw new ApiError(
        "PROVIDER_UNAVAILABLE",
        "无法连接 YouTube API。",
        502,
        error,
      );
    }
  }
}

function youtubeWatchUrl(id: string): string {
  return `https://www.youtube.com/watch?v=${encodeURIComponent(id)}`;
}

function parseIsoDuration(value?: string): number | undefined {
  if (!value) {
    return undefined;
  }
  const match = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/.exec(value);
  if (!match) {
    return undefined;
  }
  return (
    Number(match[1] ?? 0) * 3600 +
    Number(match[2] ?? 0) * 60 +
    Number(match[3] ?? 0)
  );
}
