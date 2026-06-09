import {
  createCipheriv,
  createDecipheriv,
  createHash,
  createHmac,
} from "node:crypto";
import { LRUCache } from "lru-cache";
import { ApiError } from "../errors.js";
import { VIDEO_ID_SECRET } from "./runtimeSecrets.js";
import type {
  ContentCategory,
  PlaybackOption,
  PublicSearchResult,
  PublicVideoDetails,
  PublicVideoSummary,
  SearchResult,
  VideoDetails,
  ProviderName,
} from "../types.js";
import type { VideoProvider } from "../providers/VideoProvider.js";

const PROVIDER_PAGE_SIZE = 50;
const PROVIDER_CANDIDATE_FACTOR = 4;
const MIN_PROVIDER_CANDIDATES = 40;
const MIN_PROVIDER_PAGES = 2;
const DEFAULT_PROVIDER_SEARCH_TIMEOUT_MS = positiveNumber(
  process.env.PROVIDER_SEARCH_TIMEOUT_MS,
  15_000,
);
const DEFAULT_DETAIL_RESOLVE_TIMEOUT_MS = positiveNumber(
  process.env.DETAIL_RESOLVE_TIMEOUT_MS,
  35_000,
);
const DEFAULT_SEARCH_DETAIL_TIMEOUT_MS = positiveNumber(
  process.env.SEARCH_DETAIL_TIMEOUT_MS,
  8_000,
);
const DEFAULT_YOUTUBE_FALLBACK_COOLDOWN_MS = positiveNumber(
  process.env.YOUTUBE_FALLBACK_COOLDOWN_MS,
  30 * 60_000,
);
const VIDEO_ID_KEY = createHash("sha256")
  .update(`media-public-video-id:${VIDEO_ID_SECRET}`)
  .digest();
const YOUTUBE_FALLBACK_CANDIDATES = 5;
const MIN_YOUTUBE_FALLBACK_SCORE = 0.32;

type VideoAggregatorOptions = {
  providerSearchTimeoutMs?: number;
  detailResolveTimeoutMs?: number;
  searchDetailTimeoutMs?: number;
  youtubeFallbackCooldownMs?: number;
};

type BilibiliPlaybackFallback = {
  query: string;
  title: string;
  description: string;
  creator?: string;
  thumbnailUrl?: string;
  durationSeconds?: number;
  sourceUrl: string;
  year?: string;
};

export class VideoAggregator {
  private readonly detailCache = new LRUCache<string, PublicVideoDetails>({
    max: 5_000,
    ttl: 60 * 60_000,
  });

  private readonly providerSearchTimeoutMs: number;
  private readonly detailResolveTimeoutMs: number;
  private readonly searchDetailTimeoutMs: number;
  private readonly youtubeFallbackCooldownMs: number;
  private youtubeFallbackUnavailableUntil = 0;

