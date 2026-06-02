import { ApiError } from "../src/errors.js";
import { YouTubeProvider } from "../src/providers/YouTubeProvider.js";

test("YouTube Provider 使用官方 API 搜索视频但不声明本地可播", async () => {
  const calls: string[] = [];
  const provider = new YouTubeProvider("key", async (input) => {
    calls.push(String(input));
    return response({
      pageInfo: { totalResults: 1 },
      items: [
        {
          id: { videoId: "abc123" },
          snippet: {
            title: "Dota2 Full Match",
            description: "match replay",
            channelTitle: "赛事频道",
            publishedAt: "2024-01-01T00:00:00Z",
            thumbnails: { high: { url: "https://img.example/high.jpg" } },
          },
        },
      ],
    });
  });

  const result = await provider.search("Dota2 match", 1, 10);

  expect(new URL(calls[0]).hostname).toBe("www.googleapis.com");
  expect(result.items[0]).toMatchObject({
    provider: "youtube",
    id: "abc123",
    playable: false,
    sourceUrl: "https://www.youtube.com/watch?v=abc123",
  });
});

test("YouTube Provider 不返回直链播放流", async () => {
  const provider = new YouTubeProvider("key", async () => response({}));

  await expect(provider.getStreams()).rejects.toBeInstanceOf(ApiError);
});

function response(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: {
      "content-type": "application/json",
    },
  });
}
