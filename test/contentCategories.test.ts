import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  categoriesConfigSchema,
  contentCategories,
  findContentCategory,
  loadContentCategories,
} from "../src/contentCategories.js";

test("默认配置加载后,每个分类都有完整的对外字段", () => {
  expect(contentCategories.length).toBeGreaterThan(0);
  for (const category of contentCategories) {
    expect(category.id).toBeTruthy();
    expect(category.title).toBeTruthy();
    expect(category.description).toBeTruthy();
    expect(category.query).toBeTruthy();
  }
});

test("默认配置中所有 id 唯一,没有重复", () => {
  const ids = contentCategories.map((c) => c.id);
  expect(new Set(ids).size).toBe(ids.length);
});

test("默认配置只包含产品指定的五个一级分类", () => {
  expect(contentCategories.map((category) => category.id)).toEqual([
    "movies",
    "tv-series",
    "documentaries",
    "red-classic",
    "dota-zone",
  ]);
});

test("query 字段使用短搜索词,内部关键词只用于相关性评分", () => {
  const documentaries = findContentCategory("documentaries");
  expect(documentaries).toBeDefined();
  expect(documentaries!.query).toBe("纪录片 完整版");
  expect(documentaries!.keywords).toContain("历史");
  expect(documentaries!.query).not.toContain("历史");
});

test("findContentCategory 对未传或未定义的 id 返回 undefined", () => {
  expect(findContentCategory()).toBeUndefined();
  expect(findContentCategory("")).toBeUndefined();
  expect(findContentCategory("nonexistent-category")).toBeUndefined();
});

test("findContentCategory 对已存在的 id 返回对应分类", () => {
  const dota = findContentCategory("dota-zone");
  expect(dota?.title).toBe("Dota 专区");
});

test("Dota 专区偏向 Dota1 且不使用 NOT 排除词", () => {
  const dota = findContentCategory("dota-zone");
  expect(dota?.query).not.toContain("NOT");
  expect(dota?.query).toContain("Dota1");
  expect(dota?.keywords).toContain("魔兽争霸3");
  expect(dota?.keywords).toContain("Dota2");
  expect(dota!.keywords.indexOf("Dota1 完整比赛")).toBeLessThan(
    dota!.keywords.indexOf("Dota2"),
  );
});

test("excludes 配置保留为内部过滤规则,不会污染搜索 query", () => {
  const config = categoriesConfigSchema.parse({
    categories: [
      {
        id: "sample",
        title: "示例",
        description: "演示 excludes 拼接",
        searchQuery: "foo",
        keywords: ["foo", "bar"],
        excludes: ["baz", "qux"],
      },
    ],
  });
  expect(config.categories[0].searchQuery).toBe("foo");
  expect(config.categories[0].excludes).toEqual(["baz", "qux"]);
});

test("可以从运行时 JSON 文件加载分类配置", () => {
  const dir = mkdtempSync(join(tmpdir(), "media-categories-"));
  const path = join(dir, "contentCategories.json");
  writeFileSync(
    path,
    JSON.stringify({
      categories: [
        {
          id: "local-test",
          title: "本地测试",
          description: "由运行时配置提供",
          searchQuery: "本地 搜索",
          keywords: ["本地", "搜索"],
          excludes: ["预告"],
        },
      ],
    }),
  );

  const categories = loadContentCategories(path);

  expect(categories).toEqual([
    {
      id: "local-test",
      title: "本地测试",
      description: "由运行时配置提供",
      query: "本地 搜索",
      keywords: ["本地", "搜索"],
      excludes: ["预告"],
    },
  ]);
});

test("配置缺关键词或关键词为空数组时被 Zod 拒绝", () => {
  const badCases = [
    { categories: [] },
    { categories: [{ id: "x", title: "t", description: "d", keywords: [] }] },
    { categories: [{ id: "x", title: "t", description: "d", keywords: [""] }] },
  ];
  for (const bad of badCases) {
    const result = categoriesConfigSchema.safeParse(bad);
    expect(result.success).toBe(false);
  }
});

test("缺少顶层 categories 字段时被 Zod 拒绝", () => {
  const result = categoriesConfigSchema.safeParse({});
  expect(result.success).toBe(false);
});
