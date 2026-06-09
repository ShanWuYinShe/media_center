import { HttpVodExtractor } from "../src/services/VodExtractor.js";

test("HTTP 点播解析器调用内部 extractor 服务", async () => {
  const calls: Array<{ url: string; init?: RequestInit }> = [];
  const extractor = new HttpVodExtractor(
    "http://vod_extractor:8790/extract",
    1000,
    async (input, init) => {
      calls.push({ url: String(input), init });
      return new Response(
        JSON.stringify({
          id: "video-1",
          live_status: "not_live",
          formats: [{ url: "https://cdn.example.test/video.mp4", ext: "mp4" }],
        }),
        { headers: { "content-type": "application/json" } },
      );
    },
  );

  const result = await extractor.extract("https://www.youtube.com/watch?v=x");

  expect(result.id).toBe("video-1");
  expect(calls).toHaveLength(1);
  expect(calls[0].url).toBe("http://vod_extractor:8790/extract");
  expect(calls[0].init?.method).toBe("POST");
  expect(calls[0].init?.body).toBe(
    JSON.stringify({ url: "https://www.youtube.com/watch?v=x" }),
  );
});

test("HTTP 点播解析器把服务失败转成 Provider 错误", async () => {
  const extractor = new HttpVodExtractor(
    "http://vod_extractor:8790/extract",
    1000,
    async () =>
      new Response(JSON.stringify({ error: "extract failed" }), {
        status: 502,
        headers: { "content-type": "application/json" },
      }),
  );

  await expect(
    extractor.extract("https://www.youtube.com/watch?v=x"),
  ).rejects.toMatchObject({
    code: "UPSTREAM_UNAVAILABLE",
    statusCode: 502,
  });
});
