import { z } from "zod";
import type { ContentCategory } from "./types.js";
import rawConfig from "./contentCategories.json" with { type: "json" };

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

function buildCategory(
  config: z.infer<typeof categoryConfigSchema>,
): ContentCategory {
  const queryParts = [...config.keywords];
  if (config.excludes && config.excludes.length > 0) {
    queryParts.push("NOT", ...config.excludes);
  }
  return {
    id: config.id,
    title: config.title,
    description: config.description,
    query: queryParts.join(" "),
  };
}

function loadContentCategories(): ContentCategory[] {
  const parsed = categoriesConfigSchema.safeParse(rawConfig);
  if (!parsed.success) {
    throw new Error(
      `contentCategories.json 配置不合法: ${parsed.error.issues
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
