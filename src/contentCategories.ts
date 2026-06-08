import { z } from "zod";
import { readFileSync } from "node:fs";
import type { ContentCategory } from "./types.js";
import rawDefaultConfig from "./contentCategories.json" with { type: "json" };

export const categoryConfigSchema = z.object({
  id: z.string().min(1),
  title: z.string().min(1),
  description: z.string().min(1),
  searchQuery: z.string().trim().min(1).optional(),
  keywords: z.array(z.string().trim().min(1)).min(1),
  excludes: z.array(z.string().trim().min(1)).optional(),
});

export const categoriesConfigSchema = z.object({
  categories: z.array(categoryConfigSchema).min(1),
});

function buildCategory(
  config: z.infer<typeof categoryConfigSchema>,
): ContentCategory {
  const query = config.searchQuery ?? config.keywords[0];
  return {
    id: config.id,
    title: config.title,
    description: config.description,
    query,
    keywords: config.keywords,
    excludes: config.excludes ?? [],
  };
}

export function loadContentCategories(
  configPath = runtimeCategoriesConfigPath(),
): ContentCategory[] {
  const source = configPath ?? "contentCategories.json";
  const rawConfig = configPath ? readJsonFile(configPath) : rawDefaultConfig;
  const parsed = categoriesConfigSchema.safeParse(rawConfig);
  if (!parsed.success) {
    throw new Error(
      `${source} 配置不合法: ${parsed.error.issues
        .map((issue) => `${issue.path.join(".")}: ${issue.message}`)
        .join("; ")}`,
    );
  }

  const seen = new Set<string>();
  for (const category of parsed.data.categories) {
    if (seen.has(category.id)) {
      throw new Error(
        `contentCategories.json 存在重复的分类 id: ${category.id}`,
      );
    }
    seen.add(category.id);
  }

  return parsed.data.categories.map(buildCategory);
}

export const contentCategories: ContentCategory[] = loadContentCategories();

export function findContentCategory(id?: string): ContentCategory | undefined {
  if (!id) {
    return undefined;
  }
  return contentCategories.find((category) => category.id === id);
}

function runtimeCategoriesConfigPath(): string | undefined {
  return (
    process.env.CONTENT_CATEGORIES_FILE ??
    process.env.MEDIA_SERVER_CONTENT_CATEGORIES_FILE
  );
}

function readJsonFile(path: string): unknown {
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch (error) {
    throw new Error(`读取分类配置失败: ${path}`, { cause: error });
  }
}