  constructor(
    private readonly providers: VideoProvider[],
    options: VideoAggregatorOptions = {},
  ) {
    this.providerSearchTimeoutMs =
      options.providerSearchTimeoutMs ?? DEFAULT_PROVIDER_SEARCH_TIMEOUT_MS;
    this.detailResolveTimeoutMs =
      options.detailResolveTimeoutMs ?? DEFAULT_DETAIL_RESOLVE_TIMEOUT_MS;
    this.searchDetailTimeoutMs =
      options.searchDetailTimeoutMs ?? DEFAULT_SEARCH_DETAIL_TIMEOUT_MS;
    this.youtubeFallbackCooldownMs =
      options.youtubeFallbackCooldownMs ?? DEFAULT_YOUTUBE_FALLBACK_COOLDOWN_MS;
  }

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
          providerSearchQuery(provider.name, expandedQuery, category),
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
        "UPSTREAM_UNAVAILABLE",
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
    const details = await this.resolveDetails(providerName, sourceId);
    const publicDetails = this.toPublicDetails(details);
    this.detailCache.set(videoId, publicDetails);
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
      const result = await withTimeout(
        provider.search(
          query,
          providerPage,
          providerPage === 1
            ? Math.max(PROVIDER_PAGE_SIZE, rawCandidateTarget)
            : PROVIDER_PAGE_SIZE,
        ),
        this.providerSearchTimeoutMs,
        () => providerTimeoutError(provider.name, "搜索"),
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
    let remainingDetailAttempts = Math.max(
      targetCount * 2,
      pageSize * results.length,
      4,
    );

    while (
      remainingDetailAttempts > 0 &&
      interleave(groups, targetCount).length < targetCount
    ) {
      let progressed = false;
      const chunks = await Promise.all(
        rankedCandidates.map((candidates, index) => {
          if (exhausted[index]) {
            return Promise.resolve([]);
          }
          const start = offsets[index];
          const take = Math.min(
            chunkSize,
            remainingDetailAttempts,
            candidates.length - start,
          );
          if (take <= 0) {
            return Promise.resolve([]);
          }
          remainingDetailAttempts -= take;
          const end = start + take;
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
        // 搜索阶段可复用详情缓存里的"可播放"结论,避免重复调用 extractor。
        // 真正进入详情/播放时仍重新解析,因为 Provider CDN 直链有时效。
        const videoId = encodeVideoId(item.provider, item.id);
        const cachedDetails = this.detailCache.get(videoId);
        if (cachedDetails) {
          return cachedDetails.playbackOptions.length > 0
            ? publicDetailsToSummary(cachedDetails)
            : undefined;
        }
        if (item.provider === "bilibili" || item.provider === "internet-archive") {
          // 风控或元数据成本较高的来源只在进入详情/播放时解析直链。
          return this.toPublicSummary(item);
        }
        const details = await withTimeout(
          this.provider(item.provider).getDetails(item.id),
          this.searchDetailTimeoutMs,
          () => providerTimeoutError(item.provider, "详情解析"),
        );
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

  private async resolveDetails(
    providerName: string,
    sourceId: string,
  ): Promise<VideoDetails> {
    try {
      return await withTimeout(
        this.provider(providerName).getDetails(sourceId),
        this.detailResolveTimeoutMs,
        () => providerTimeoutError(providerName, "详情解析"),
      );
    } catch (error) {
      const fallback = bilibiliPlaybackFallback(providerName, sourceId, error);
      if (!fallback) {
        throw error;
      }
      if (this.isYoutubeFallbackCoolingDown()) {
        throw error;
      }
      let youtubeDetails: VideoDetails | undefined;
      try {
        youtubeDetails = await this.findYoutubeFallback(fallback);
      } catch {
        this.coolDownYoutubeFallback();
        throw error;
      }
      if (youtubeDetails) {
        return {
          provider: "bilibili",
          id: sourceId,
          videoKind: "vod",
          title: fallback.title,
          description: fallback.description,
          year: fallback.year,
          creators: fallback.creator ? [fallback.creator] : [],
          thumbnailUrl: fallback.thumbnailUrl,
          durationSeconds: fallback.durationSeconds,
          sourceUrl: fallback.sourceUrl,
          playable: true,
          streams: youtubeDetails.streams,
        };
      }
      throw error;
    }
  }

  private async findYoutubeFallback(
    fallback: BilibiliPlaybackFallback,
  ): Promise<VideoDetails | undefined> {
    const youtube = this.providers.find(
      (provider) => provider.name === "youtube",
    );
    if (!youtube) {
      return undefined;
    }
    const search = await withTimeout(
      youtube.search(fallback.query, 1, YOUTUBE_FALLBACK_CANDIDATES),
      this.providerSearchTimeoutMs,
      () => providerTimeoutError("youtube", "补源搜索"),
    );
    const candidates = search.items
      .filter((item) => item.provider === "youtube" && item.videoKind === "vod")
      .map((item) => ({
        item,
        score: youtubeFallbackScore(item, fallback),
      }))
      .filter(({ score }) => score >= MIN_YOUTUBE_FALLBACK_SCORE)
      .sort((a, b) => b.score - a.score)
      .slice(0, YOUTUBE_FALLBACK_CANDIDATES);

    let lastCandidateError: unknown;
    for (const { item } of candidates) {
      try {
        const details = await withTimeout(
          youtube.getDetails(item.id),
          this.detailResolveTimeoutMs,
          () => providerTimeoutError("youtube", "补源解析"),
        );
        if (details.videoKind === "vod" && details.streams.length > 0) {
          return details;
        }
      } catch (error) {
        lastCandidateError = error;
      }
    }
    if (lastCandidateError) {
      throw new ApiError(
        "UPSTREAM_UNAVAILABLE",
        "B站播放地址触发风控，YouTube 补源暂不可用。",
        502,
        lastCandidateError,
      );
    }
    return undefined;
  }

  private isYoutubeFallbackCoolingDown(): boolean {
    return Date.now() < this.youtubeFallbackUnavailableUntil;
  }

  private coolDownYoutubeFallback(): void {
    if (this.youtubeFallbackCooldownMs > 0) {
      this.youtubeFallbackUnavailableUntil =
        Date.now() + this.youtubeFallbackCooldownMs;
    }
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

function withTimeout<T>(
  promise: Promise<T>,
  timeoutMs: number,
  createError: () => Error,
): Promise<T> {
  if (timeoutMs <= 0) {
    return promise;
  }
  return new Promise<T>((resolve, reject) => {
    const timeout = setTimeout(() => reject(createError()), timeoutMs);
    promise.then(resolve, reject).finally(() => clearTimeout(timeout));
  });
}

function providerTimeoutError(provider: string, operation: string): ApiError {
  return new ApiError(
    "UPSTREAM_TIMEOUT",
    `${provider} ${operation}响应超时。`,
    502,
  );
}

function positiveNumber(value: string | undefined, fallback: number): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
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
    return details.streams.map((stream, index) => ({
      id: encodePlaybackOptionId(details, stream, index),
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

function bilibiliPlaybackFallback(
  providerName: string,
  sourceId: string,
  error: unknown,
): BilibiliPlaybackFallback | undefined {
  if (
    providerName !== "bilibili" ||
    !(error instanceof ApiError) ||
    error.code !== "UPSTREAM_UNAVAILABLE" ||
    !isRecord(error.upstreamError) ||
    error.upstreamError.reason !== "bilibili_voucher"
  ) {
    return undefined;
  }
  const title = stringValue(error.upstreamError.title);
  const query = stringValue(error.upstreamError.fallbackSearchQuery) ?? title;
  if (!query) {
    return undefined;
  }
  return {
    query,
    title: title ?? query,
    description: stringValue(error.upstreamError.description) ?? "",
    creator: stringValue(error.upstreamError.creator),
    thumbnailUrl: stringValue(error.upstreamError.thumbnailUrl),
    durationSeconds: positiveFiniteNumber(error.upstreamError.durationSeconds),
    sourceUrl:
      stringValue(error.upstreamError.sourceUrl) ??
      `https://www.bilibili.com/video/${sourceId}`,
    year: stringValue(error.upstreamError.year),
  };
}

function youtubeFallbackScore(
  item: SearchResult["items"][number],
  fallback: BilibiliPlaybackFallback,
): number {
  let score = textSimilarity(item.title, fallback.title);
  if (fallback.durationSeconds && item.durationSeconds) {
    const diff = Math.abs(item.durationSeconds - fallback.durationSeconds);
    const closeEnough = Math.max(120, fallback.durationSeconds * 0.15);
    if (diff <= closeEnough) {
      score += 0.25;
    } else if (diff > Math.max(600, fallback.durationSeconds * 0.5)) {
      score -= 0.2;
    }
  }
  const fallbackCreator = fallback.creator;
  if (
    fallbackCreator &&
    item.creators.some(
      (itemCreator) =>
        normalizedText(itemCreator).includes(normalizedText(fallbackCreator)) ||
        normalizedText(fallbackCreator).includes(normalizedText(itemCreator)),
    )
  ) {
    score += 0.05;
  }
  return score;
}

function textSimilarity(left: string, right: string): number {
  const a = normalizedText(left);
  const b = normalizedText(right);
  if (!a || !b) {
    return 0;
  }
  if (a === b) {
    return 1;
  }
  const minLength = Math.min(a.length, b.length);
  if (minLength >= 8 && (a.includes(b) || b.includes(a))) {
    return 0.75;
  }
  const aParts = bigrams(a);
  const bParts = bigrams(b);
  const union = new Set([...aParts, ...bParts]);
  let intersection = 0;
  for (const part of aParts) {
    if (bParts.has(part)) {
      intersection += 1;
    }
  }
  return union.size > 0 ? intersection / union.size : 0;
}

function normalizedText(value: string): string {
  return value.toLocaleLowerCase().replace(/[^\p{Letter}\p{Number}]+/gu, "");
}

function bigrams(value: string): Set<string> {
  const chars = Array.from(value);
  if (chars.length <= 1) {
    return new Set(chars);
  }
  const out = new Set<string>();
  for (let index = 0; index < chars.length - 1; index++) {
    out.add(`${chars[index]}${chars[index + 1]}`);
  }
  return out;
}

function stringValue(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function positiveFiniteNumber(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) && value > 0
    ? value
    : undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function providerSearchQuery(
  providerName: string,
  fallbackQuery: string,
  category?: ContentCategory,
): string {
  if (!category || !isProviderName(providerName)) {
    return fallbackQuery;
  }
  return category.sourceQueries[providerName] ?? fallbackQuery;
}

function isProviderName(value: string): value is ProviderName {
  return (
    value === "internet-archive" || value === "youtube" || value === "bilibili"
  );
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
  const sourceScore = sourceQueryRelevanceScore(item, category);
  if (sourceScore !== undefined) {
    return sourceScore;
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

function sourceQueryRelevanceScore(
  item: SearchResult["items"][number],
  category: ContentCategory,
): number | undefined {
  const providerQuery = category.sourceQueries[item.provider];
  if (!providerQuery || item.provider !== "internet-archive") {
    return undefined;
  }
  const text = `${item.title} ${item.description} ${item.creators.join(" ")}`
    .toLocaleLowerCase()
    .replace(/\s+/g, " ");
  const terms = providerQuery
    .toLocaleLowerCase()
    .split(/\s+/)
    .filter((term) => term.length >= 3);
  const matchedTerms = terms.filter((term) => text.includes(term)).length;
  return Math.max(1, matchedTerms);
}

export function encodeVideoId(
  providerName: ProviderName,
  sourceId: string,
): string {
  const payload = JSON.stringify({
    v: 1,
    provider: providerName,
    id: sourceId,
  });
  const iv = createHmac("sha256", VIDEO_ID_SECRET)
    .update(`video-id:${payload}`)
    .digest()
    .subarray(0, 12);
  const cipher = createCipheriv("aes-256-gcm", VIDEO_ID_KEY, iv);
  const encrypted = Buffer.concat([
    cipher.update(payload, "utf8"),
    cipher.final(),
  ]);
  const tag = cipher.getAuthTag();
  return [
    "v1",
    iv.toString("base64url"),
    encrypted.toString("base64url"),
    tag.toString("base64url"),
  ].join(".");
}

function encodePlaybackOptionId(
  details: VideoDetails,
  stream: VideoDetails["streams"][number],
  index: number,
): string {
  const payload = JSON.stringify({
    v: 1,
    provider: details.provider,
    id: details.id,
    stream: stream.id,
    index,
  });
  const iv = createHmac("sha256", VIDEO_ID_SECRET)
    .update(`playback-option-id:${payload}`)
    .digest()
    .subarray(0, 12);
  const cipher = createCipheriv("aes-256-gcm", VIDEO_ID_KEY, iv);
  const encrypted = Buffer.concat([
    cipher.update(payload, "utf8"),
    cipher.final(),
  ]);
  const tag = cipher.getAuthTag();
  return [
    "v1",
    iv.toString("base64url"),
    encrypted.toString("base64url"),
    tag.toString("base64url"),
  ].join(".");
}

function decodeVideoId(videoId: string): {
  providerName: ProviderName;
  sourceId: string;
} {
  const parts = videoId.split(".");
  if (parts.length !== 4 || parts[0] !== "v1") {
    throw new ApiError("BAD_REQUEST", "视频 ID 不合法。", 400);
  }
  try {
    const iv = Buffer.from(parts[1], "base64url");
    const encrypted = Buffer.from(parts[2], "base64url");
    const tag = Buffer.from(parts[3], "base64url");
    if (iv.length !== 12 || tag.length !== 16) {
      throw new Error("invalid video id shape");
    }
    const decipher = createDecipheriv("aes-256-gcm", VIDEO_ID_KEY, iv);
    decipher.setAuthTag(tag);
    const decoded = Buffer.concat([
      decipher.update(encrypted),
      decipher.final(),
    ]).toString("utf8");
    const payload = JSON.parse(decoded) as unknown;
    if (!isVideoIdPayload(payload)) {
      throw new Error("invalid video id payload");
    }
    return { providerName: payload.provider, sourceId: payload.id };
  } catch (error) {
    throw new ApiError("BAD_REQUEST", "视频 ID 不合法。", 400, error);
  }
}

function isVideoIdPayload(
  value: unknown,
): value is { v: 1; provider: ProviderName; id: string } {
  return (
    isRecord(value) &&
    value.v === 1 &&
    typeof value.id === "string" &&
    value.id.length > 0 &&
    typeof value.provider === "string" &&
    isProviderName(value.provider)
  );
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
