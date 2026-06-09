import { VideoAggregator } from "../src/services/VideoAggregator.js";
import type {
  SearchResult,
  VideoDetails,
  VideoStream,
  VideoSummary,
} from "../src/types.js";
import type { VideoProvider } from "../src/providers/VideoProvider.js";
import { ApiError } from "../src/errors.js";

const playableStream: VideoStream = {
  id: "stream-1",
  url: "https://cdn.example.test/video.mp4",
  originalUrl: "https://cdn.example.test/video.mp4",
  mimeType: "video/mp4",
  quality: "标清",
  supportsRange: true,
};

function summary(
  id: string,
  playable: boolean,
  provider: "bilibili" | "youtube" = "bilibili",
): VideoSummary {
  return {
    provider,
    id,
    videoKind: "vod",
    title: `视频 ${id}`,
    description: "测试视频",
    creators: ["测试频道"],
    sourceUrl: `https://example.test/${id}`,
    playable,
  };
}

function details(
  id: string,
  streams: VideoStream[],
  provider: "bilibili" | "youtube" = "bilibili",
): VideoDetails {
  return {
    ...summary(id, streams.length > 0, provider),
    sourceUrl: `https://example.test/${id}`,
    streams,
  };
}

function encoded(provider: string, id: string): string {
  return `${provider}:${Buffer.from(id, "utf8").toString("base64url")}`;
}

test("Bilibili 搜索结果不在列表阶段解析播放地址", async () => {
  const bilibiliProvider: VideoProvider = {
    name: "bilibili",
    async search(query, page, pageSize): Promise<SearchResult> {
      return {
        query,
        page,
        pageSize,
        total: 2,
        items: [summary("playable", true), summary("no-stream", true)],
      };
    },
    async getDetails(id) {
      return details(id, id === "playable" ? [playableStream] : []);
    },
    async getStreams() {
      return [playableStream];
    },
  };
  const youtubeProvider: VideoProvider = {
    name: "youtube",
    async search(query, page, pageSize): Promise<SearchResult> {
      return {
        query,
        page,
        pageSize,
        total: 1,
        items: [summary("youtube-only", false, "youtube")],
      };
    },
    async getDetails(id) {
      return {
        ...summary(id, false, "youtube"),
        sourceUrl: `https://youtube.example.test/${id}`,
        streams: [],
      };
    },
    async getStreams() {
      return [];
    },
  };

  const result = await new VideoAggregator([
    bilibiliProvider,
    youtubeProvider,
  ]).search("Dota", 1, 10);

  expect(result.query).toBe("Dota");
  expect(result.total).toBe(2);
  expect(result.items).toHaveLength(2);
  expect(result.items[0]).toMatchObject({
    id: encoded("bilibili", "playable"),
    videoKind: "vod",
    title: "视频 playable",
    playbackKind: "vod",
  });
  expect(result.items[0]).not.toHaveProperty("provider");
  expect(result.items[0]).not.toHaveProperty("sourceUrl");
  expect(result.items[0]).not.toHaveProperty("playbackOptions");
  expect(result.items[1].id).toBe(encoded("bilibili", "no-stream"));
});

test("详情接口拒绝没有点播播放地址的视频", async () => {
  const provider: VideoProvider = {
    name: "bilibili",
    async search(query, page, pageSize): Promise<SearchResult> {
      return { query, page, pageSize, total: 0, items: [] };
    },
    async getDetails(id) {
      return details(id, []);
    },
    async getStreams() {
      return [];
    },
  };

  await expect(
    new VideoAggregator([provider]).getDetails(encoded("bilibili", "empty")),
  ).rejects.toThrow("暂无可由 App 直接播放的点播媒体地址");
});

test("Bilibili 风控时用 YouTube 补源并保留原视频 ID", async () => {
  const bilibiliProvider: VideoProvider = {
    name: "bilibili",
    async search(query, page, pageSize): Promise<SearchResult> {
      return { query, page, pageSize, total: 0, items: [] };
    },
    async getDetails() {
      throw new ApiError(
        "PROVIDER_UNAVAILABLE",
        "B站播放地址请求触发风控，请稍后重试。",
        502,
        {
          reason: "bilibili_voucher",
          fallbackSearchQuery: "风控视频完整测试",
          title: "风控视频完整测试",
          description: "B站元数据",
          creator: "UP 主",
          thumbnailUrl: "https://i.example.test/bili.jpg",
          durationSeconds: 3600,
          sourceUrl: "https://www.bilibili.com/video/BVFAIL",
          year: "2026",
        },
      );
    },
    async getStreams() {
      return [];
    },
  };
  const youtubeProvider: VideoProvider = {
    name: "youtube",
    async search(query, page, pageSize): Promise<SearchResult> {
      return {
        query,
        page,
        pageSize,
        total: 1,
        items: [
          {
            ...summary("yt-match", true, "youtube"),
            title: "风控视频完整测试",
            durationSeconds: 3590,
          },
        ],
      };
    },
    async getDetails(id) {
      return details(id, [{ ...playableStream, id: "yt-stream" }], "youtube");
    },
    async getStreams() {
      return [playableStream];
    },
  };

  const result = await new VideoAggregator([
    bilibiliProvider,
    youtubeProvider,
  ]).getDetails(encoded("bilibili", "BVFAIL"));

  expect(result).toMatchObject({
    id: encoded("bilibili", "BVFAIL"),
    title: "风控视频完整测试",
    description: "B站元数据",
    year: "2026",
    creators: ["UP 主"],
    thumbnailUrl: "https://i.example.test/bili.jpg",
    durationSeconds: 3600,
    playbackOptions: [{ id: "yt-stream", mimeType: "video/mp4" }],
  });
});

