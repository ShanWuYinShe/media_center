import { ApiError } from "../errors.js";
import type {
  ProviderName,
  SearchResult,
  VideoDetails,
  VideoStream,
  VideoSummary,
} from "../types.js";
import type { VideoProvider } from "./VideoProvider.js";

type FetchLike = typeof fetch;

type ArchiveDoc = {
  identifier?: string;
  title?: string;
  description?: string | string[];
  date?: string;
  creator?: string | string[];
};

type ArchiveSearchResponse = {
  response?: {
    numFound?: number;
    docs?: ArchiveDoc[];
  };
};

type ArchiveFile = {
  name?: string;
  format?: string;
  size?: string;
  length?: string;
  width?: string;
  height?: string;
  source?: string;
};

type ArchiveMetadataResponse = {
  metadata?: {
    identifier?: string;
    title?: string;
    description?: string | string[];
    date?: string;
    creator?: string | string[];
    licenseurl?: string;
  };
  files?: ArchiveFile[];
};

const PROVIDER: ProviderName = "internet-archive";
const BASE_URL = "https://archive.org";
const PLAYABLE_FORMATS = new Set([
  "h.264",
  "mpeg4",
  "512kb mpeg4",
  "webm",
  "matroska/webm",
]);
const SAFE_COLLECTION_QUERY =
  "collection:(prelinger OR feature_films OR classic_tv OR opensource_movies)";
const PLAYABLE_FORMAT_QUERY =
  'format:("h.264" OR MPEG4 OR "512Kb MPEG4" OR WebM)';

export class InternetArchiveProvider implements VideoProvider {
  readonly name = PROVIDER;

  constructor(private readonly fetcher: FetchLike = fetch) {}

