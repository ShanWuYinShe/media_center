import { LRUCache } from "lru-cache";
import { ApiError } from "../errors.js";
import type {
  ContentCategory,
  PlaybackOption,
  PublicSearchResult,
  PublicVideoDetails,
  PublicVideoSummary,
  SearchResult,
  VideoDetails,
  VideoStream,
} from "../types.js";
import type { VideoProvider } from "../providers/VideoProvider.js";

export class VideoAggregator {
  private readonly searchCache = new LRUCache<string, PublicSearchResult>({
    max: 200,
    ttl: 60_000,
  });
  private readonly detailCache = new LRUCache<string, PublicVideoDetails>({
    max: 500,
    ttl: 300_000,
  });

  constructor(private readonly providers: VideoProvider[]) {}

  async search(
    query: string,
    page: number,
    pageSize: number,
    category?: ContentCategory,
  ): Promise<PublicSearchResult> {
    const expandedQuery = [category?.query, query].filter(Boolean).join(" ");
    const cacheKey = `${category?.id ?? "free"}:${expandedQuery}:${page}:${pageSize}`;
    const cached = this.searchCache.get(cacheKey);
    if (cached) {
      return cached;
    }

    const results = await Promise.all(
      this.providers.map((provider) =>
        provider.search(expandedQuery, page, pageSize),
      ),
    );
    const merged: PublicSearchResult = {
      query: expandedQuery,
      category,
      page,
      pageSize,
      total: results.reduce((total, result) => total + result.total, 0),
      items: results
        .flatMap((result) => result.items)
        .slice(0, pageSize)
        .map((item) => this.toPublicSummary(item)),
    };
    this.searchCache.set(cacheKey, merged);
    return merged;
  }

  async getDetails(videoId: string): Promise<PublicVideoDetails> {
    const { providerName, sourceId } = decodeVideoId(videoId);
    const cacheKey = videoId;
    const cached = this.detailCache.get(cacheKey);
    if (cached) {
      return cached;
    }
    const details = await this.provider(providerName).getDetails(sourceId);
    const publicDetails = this.toPublicDetails(details);
    this.detailCache.set(cacheKey, publicDetails);
    return publicDetails;
  }

  async getPlaybackOptions(videoId: string): Promise<PlaybackOption[]> {
    return (await this.getDetails(videoId)).playbackOptions;
  }

  private provider(name: string): VideoProvider {
    const provider = this.providers.find(
      (candidate) => candidate.name === name,
    );
    if (!provider) {
      throw new ApiError("NOT_FOUND", "不支持的视频来源。", 404);
    }
    return provider;
  }

  private toPublicSummary(
    item: VideoDetails | SearchResult["items"][number],
  ): PublicVideoSummary {
    return {
      id: encodeVideoId(item.provider, item.id),
      title: item.title,
      description: item.description,
      year: item.year,
      creators: item.creators,
      thumbnailUrl: item.thumbnailUrl,
      durationSeconds: item.durationSeconds,
    };
  }

  private toPublicDetails(details: VideoDetails): PublicVideoDetails {
    return {
      ...this.toPublicSummary(details),
      playbackOptions: toPlaybackOptions(details),
    };
  }
}

function toPlaybackOptions(details: VideoDetails): PlaybackOption[] {
  if (details.streams.length > 0) {
    return details.streams.map((stream) => ({
      id: stream.id,
      kind: "stream",
      label: `播放 ${stream.quality}`,
      url: stream.url,
      mimeType: stream.mimeType,
    }));
  }
  return [
    {
      id: "external",
      kind: "external",
      label: "打开播放",
      url: details.sourceUrl,
    },
  ];
}

function encodeVideoId(providerName: string, sourceId: string): string {
  return `${providerName}:${Buffer.from(sourceId, "utf8").toString("base64url")}`;
}

function decodeVideoId(videoId: string): {
  providerName: string;
  sourceId: string;
} {
  const separator = videoId.indexOf(":");
  if (separator <= 0) {
    throw new ApiError("BAD_REQUEST", "视频 ID 不合法。", 400);
  }
  return {
    providerName: videoId.slice(0, separator),
    sourceId: Buffer.from(videoId.slice(separator + 1), "base64url").toString(
      "utf8",
    ),
  };
}
