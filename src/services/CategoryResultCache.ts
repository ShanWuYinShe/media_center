import type {
  ContentCategory,
  PublicContentCategory,
  PublicSearchResult,
  PublicVideoSummary,
} from "../types.js";
import type { VideoAggregator } from "./VideoAggregator.js";

type CategorySearchSource = Pick<VideoAggregator, "searchItems">;
type CategoryListFilter = Pick<VideoAggregator, "filterListItems">;

type CategorySnapshot = {
  items: PublicVideoSummary[];
  updatedAt: number;
};

type CategoryResultCacheOptions = {
  defaultPages?: number;
  defaultPageSize?: number;
  now?: () => number;
};

type RefreshErrorHandler = (category: ContentCategory, error: unknown) => void;

const DEFAULT_CACHED_CATEGORY_PAGES = 100;
const DEFAULT_CATEGORY_PAGE_SIZE = 18;

export class CategoryResultCache {
  private readonly snapshots = new Map<string, CategorySnapshot>();
  private readonly extensionTasks = new Map<string, Promise<void>>();

  constructor(
    private readonly source: CategorySearchSource & Partial<CategoryListFilter>,
    private readonly categories: ContentCategory[],
    private readonly options: CategoryResultCacheOptions = {},
  ) {}

  async getPage(
    category: ContentCategory,
    page: number,
    pageSize: number,
  ): Promise<PublicSearchResult> {
    const end = page * pageSize;
    await this.ensureItems(category, end);
    const snapshot = this.snapshots.get(category.id);
    const items = this.filterListItems(snapshot?.items ?? []);
    const start = (page - 1) * pageSize;

    return {
      query: "",
      category: toPublicCategory(category),
      page,
      pageSize,
      total: items.length,
      items: items.slice(start, end),
    };
  }

  async refreshDefaultPages(onError?: RefreshErrorHandler): Promise<void> {
    const target = this.defaultTargetCount();
    for (const category of this.categories) {
      try {
        await this.refreshCategory(category, target);
      } catch (error) {
        if (onError) {
          onError(category, error);
        } else {
          throw error;
        }
      }
    }
  }

  async refreshCategory(
    category: ContentCategory,
    targetCount = this.defaultTargetCount(),
  ): Promise<void> {
    const currentCount = this.snapshots.get(category.id)?.items.length ?? 0;
    const items = await this.fetchCategoryItems(
      category,
      Math.max(targetCount, currentCount),
    );
    this.storeSnapshot(category.id, items, true);
  }

  private async ensureItems(
    category: ContentCategory,
    targetCount: number,
  ): Promise<void> {
    const snapshot = this.snapshots.get(category.id);
    if (snapshot && snapshot.items.length >= targetCount) {
      return;
    }

    const existingTask = this.extensionTasks.get(category.id);
    if (existingTask) {
      await existingTask;
      const updated = this.snapshots.get(category.id);
      if (updated && updated.items.length >= targetCount) {
        return;
      }
    }

    const task = this.extendCategory(category, targetCount);
    this.extensionTasks.set(category.id, task);
    try {
      await task;
    } finally {
      if (this.extensionTasks.get(category.id) === task) {
        this.extensionTasks.delete(category.id);
      }
    }
  }

  private async extendCategory(
    category: ContentCategory,
    targetCount: number,
  ): Promise<void> {
    const currentCount = this.snapshots.get(category.id)?.items.length ?? 0;
    const items = await this.fetchCategoryItems(
      category,
      Math.max(targetCount, currentCount),
    );
    this.storeSnapshot(category.id, items, false);
  }

  private async fetchCategoryItems(
    category: ContentCategory,
    targetCount: number,
  ): Promise<PublicVideoSummary[]> {
    if (targetCount <= 0) {
      return [];
    }
    return this.source.searchItems(category.query, targetCount, category);
  }

  private storeSnapshot(
    categoryId: string,
    items: PublicVideoSummary[],
    replaceShorter: boolean,
  ): void {
    const current = this.snapshots.get(categoryId);
    if (!current || replaceShorter || items.length >= current.items.length) {
      this.snapshots.set(categoryId, {
        items,
        updatedAt: this.now(),
      });
    }
  }

  private filterListItems(items: PublicVideoSummary[]): PublicVideoSummary[] {
    return this.source.filterListItems?.(items) ?? items;
  }

  private defaultTargetCount(): number {
    return (
      (this.options.defaultPages ?? DEFAULT_CACHED_CATEGORY_PAGES) *
      (this.options.defaultPageSize ?? DEFAULT_CATEGORY_PAGE_SIZE)
    );
  }

  private now(): number {
    return this.options.now?.() ?? Date.now();
  }
}

function toPublicCategory(category: ContentCategory): PublicContentCategory {
  return {
    id: category.id,
    title: category.title,
    description: category.description,
  };
}
