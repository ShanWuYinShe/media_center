import { VideoAggregator } from "../src/services/VideoAggregator.js";
import type {
  SearchResult,
  VideoDetails,
  VideoStream,
  VideoSummary,
} from "../src/types.js";
import type { VideoProvider } from "../src/providers/VideoProvider.js";

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

function details(id: string, streams: VideoStream[]): VideoDetails {
  return {
    ...summary(id, streams.length > 0),
    sourceUrl: `https://example.test/${id}`,
    streams,
  };
}

function encoded(provider: string, id: string): string {
  return `${provider}:${Buffer.from(id, "utf8").toString("base64url")}`;
}

test("搜索结果只公开已校验可直接播放的视频", async () => {
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
  expect(result.total).toBe(1);
  expect(result.items).toHaveLength(1);
  expect(result.items[0]).toMatchObject({
    id: encoded("bilibili", "playable"),
    videoKind: "vod",
    title: "视频 playable",
    playbackKind: "vod",
  });
  expect(result.items[0]).not.toHaveProperty("provider");
  expect(result.items[0]).not.toHaveProperty("sourceUrl");
  expect(result.items[0]).not.toHaveProperty("playbackOptions");
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