test("YouTube 补源失败后返回 Bilibili 风控错误并短时熔断", async () => {
  let youtubeSearchCalls = 0;
  const bilibiliProvider: VideoProvider = {
    name: "bilibili",
    async search(query, page, pageSize): Promise<SearchResult> {
      return { query, page, pageSize, total: 0, items: [] };
    },
    async getDetails() {
      throw new ApiError(
        "PROVIDER_UNAVAILABLE",
        "B站播放地址请求触发风控，请稍后重试。",
        502,
        {
          reason: "bilibili_voucher",
          fallbackSearchQuery: "风控视频完整测试",
          title: "风控视频完整测试",
          sourceUrl: "https://www.bilibili.com/video/BVFAIL",
        },
      );
    },
    async getStreams() {
      return [];
    },
  };
  const youtubeProvider: VideoProvider = {
    name: "youtube",
    async search(query, page, pageSize): Promise<SearchResult> {
      youtubeSearchCalls += 1;
      return {
        query,
        page,
        pageSize,
        total: 1,
        items: [
          {
            ...summary("yt-match", true, "youtube"),
            title: "风控视频完整测试",
          },
        ],
      };
    },
    async getDetails() {
      throw new ApiError(
        "PROVIDER_UNAVAILABLE",
        "点播解析服务返回失败。",
        502,
        {
          reason: "youtube_bot_challenge",
        },
      );
    },
    async getStreams() {
      return [];
    },
  };
  const aggregator = new VideoAggregator([bilibiliProvider, youtubeProvider], {
    youtubeFallbackCooldownMs: 60_000,
  });
  const videoId = encoded("bilibili", "BVFAIL");

  await expect(aggregator.getDetails(videoId)).rejects.toMatchObject({
    code: "PROVIDER_UNAVAILABLE",
    message: "B站播放地址请求触发风控，请稍后重试。",
  });
  await expect(aggregator.getDetails(videoId)).rejects.toMatchObject({
    code: "PROVIDER_UNAVAILABLE",
    message: "B站播放地址请求触发风控，请稍后重试。",
  });

  expect(youtubeSearchCalls).toBe(1);
});

test("详情接口每次刷新播放地址避免过期缓存", async () => {
  let detailCalls = 0;
  const provider: VideoProvider = {
    name: "bilibili",
    async search(query, page, pageSize): Promise<SearchResult> {
      return {
        query,
        page,
        pageSize,
        total: 1,
        items: [summary("playable", true)],
      };
    },
    async getDetails(id) {
      detailCalls += 1;
      return details(id, [
        {
          ...playableStream,
          url: `https://cdn.example.test/video-${detailCalls}.mp4`,
          originalUrl: `https://cdn.example.test/video-${detailCalls}.mp4`,
        },
      ]);
    },
    async getStreams() {
      return [playableStream];
    },
  };
  const aggregator = new VideoAggregator([provider]);

  await aggregator.search("测试", 1, 1);
  const firstDetails = await aggregator.getDetails(
    encoded("bilibili", "playable"),
  );
  const secondDetails = await aggregator.getDetails(
    encoded("bilibili", "playable"),
  );

  expect(firstDetails.playbackOptions[0].url).toBe(
    "https://cdn.example.test/video-1.mp4",
  );
  expect(secondDetails.playbackOptions[0].url).toBe(
    "https://cdn.example.test/video-2.mp4",
  );
});

test("搜索会扫描来源后续页以填满公开分页", async () => {
  const provider: VideoProvider = {
    name: "bilibili",
    async search(query, page, pageSize): Promise<SearchResult> {
      const itemsByPage: Record<number, VideoSummary[]> = {
        1: [summary("no-stream-1", true), summary("no-stream-2", true)],
        2: [
          summary("playable-1", true),
          summary("playable-2", true),
          summary("playable-3", true),
          summary("playable-4", true),
        ],
      };
      return {
        query,
        page,
        pageSize,
        total: 6,
        items: itemsByPage[page] ?? [],
      };
    },
    async getDetails(id) {
      return details(id, id.startsWith("playable") ? [playableStream] : []);
    },
    async getStreams() {
      return [playableStream];
    },
  };
  const aggregator = new VideoAggregator([provider]);

  const page1 = await aggregator.search("测试", 1, 2);
  const page2 = await aggregator.search("测试", 2, 2);

  expect(page1.items.map((item) => item.id)).toEqual([
    encoded("bilibili", "no-stream-1"),
    encoded("bilibili", "no-stream-2"),
  ]);
  expect(page2.items.map((item) => item.id)).toEqual([
    encoded("bilibili", "playable-1"),
    encoded("bilibili", "playable-2"),
  ]);
});

test("搜索不会被无响应的视频来源阻塞", async () => {
  const hangingProvider: VideoProvider = {
    name: "youtube",
    async search(): Promise<SearchResult> {
      return new Promise<SearchResult>(() => {});
    },
    async getDetails() {
      return details("never", []);
    },
    async getStreams() {
      return [];
    },
  };
  const healthyProvider: VideoProvider = {
    name: "bilibili",
    async search(query, page, pageSize): Promise<SearchResult> {
      return {
        query,
        page,
        pageSize,
        total: 1,
        items: [summary("playable", true)],
      };
    },
    async getDetails() {
      return details("playable", [playableStream]);
    },
    async getStreams() {
      return [playableStream];
    },
  };

  const result = await new VideoAggregator([hangingProvider, healthyProvider], {
    providerSearchTimeoutMs: 10,
    detailResolveTimeoutMs: 50,
  }).search("测试", 1, 1);

  expect(result.items.map((item) => item.id)).toEqual([
    encoded("bilibili", "playable"),
  ]);
});
