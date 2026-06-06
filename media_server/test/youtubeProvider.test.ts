import { YouTubeProvider } from "../src/providers/YouTubeProvider.js";
import type { VodExtractor } from "../src/services/VodExtractor.js";

const SEARCH_HTML = `
<html><head><script>var ytInitialData = ${JSON.stringify({
  contents: {
    twoColumnSearchResultsRenderer: {
      primaryContents: {
        sectionListRenderer: {
          contents: [
            {
              itemSectionRenderer: {
                contents: [
                  {
                    videoRenderer: {
                      videoId: "testvid1",
                      title: { runs: [{ text: "Test Video 1" }] },
                      lengthText: { simpleText: "10:30" },
                      ownerText: { runs: [{ text: "测试频道" }] },
                      thumbnail: {
                        thumbnails: [
                          {
                            url: "https://i.ytimg.com/vi/testvid1/hq720.jpg",
                            width: 720,
                            height: 404,
                          },
                        ],
                      },
                      descriptionSnippet: { runs: [{ text: "视频描述" }] },
                    },
                  },
                  {
                    videoRenderer: {
                      videoId: "testvid2",
                      title: { runs: [{ text: "Test Video 2" }] },
                      lengthText: { simpleText: "1:23:45" },
                      ownerText: { runs: [{ text: "另一个频道" }] },
                    },
                  },
                  {
                    videoRenderer: {
                      videoId: "livevid",
                      title: { runs: [{ text: "Live Video" }] },
                      ownerText: { runs: [{ text: "直播频道" }] },
                    },
                  },
                ],
              },
            },
          ],
        },
      },
    },
  },
})};</script></head><body></body></html>
`;

const OEMBED_JSON = {
  title: "Test Video Detail",
  author_name: "Detail Channel",
  thumbnail_url: "https://i.ytimg.com/vi/detailid/hqdefault.jpg",
};

const WATCH_HTML = `
<html><head><script>var ytInitialData = ${JSON.stringify({
  contents: {
    twoColumnWatchNextResults: {
      results: {
        results: {
          contents: [
            {
              videoPrimaryInfoRenderer: {
                dateText: { simpleText: "Jan 1, 2024" },
              },
            },
            { videoSecondaryInfoRenderer: {} },
          ],
        },
      },
    },
  },
})};</script></head><body></body></html>
`;

const VOD_EXTRACTOR: VodExtractor = {
  async extract() {
    return {
      id: "detailid",
      title: "Test Video Detail",
      duration: 630,
      live_status: "not_live",
      formats: [
        {
          format_id: "18",
          format_note: "360p",
          url: "https://rr.googlevideo.com/videoplayback?id=detailid",
          ext: "mp4",
          protocol: "https",
          width: 640,
          height: 360,
          filesize: 1024,
          vcodec: "avc1",
          acodec: "mp4a",
        },
      ],
    };
  },
};

const LIVE_EXTRACTOR: VodExtractor = {
  async extract() {
    return {
      id: "liveid",
      title: "Live Video",
      is_live: true,
      live_status: "is_live",
      formats: [],
    };
  },
};

function mockHtml(html: string): Response {
  return new Response(html, {
    status: 200,
    headers: { "content-type": "text/html" },
  });
}

function mockJson(data: unknown): Response {
  return new Response(JSON.stringify(data), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
}

test("搜索解析 ytInitialData 返回视频列表", async () => {
  const urls: string[] = [];
  const provider = new YouTubeProvider(async (input) => {
    urls.push(String(input));
    return mockHtml(SEARCH_HTML);
  }, VOD_EXTRACTOR);

  const result = await provider.search("测试", 1, 10);

  expect(result.total).toBe(2);
  expect(result.items).toHaveLength(2);
  expect(result.items[0]).toMatchObject({
    provider: "youtube",
    id: "testvid1",
    videoKind: "vod",
    title: "Test Video 1",
    playable: true,
    durationSeconds: 630,
    creators: ["测试频道"],
    sourceUrl: "https://www.youtube.com/watch?v=testvid1",
  });
  expect(result.items[1].durationSeconds).toBe(5025); // 1:23:45
  expect(result.items.some((item) => item.id === "livevid")).toBe(false);
});

test("搜索分页模拟", async () => {
  const provider = new YouTubeProvider(
    async () => mockHtml(SEARCH_HTML),
    VOD_EXTRACTOR,
  );

  const page1 = await provider.search("测试", 1, 1);
  expect(page1.items).toHaveLength(1);
  expect(page1.items[0].id).toBe("testvid1");

  const page2 = await provider.search("测试", 2, 1);
  expect(page2.items).toHaveLength(1);
  expect(page2.items[0].id).toBe("testvid2");
});

test("getDetails 通过 oEmbed + watch 页面获取详情", async () => {
  const calls: string[] = [];
  const provider = new YouTubeProvider(async (input) => {
    const url = String(input);
    calls.push(url);
    if (url.includes("oembed")) return mockJson(OEMBED_JSON);
    return mockHtml(WATCH_HTML);
  }, VOD_EXTRACTOR);

  const detail = await provider.getDetails("detailid");

  expect(detail).toMatchObject({
    provider: "youtube",
    id: "detailid",
    videoKind: "vod",
    title: "Test Video Detail",
    creators: ["Detail Channel"],
    year: "2024",
    playable: true,
    sourceUrl: "https://www.youtube.com/watch?v=detailid",
  });
  expect(detail.streams).toHaveLength(1);
  expect(detail.streams[0]).toMatchObject({
    url: "https://rr.googlevideo.com/videoplayback?id=detailid",
    mimeType: "video/mp4",
    quality: "360p",
  });
  expect(calls).toHaveLength(2);
  expect(calls[0]).toContain("oembed");
  expect(calls[1]).toContain("watch?v=detailid");
});

test("getStreams 拒绝直播视频", async () => {
  const provider = new YouTubeProvider(
    async () => mockHtml(""),
    LIVE_EXTRACTOR,
  );
  await expect(provider.getStreams("liveid")).rejects.toThrow(
    "当前只支持普通视频点播",
  );
});
