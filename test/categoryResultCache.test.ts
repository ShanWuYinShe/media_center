import { CategoryResultCache } from "../src/services/CategoryResultCache.js";
import type { ContentCategory, PublicVideoSummary } from "../src/types.js";

const category: ContentCategory = {
  id: "movies",
  title: "电影",
  description: "测试分类",
  query: "电影 完整版",
  sourceQueries: {},
  keywords: ["电影"],
  excludes: [],
};

function item(id: string): PublicVideoSummary {
  return {
    id,
    videoKind: "vod",
    title: `视频 ${id}`,
    description: "",
    creators: [],
    playbackKind: "vod",
  };
}

test("分类分页返回同一服务端快照中的结果", async () => {
  let generation = 0;
  const cache = new CategoryResultCache(
    {
      async searchItems(_query, targetCount) {
        generation += 1;
        return Array.from({ length: targetCount }, (_, index) =>
          item(`g${generation}-${index + 1}`),
        );
      },
    },
    [category],
    { defaultPages: 2, defaultPageSize: 2 },
  );

  await cache.refreshDefaultPages();
  const page1 = await cache.getPage(category, 1, 2);
  const page2 = await cache.getPage(category, 2, 2);

  expect(page1.items.map((video) => video.id)).toEqual(["g1-1", "g1-2"]);
  expect(page2.items.map((video) => video.id)).toEqual(["g1-3", "g1-4"]);
  expect(generation).toBe(1);
});

test("分类分页超过当前快照时按请求继续扩展", async () => {
  const requestedCounts: number[] = [];
  const cache = new CategoryResultCache(
    {
      async searchItems(_query, targetCount) {
        requestedCounts.push(targetCount);
        return Array.from({ length: targetCount }, (_, index) =>
          item(`v${index + 1}`),
        );
      },
    },
    [category],
    { defaultPages: 1, defaultPageSize: 2 },
  );

  await cache.refreshDefaultPages();
  const page3 = await cache.getPage(category, 3, 2);

  expect(page3.items.map((video) => video.id)).toEqual(["v5", "v6"]);
  expect(requestedCounts).toEqual([2, 6]);
});
