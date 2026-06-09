import { expect, test } from "vitest";
import { BilibiliProvider } from "../src/providers/BilibiliProvider.js";
import { isDashManifestReference } from "../src/services/StreamManifests.js";

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

test("多分段 Bilibili MP4 不作为单 URL 点播流返回", async () => {
  const provider = new BilibiliProvider(async (input) => {
    const url = String(input);
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
          bvid: "BVSEGMENTED",
          title: "分段视频",
          desc: "简介",
          cid: 456,
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
              url: "https://example.test/video-1.mp4",
            },
            {
              order: 2,
              length: 1000,
              size: 2048,
              quality: 64,
              url: "https://example.test/video-2.mp4",
            },
          ],
        },
      });
    }
    return jsonResponse({ code: -1 });
  });

  const details = await provider.getDetails("BVSEGMENTED");

  expect(details.streams).toHaveLength(0);
});

test("Bilibili DASH 清单作为流式播放项返回", async () => {
  const provider = new BilibiliProvider(async (input) => {
    const url = String(input);
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
          bvid: "BVDASH",
          title: "DASH 视频",
          desc: "简介",
          cid: 789,
          owner: { name: "UP" },
        },
      });
    }
    if (url.includes("/x/player/wbi/playurl") && url.includes("fnval=16")) {
      return jsonResponse({
        code: 0,
        data: {
          dash: {
            duration: 120,
            minBufferTime: 1.5,
            video: [
              {
                id: 64,
                baseUrl: "https://example.test/video.m4s",
                bandwidth: 800000,
                mimeType: "video/mp4",
                codecs: "avc1.64001f",
                width: 1280,
                height: 720,
                frameRate: "30",
                SegmentBase: {
                  Initialization: "0-1000",
                  indexRange: "1001-1200",
                },
              },
            ],
            audio: [
              {
                id: 30280,
                baseUrl: "https://example.test/audio.m4s",
                bandwidth: 128000,
                mimeType: "audio/mp4",
                codecs: "mp4a.40.2",
                frameRate: "",
                SegmentBase: {
                  Initialization: "0-800",
                  indexRange: "801-900",
                },
              },
            ],
          },
        },
      });
    }
    return jsonResponse({ code: -1 });
  });

  const details = await provider.getDetails("BVDASH");

  expect(details.streams).toHaveLength(1);
  expect(details.streams[0]).toMatchObject({
    mimeType: "application/dash+xml",
    quality: "720P",
    supportsRange: true,
  });
  expect(isDashManifestReference(details.streams[0].url)).toBe(true);
});

test("Bilibili v_voucher 风控响应转成上游不可用错误", async () => {
  const provider = new BilibiliProvider(async (input) => {
    const url = String(input);
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
          bvid: "BVVOUCHER",
          title: "风控视频",
          desc: "简介",
          cid: 100,
          owner: { name: "UP" },
        },
      });
    }
    if (url.includes("/x/player/wbi/playurl")) {
      return jsonResponse({
        code: 0,
        message: "OK",
        data: { v_voucher: "voucher_test" },
      });
    }
    return jsonResponse({ code: -1 });
  });

  await expect(provider.getDetails("BVVOUCHER")).rejects.toMatchObject({
    code: "UPSTREAM_UNAVAILABLE",
    statusCode: 502,
    upstreamError: {
      reason: "bilibili_voucher",
      fallbackSearchQuery: "风控视频",
      title: "风控视频",
      creator: "UP",
      sourceUrl: "https://www.bilibili.com/video/BVVOUCHER",
    },
  });
});
