import { z } from "zod";
import { readFileSync } from "node:fs";
import type { ContentCategory, MediaSourceConfig, ProviderName } from "./types.js";
import rawDefaultConfig from "./contentCategories.json" with { type: "json" };

const providerNameSchema = z.enum(["internet-archive", "youtube", "bilibili"]);

export const mediaSourceConfigSchema = z.object({
  id: providerNameSchema,
  enabled: z.boolean().default(true),
});

export const categoryConfigSchema = z.object({
  id: z.string().min(1),
  title: z.string().min(1),
  description: z.string().min(1),
  searchQuery: z.string().trim().min(1).optional(),
  sourceQueries: z
    .partialRecord(providerNameSchema, z.string().trim().min(1))
    .optional(),
  keywords: z.array(z.string().trim().min(1)).min(1),
  excludes: z.array(z.string().trim().min(1)).optional(),
});

export const categoriesConfigSchema = z.object({
  sources: z.array(mediaSourceConfigSchema).optional(),
  categories: z.array(categoryConfigSchema).min(1),
});

export type MediaServerConfig = {
  sources: MediaSourceConfig[];
  categories: ContentCategory[];
};

export const defaultMediaSources: MediaSourceConfig[] = [
  { id: "bilibili", enabled: true },
  { id: "youtube", enabled: false },
  { id: "internet-archive", enabled: false },
];

function buildCategory(
  config: z.infer<typeof categoryConfigSchema>,
): ContentCategory {
  const query = config.searchQuery ?? config.keywords[0];
  return {
    id: config.id,
    title: config.title,
    description: config.description,
    query,
    sourceQueries: config.sourceQueries ?? {},
    keywords: config.keywords,
    excludes: config.excludes ?? [],
  };
}

export function loadMediaServerConfig(
  configPath = runtimeCategoriesConfigPath(),
): MediaServerConfig {
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

  return {
    sources: normalizeMediaSources(parsed.data.sources),
    categories: parsed.data.categories.map(buildCategory),
  };
}

export function loadContentCategories(
  configPath = runtimeCategoriesConfigPath(),
): ContentCategory[] {
  return loadMediaServerConfig(configPath).categories;
}

export const mediaServerConfig: MediaServerConfig = loadMediaServerConfig();
export const contentCategories: ContentCategory[] = mediaServerConfig.categories;

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

function normalizeMediaSources(
  sources: z.infer<typeof mediaSourceConfigSchema>[] | undefined,
): MediaSourceConfig[] {
  if (!sources || sources.length === 0) {
    return defaultMediaSources;
  }
  const seen = new Set<ProviderName>();
  const normalized: MediaSourceConfig[] = [];
  for (const source of sources) {
    if (seen.has(source.id)) {
      throw new Error(`contentCategories.json 存在重复的视频来源 id: ${source.id}`);
    }
    seen.add(source.id);
    normalized.push({ id: source.id, enabled: source.enabled });
  }
  for (const source of defaultMediaSources) {
    if (!seen.has(source.id)) {
      normalized.push({ ...source, enabled: false });
    }
  }
  if (!normalized.some((source) => source.enabled)) {
    throw new Error("contentCategories.json 至少需要启用一个视频来源");
  }
  return normalized;
}
