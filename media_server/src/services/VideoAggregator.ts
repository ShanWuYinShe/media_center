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
} from "../types.js";
import type { VideoProvider } from "../providers/VideoProvider.js";

export class VideoAggregator {
  private readonly searchCache = new LRUCache<string, PublicSearchResult>({
    max: 1_000,
    ttl: 15 * 60_000,
  });
  private readonly detailCache = new LRUCache<string, PublicVideoDetails>({
    max: 5_000,
    ttl: 60 * 60_000,
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

    const providerPageSize = category ? Math.min(pageSize * 6, 40) : pageSize;
    const settledResults = await Promise.allSettled(
      this.providers.map((provider) =>
        provider.search(expandedQuery, page, providerPageSize),
      ),
    );
    const results = settledResults.flatMap((result) =>
      result.status === "fulfilled" ? [result.value] : [],
    );
    if (results.length === 0) {
      throw new ApiError(
        "PROVIDER_UNAVAILABLE",
        "所有视频来源暂时不可用。",
        502,
      );
    }
    const playableGroups: PublicVideoSummary[][] = [];
    for (const result of results) {
      const remaining = pageSize - playableGroups.flat().length;
      if (remaining <= 0) {
        break;
      }
      playableGroups.push(
        await this.resolvePlayableSummaries(result, remaining, category),
      );
    }
    const items = interleave(playableGroups, pageSize);
    const merged: PublicSearchResult = {
      query,
      category: category ? toPublicCategory(category) : undefined,
      page,
      pageSize,
      total: playableGroups.reduce((total, group) => total + group.length, 0),
      items,
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

  private async resolvePlayableSummaries(
    result: SearchResult,
    limit: number,
    category?: ContentCategory,
  ): Promise<PublicVideoSummary[]> {
    const candidates = result.items
      .filter((item) => item.playable && item.videoKind === "vod")
      .map((item) => ({ item, score: relevanceScore(item, category) }))
      .filter(({ score }) => score >= 0)
      .sort((a, b) => b.score - a.score)
      .map(({ item }) => item)
      .slice(0, limit);
    const resolved = await Promise.allSettled(
      candidates.map(async (item) => {
        const details = await this.provider(item.provider).getDetails(item.id);
        if (details.videoKind !== "vod" || details.streams.length === 0) {
          return undefined;
        }
        const publicDetails = this.toPublicDetails(details);
        this.detailCache.set(
          encodeVideoId(details.provider, details.id),
          publicDetails,
        );
        return this.toPublicSummary(details);
      }),
    );
    return resolved.flatMap((item) =>
      item.status === "fulfilled" && item.value ? [item.value] : [],
    );
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
      videoKind: item.videoKind,
      title: item.title,
      description: item.description,
      year: item.year,
      creators: item.creators,
      thumbnailUrl: item.thumbnailUrl,
      durationSeconds: item.durationSeconds,
      playbackKind: "vod",
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
  if (details.videoKind !== "vod") {
    throw new ApiError(
      "NO_PLAYABLE_STREAM",
      "当前只支持普通视频点播，不支持直播视频。",
      422,
    );
  }
  if (details.streams.length > 0) {
    return details.streams.map((stream) => ({
      id: stream.id,
      kind: "vod",
      label: stream.quality,
      url: stream.url,
      mimeType: stream.mimeType,
    }));
  }
  throw new ApiError(
    "NO_PLAYABLE_STREAM",
    "该普通视频暂无可由 App 直接播放的点播媒体地址。",
    422,
  );
}

function toPublicCategory(category: ContentCategory) {
  return {
    id: category.id,
    title: category.title,
    description: category.description,
  };
}

function relevanceScore(
  item: SearchResult["items"][number],
  category?: ContentCategory,
): number {
  if (!category) {
    return 1;
  }
  const text = `${item.title} ${item.description} ${item.creators.join(" ")}`
    .toLocaleLowerCase()
    .replace(/\s+/g, " ");
  if (
    category.excludes.some((term) => text.includes(term.toLocaleLowerCase()))
  ) {
    return -1;
  }
  if (
    category.id !== "dota-zone" &&
    item.durationSeconds !== undefined &&
    item.durationSeconds < 20 * 60
  ) {
    return -1;
  }
  const keywordScore = category.keywords.reduce((score, keyword, index) => {
    const normalized = keyword.toLocaleLowerCase();
    if (!normalized || !text.includes(normalized)) {
      return score;
    }
    return score + Math.max(1, category.keywords.length - index);
  }, 0);
  if (category.id === "dota-zone") {
    if (
      text.includes("dota1") ||
      text.includes("dota 1") ||
      text.includes("魔兽争霸") ||
      text.includes("allstars")
    ) {
      return keywordScore + 30;
    }
    return /\b(dota|dota2|ti)\b/i.test(text) ? keywordScore + 5 : -1;
  }
  if (keywordScore <= 0) {
    return -1;
  }
  if (
    category.id === "movies" &&
    !text.includes("完整版") &&
    !text.includes("全片") &&
    !text.includes("正片")
  ) {
    return -1;
  }
  if (
    category.id === "tv-series" &&
    !text.includes("全集") &&
    !text.includes("连续剧") &&
    !text.includes("剧集") &&
    !/第\s*\d+\s*集/.test(text) &&
    !/\d+\s*[-~]\s*\d+\s*集/.test(text) &&
    !/ep\s*\d+/i.test(text)
  ) {
    return -1;
  }
  if (category.id === "documentaries" && !text.includes("纪录片")) {
    return -1;
  }
  if (
    category.id === "red-classic" &&
    !["红色", "长征", "抗战", "抗日", "解放战争", "革命", "红军", "战争"].some(
      (term) => text.includes(term),
    )
  ) {
    return -1;
  }
  return keywordScore;
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

/**
 * 将多组结果轮询交织：每轮从各组取一个元素，直到填满 limit 或所有组耗尽。
 * 例如 3 个 provider 各返回 [A1,A2,A3], [B1,B2], [C1,C2,C3,C4],
 * limit=7 → [A1,B1,C1, A2,B2,C2, A3,C3]（B 组在第 2 轮耗尽后跳过）。
 */
function interleave<T>(groups: T[][], limit: number): T[] {
  const out: T[] = [];
  const cursors = groups.map(() => 0);
  while (out.length < limit) {
    let added = false;
    for (let g = 0; g < groups.length; g++) {
      if (out.length >= limit) break;
      if (cursors[g] < groups[g].length) {
        out.push(groups[g][cursors[g]++]);
        added = true;
      }
    }
    if (!added) break; // 所有组都已耗尽
  }
  return out;
}
