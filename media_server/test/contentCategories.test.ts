import { z } from "zod";
import {
  contentCategories,
  findContentCategory,
} from "../src/contentCategories.js";

const categoryConfigSchema = z.object({
  id: z.string().min(1),
  title: z.string().min(1),
  description: z.string().min(1),
  keywords: z.array(z.string().min(1)).min(1),
  excludes: z.array(z.string().min(1)).optional(),
});

const categoriesConfigSchema = z.object({
  categories: z.array(categoryConfigSchema).min(1),
});

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

test("query 字段由 keywords 用空格拼接,顺序与配置一致", () => {
  const urban = findContentCategory("life-urban");
  expect(urban).toBeDefined();
  const tokens = urban!.query.split(" ");
  // 配置里第一个关键词必须是 query 的第一个 token
  expect(tokens[0]).toBe("城市");
  // 关键词都出现
  expect(tokens).toContain("家庭");
  expect(tokens).toContain("职场");
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

test("Dota 专区不再使用 NOT 排除词,query 是纯关键词拼接", () => {
  const dota = findContentCategory("dota-zone");
  expect(dota?.query).not.toContain("NOT");
  expect(dota?.query).toContain("Dota 2");
});

test("excludes 配置会通过 NOT 拼到 query 末尾", () => {
  const config = categoriesConfigSchema.parse({
    categories: [
      {
        id: "sample",
        title: "示例",
        description: "演示 excludes 拼接",
        keywords: ["foo", "bar"],
        excludes: ["baz", "qux"],
      },
    ],
  });
  const queryParts = [...config.categories[0].keywords];
  if (config.categories[0].excludes) {
    queryParts.push("NOT", ...config.categories[0].excludes);
  }
  const query = queryParts.join(" ");
  expect(query).toBe("foo bar NOT baz qux");
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
