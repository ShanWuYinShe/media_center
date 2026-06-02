import { InternetArchiveProvider } from "../src/providers/InternetArchiveProvider.js";

test("搜索公开视频时会使用安全集合和可播放格式过滤", async () => {
  const calls: string[] = [];
  const provider = new InternetArchiveProvider(async (input) => {
    calls.push(String(input));
    return response({
      response: {
        numFound: 1,
        docs: [
          {
            identifier: "movie-1",
            title: "Public Movie",
            description: "<p>Open video</p>",
            date: "1945-01-01",
            creator: ["Archive"],
          },
        ],
      },
    });
  });

  const result = await provider.search("city", 1, 10);

  expect(result.items).toHaveLength(1);
  expect(result.items[0]).toMatchObject({
    provider: "internet-archive",
    id: "movie-1",
    title: "Public Movie",
    description: "Open video",
  });
  const archiveQuery = new URL(calls[0]).searchParams.get("q") ?? "";
  expect(archiveQuery).toContain("collection:(prelinger OR feature_films");
  expect(archiveQuery).toContain('format:("h.264" OR MPEG4');
  expect(archiveQuery).toContain("city");
});

test("搜索会保留 Dota 对局过滤词并去掉危险括号", async () => {
  const calls: string[] = [];
  const provider = new InternetArchiveProvider(async (input) => {
    calls.push(String(input));
    return response({ response: { numFound: 0, docs: [] } });
  });

  await provider.search("Dota2 match NOT guide (trailer)", 1, 10);

  const archiveQuery = new URL(calls[0]).searchParams.get("q") ?? "";
  expect(archiveQuery).toContain("Dota2 match NOT guide trailer");
  expect(archiveQuery).not.toContain("(trailer)");
});

test("详情只返回支持 Range 的 MP4/WebM 播放流并按清晰度排序", async () => {
  const provider = new InternetArchiveProvider(async (input, init) => {
    const url = String(input);
    if (init?.method === "HEAD") {
      return new Response(null, {
        status: 200,
        headers: {
          "accept-ranges": "bytes",
          "content-length": "2000",
        },
      });
    }
    if (url.includes("/metadata/")) {
      return response({
        metadata: {
          identifier: "movie-1",
          title: "Public Movie",
          description: "Open video",
        },
        files: [
          {
            name: "low.mp4",
            format: "512Kb MPEG4",
            size: "1000",
            height: "360",
            width: "640",
          },
          {
            name: "hd.mp4",
            format: "h.264",
            size: "2000",
            height: "720",
            width: "1280",
          },
          {
            name: "archive.mkv",
            format: "Matroska",
            size: "3000",
          },
        ],
      });
    }
    throw new Error(`Unexpected URL ${url}`);
  });

  const details = await provider.getDetails("movie-1");

  expect(details.streams.map((stream) => stream.quality)).toEqual([
    "720p",
    "360p",
  ]);
  expect(
    details.streams.every((stream) => stream.mimeType === "video/mp4"),
  ).toBe(true);
});

function response(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: {
      "content-type": "application/json",
    },
  });
}