  async search(
    query: string,
    page: number,
    pageSize: number,
  ): Promise<SearchResult> {
    const textQuery = buildTextQuery(query);
    const q = [
      "mediatype:movies",
      SAFE_COLLECTION_QUERY,
      PLAYABLE_FORMAT_QUERY,
      `(title:(${textQuery}) OR description:(${textQuery}))`,
    ].join(" AND ");

    const url = new URL("/advancedsearch.php", BASE_URL);
    url.searchParams.set("q", q);
    url.searchParams.set("output", "json");
    url.searchParams.set("page", String(page));
    url.searchParams.set("rows", String(pageSize));
    url.searchParams.append("sort[]", "downloads desc");
    for (const field of [
      "identifier",
      "title",
      "description",
      "date",
      "creator",
    ]) {
      url.searchParams.append("fl[]", field);
    }

    function buildTextQuery(query: string): string {
      const terms = query
        .split(/\s+/)
        .map((term) => term.trim())
        .filter(Boolean)
        .map((term) => term.replace(/[()"]/g, ""));

      return terms.length > 0 ? terms.join(" ") : query.replace(/[()"]/g, "");
    }

    const data = await this.getJson<ArchiveSearchResponse>(url);
    const docs = data.response?.docs ?? [];
    return {
      query,
      page,
      pageSize,
      total: data.response?.numFound ?? docs.length,
      items: docs
        .filter((doc): doc is ArchiveDoc & { identifier: string } =>
          Boolean(doc.identifier),
        )
        .map((doc) => this.toSummary(doc)),
    };
  }

  async getDetails(id: string): Promise<VideoDetails> {
    const metadata = await this.fetchMetadata(id);
    const identifier = metadata.metadata?.identifier ?? id;
    const streams = await this.resolveStreams(identifier, metadata.files ?? []);
    if (streams.length === 0) {
      throw new ApiError(
        "NO_PLAYABLE_STREAM",
        "该视频没有可直接播放的 MP4 或 WebM 流。",
        422,
      );
    }

    return {
      ...this.toSummary({ ...metadata.metadata, identifier }),
      licenseUrl: metadata.metadata?.licenseurl,
      sourceUrl: `${BASE_URL}/details/${encodeURIComponent(identifier)}`,
      streams,
    };
  }

  async getStreams(id: string): Promise<VideoStream[]> {
    const metadata = await this.fetchMetadata(id);
    const streams = await this.resolveStreams(
      metadata.metadata?.identifier ?? id,
      metadata.files ?? [],
    );
    if (streams.length === 0) {
      throw new ApiError(
        "NO_PLAYABLE_STREAM",
        "该视频没有可直接播放的 MP4 或 WebM 流。",
        422,
      );
    }
    return streams;
  }

  private async fetchMetadata(id: string): Promise<ArchiveMetadataResponse> {
    const url = new URL(`/metadata/${encodeURIComponent(id)}`, BASE_URL);
    const metadata = await this.getJson<ArchiveMetadataResponse>(url);
    if (!metadata.metadata?.identifier) {
      throw new ApiError("NOT_FOUND", "未找到视频详情。", 404);
    }
    return metadata;
  }

  private async resolveStreams(
    identifier: string,
    files: ArchiveFile[],
  ): Promise<VideoStream[]> {
    const candidates = files.filter((file) => this.isPlayable(file));
    const streams = await Promise.all(
      candidates.map(async (file) => {
        const originalUrl = this.fileUrl(identifier, file.name ?? "");
        const head = await this.resolveHead(originalUrl);
        const height = parseNumber(file.height);
        return {
          id: file.name ?? originalUrl,
          url: head.finalUrl,
          originalUrl,
          mimeType: this.mimeType(file),
          quality: qualityLabel(height, file.format),
          width: parseNumber(file.width),
          height,
          sizeBytes: parseNumber(file.size) ?? head.sizeBytes,
          supportsRange: head.supportsRange,
        };
      }),
    );

    return streams
      .filter((stream) => stream.supportsRange)
      .sort((a, b) => (b.height ?? 0) - (a.height ?? 0));
  }

  private async resolveHead(url: string): Promise<{
    finalUrl: string;
    supportsRange: boolean;
    sizeBytes?: number;
  }> {
    try {
      const response = await this.fetcher(url, {
        method: "HEAD",
        redirect: "follow",
      });
      if (!response.ok) {
        return { finalUrl: url, supportsRange: false };
      }
      const ranges = response.headers.get("accept-ranges")?.toLowerCase();
      return {
        finalUrl: response.url || url,
        supportsRange: ranges === "bytes",
        sizeBytes: parseNumber(
          response.headers.get("content-length") ?? undefined,
        ),
      };
    } catch (error) {
      throw new ApiError(
        "PROVIDER_UNAVAILABLE",
        "视频源暂时不可用。",
        502,
        error,
      );
    }
  }

  private isPlayable(file: ArchiveFile): boolean {
    const name = file.name?.toLowerCase() ?? "";
    const format = file.format?.toLowerCase() ?? "";
    const extensionPlayable = name.endsWith(".mp4") || name.endsWith(".webm");
    return (
      extensionPlayable &&
      PLAYABLE_FORMATS.has(format) &&
      file.source !== "metadata"
    );
  }

  private mimeType(file: ArchiveFile): "video/mp4" | "video/webm" {
    return file.name?.toLowerCase().endsWith(".webm")
      ? "video/webm"
      : "video/mp4";
  }

  private fileUrl(identifier: string, name: string): string {
    const encodedName = name.split("/").map(encodeURIComponent).join("/");
    return `${BASE_URL}/download/${encodeURIComponent(identifier)}/${encodedName}`;
  }

  private toSummary(doc: ArchiveDoc & { identifier: string }): VideoSummary {
    const description = Array.isArray(doc.description)
      ? doc.description.join("\n")
      : doc.description;
    return {
      provider: PROVIDER,
      id: doc.identifier,
      title: doc.title?.trim() || doc.identifier,
      description: stripHtml(description ?? ""),
      year: doc.date?.slice(0, 4),
      creators: normalizeArray(doc.creator),
      thumbnailUrl: `${BASE_URL}/services/img/${encodeURIComponent(doc.identifier)}`,
      sourceUrl: `${BASE_URL}/details/${encodeURIComponent(doc.identifier)}`,
      playable: true,
    };
  }

  private async getJson<T>(url: URL): Promise<T> {
    try {
      const response = await this.fetcher(url);
      if (!response.ok) {
        throw new ApiError(
          "PROVIDER_UNAVAILABLE",
          "视频源返回了错误响应。",
          502,
          {
            status: response.status,
          },
        );
      }
      return (await response.json()) as T;
    } catch (error) {
      if (error instanceof ApiError) {
        throw error;
      }
      throw new ApiError(
        "PROVIDER_UNAVAILABLE",
        "无法连接视频源。",
        502,
        error,
      );
    }
  }
}

function normalizeArray(value?: string | string[]): string[] {
  if (!value) {
    return [];
  }
  return Array.isArray(value) ? value : [value];
}

function stripHtml(value: string): string {
  return value
    .replace(/<[^>]*>/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function parseNumber(value?: string): number | undefined {
  if (!value) {
    return undefined;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function qualityLabel(height?: number, format?: string): string {
  if (height) {
    return `${height}p`;
  }
  return format?.toLowerCase().includes("512kb") ? "低清" : "自动";
}
