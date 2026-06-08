import { expect, test } from "vitest";
import { BilibiliProvider } from "../src/providers/BilibiliProvider.js";

function jsonResponse(data: unknown): Response {
  return new Response(JSON.stringify(data), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
}

test("详情和播放地址使用 WBI 签名接口", async () => {
  const urls: string[] = [];
  const provider = new BilibiliProvider(async (input) => {
    const url = String(input);
    urls.push(url);
    if (url.includes("/x/web-interface/nav")) {
      return jsonResponse({
        code: 0,
        data: {
          wbi_img: {
            img_url: `https://i0.hdslb.com/bfs/wbi/${"a".repeat(64)}.png`,
            sub_url: `https://i0.hdslb.com/bfs/wbi/${"b".repeat(64)}.png`,
          },
        },
      });
    }
    if (url.includes("/x/web-interface/wbi/view")) {
      return jsonResponse({
        code: 0,
        data: {
          bvid: "BVTEST",
          title: "测试视频",
          desc: "简介",
          cid: 123,
          owner: { name: "UP" },
        },
      });
    }
    if (url.includes("/x/player/wbi/playurl")) {
      return jsonResponse({
        code: 0,
        data: {
          quality: 64,
          accept_quality: [64],
          accept_description: ["高清 720P"],
          durl: [
            {
              order: 1,
              length: 1000,
              size: 2048,
              quality: 64,
              url: "https://example.test/video.mp4",
            },
          ],
        },
      });
    }
    return jsonResponse({ code: -1 });
  });

  const details = await provider.getDetails("BVTEST");

  expect(details.streams).toHaveLength(1);
  expect(details.streams[0]).toMatchObject({
    url: "https://example.test/video.mp4",
    quality: "720P",
  });
  expect(
    urls.some(
      (url) =>
        url.includes("/x/web-interface/wbi/view?") && url.includes("w_rid="),
    ),
  ).toBe(true);
  expect(
    urls.some(
      (url) => url.includes("/x/player/wbi/playurl?") && url.includes("w_rid="),
    ),
  ).toBe(true);
});
