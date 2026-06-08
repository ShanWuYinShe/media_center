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

const PROVIDER_PAGE_SIZE = 50;
const PROVIDER_CANDIDATE_FACTOR = 4;
const MIN_PROVIDER_CANDIDATES = 40;
const MIN_PROVIDER_PAGES = 2;

export class VideoAggregator {
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
    const items = await this.searchItems(
      expandedQuery,
      page * pageSize,
      category,
    );
    const pageStart = (page - 1) * pageSize;
    const pageEnd = page * pageSize;

    return {
      query,
      category: category ? toPublicCategory(category) : undefined,
      page,
      pageSize,
      total: items.length,
      items: items.slice(pageStart, pageEnd),
    };
  }

  async searchItems(
    expandedQuery: string,
    targetCount: number,
    category?: ContentCategory,
  ): Promise<PublicVideoSummary[]> {
    if (targetCount <= 0) {
      return [];
    }
    const rawCandidateTarget = Math.max(
      MIN_PROVIDER_CANDIDATES,
      targetCount * PROVIDER_CANDIDATE_FACTOR,
    );
    const maxProviderPages = Math.max(
      MIN_PROVIDER_PAGES,
      Math.ceil(rawCandidateTarget / PROVIDER_PAGE_SIZE),
    );
    const settledResults = await Promise.allSettled(
      this.providers.map((provider) =>
        this.collectProviderSearchResult(
          provider,
          expandedQuery,
          rawCandidateTarget,
          maxProviderPages,
        ),
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
    const playableGroups = await this.resolvePlayableGroups(
      results,
      targetCount,
      Math.min(targetCount, PROVIDER_PAGE_SIZE),
      category,
    );
    return interleave(playableGroups, targetCount);
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

  private async collectProviderSearchResult(
    provider: VideoProvider,
    query: string,
    rawCandidateTarget: number,
    maxProviderPages: number,
  ): Promise<SearchResult> {
    const items: SearchResult["items"] = [];
    const seen = new Set<string>();
    let total = 0;
    let emptyPages = 0;
    let noNewPages = 0;

    for (
      let providerPage = 1;
      providerPage <= maxProviderPages;
      providerPage++
    ) {
      const result = await provider.search(
        query,
        providerPage,
        providerPage === 1
          ? Math.max(PROVIDER_PAGE_SIZE, rawCandidateTarget)
          : PROVIDER_PAGE_SIZE,
      );
      total = Math.max(total, result.total);
      if (result.items.length === 0) {
        emptyPages += 1;
      } else {
        emptyPages = 0;
      }

      let newItems = 0;
      for (const item of result.items) {
        const key = `${item.provider}:${item.id}`;
        if (seen.has(key)) {
          continue;
        }
        seen.add(key);
        items.push(item);
        newItems += 1;
      }
      if (newItems === 0) {
        noNewPages += 1;
      } else {
        noNewPages = 0;
      }

      if (items.length >= rawCandidateTarget) {
        break;
      }
      if (total > 0 && items.length >= total) {
        break;
      }
      if (emptyPages >= 2) {
        break;
      }
      if (noNewPages >= 2) {
        break;
      }
    }

    return {
      query,
      page: 1,
      pageSize: items.length,
      total: Math.max(total, items.length),
      items,
    };
  }

  private async resolvePlayableGroups(
    results: SearchResult[],
    targetCount: number,
    pageSize: number,
    category?: ContentCategory,
  ): Promise<PublicVideoSummary[][]> {
    const rankedCandidates = results.map((result) =>
      result.items
        .filter((item) => item.playable && item.videoKind === "vod")
        .map((item) => ({ item, score: relevanceScore(item, category) }))
        .filter(({ score }) => score >= 0)
        .sort((a, b) => b.score - a.score)
        .map(({ item }) => item),
    );
    const offsets = rankedCandidates.map(() => 0);
    const exhausted = rankedCandidates.map(
      (candidates) => candidates.length === 0,
    );
    const groups: PublicVideoSummary[][] = rankedCandidates.map(() => []);
    const chunkSize = Math.max(1, Math.ceil(pageSize / results.length));

    while (interleave(groups, targetCount).length < targetCount) {
      let progressed = false;
      const chunks = await Promise.all(
        rankedCandidates.map((candidates, index) => {
          if (exhausted[index]) {
            return Promise.resolve([]);
          }
          const start = offsets[index];
          const end = Math.min(start + chunkSize, candidates.length);
          const slice = candidates.slice(start, end);
          offsets[index] = end;
          if (end >= candidates.length) {
            exhausted[index] = true;
          }
          if (slice.length === 0) {
            return Promise.resolve([]);
          }
          progressed = true;
          return this.resolveCandidateSummaries(slice);
        }),
      );

      chunks.forEach((chunk, index) => {
        if (chunk.length > 0) {
          groups[index] = groups[index].concat(chunk);
        }
      });

      if (!progressed || exhausted.every(Boolean)) {
        break;
      }
    }

    return groups;
  }

  private async resolveCandidateSummaries(
    candidates: SearchResult["items"],
  ): Promise<PublicVideoSummary[]> {
    const resolved = await Promise.allSettled(
      candidates.map(async (item) => {
        // 先查 60 分钟详情缓存:点播解析(尤其 YouTube 走 yt-dlp)非常昂贵,
        // 搜索/翻页/不同分类间常命中同一视频。命中缓存可直接复用已解析结果,
        // 避免重复拉起 yt-dlp —— 这是冷启动首屏变慢的主要来源之一。
        const videoId = encodeVideoId(item.provider, item.id);
        const cachedDetails = this.detailCache.get(videoId);
        if (cachedDetails) {
          return cachedDetails.playbackOptions.length > 0
            ? publicDetailsToSummary(cachedDetails)
            : undefined;
        }
        const details = await this.provider(item.provider).getDetails(item.id);
        if (details.videoKind !== "vod" || details.streams.length === 0) {
          return undefined;
        }
        const publicDetails = this.toPublicDetails(details);
        this.detailCache.set(videoId, publicDetails);
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

/** 从缓存的公开详情还原公开摘要(去掉 playbackOptions)。 */
function publicDetailsToSummary(
  details: PublicVideoDetails,
): PublicVideoSummary {
  const { playbackOptions: _omit, ...summary } = details;
  void _omit;
  return summary;
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
    ![
      "红色",
      "长征",
      "抗战",
      "抗日",
      "解放",
      "解放战争",
      "革命",
      "红军",
      "战争",
      "抗美援朝",
      "志愿军",
      "八路军",
      "新四军",
      "游击",
      "地道战",
      "地雷战",
      "上甘岭",
      "英雄儿女",
      "闪闪的红星",
      "铁道游击队",
      "董存瑞",
      "狼牙山",
      "小兵张嘎",
      "南征北战",
      "红色娘子军",
      "智取威虎山",
      "林海雪原",
      "老电影",
    ].some((term) => text.includes(term))
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
