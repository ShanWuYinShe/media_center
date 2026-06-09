import { ApiError } from "../errors.js";
import type {
  ProviderName,
  SearchResult,
  StreamMimeType,
  VideoDetails,
  VideoStream,
  VideoSummary,
} from "../types.js";
import { fetchWithTimeout } from "../services/fetchWithTimeout.js";
import type { VideoProvider } from "./VideoProvider.js";

type FetchLike = typeof fetch;

type ArchiveSearchResponse = {
  response?: {
    numFound?: number;
    docs?: ArchiveSearchDoc[];
  };
};

type ArchiveSearchDoc = {
  identifier?: string;
  title?: string | string[];
  description?: string | string[];
  creator?: string | string[];
  year?: string | string[];
  date?: string | string[];
  runtime?: string | string[];
  licenseurl?: string | string[];
};

type ArchiveMetadataResponse = {
  metadata?: {
    identifier?: string;
    title?: string | string[];
    description?: string | string[];
    creator?: string | string[];
    year?: string | string[];
    date?: string | string[];
    runtime?: string | string[];
    licenseurl?: string | string[];
  };
  files?: ArchiveFile[];
};

type ArchiveFile = {
  name?: string;
  format?: string;
  size?: string;
  length?: string;
  width?: string;
  height?: string;
};

const PROVIDER: ProviderName = "internet-archive";
const API_BASE = "https://archive.org";
const MAX_ROWS = 50;
const MAX_QUERY_TERMS = 6;

export class InternetArchiveProvider implements VideoProvider {
  readonly name = PROVIDER;

  constructor(private readonly fetcher: FetchLike = fetch) {}

  async search(
    query: string,
    page: number,
    pageSize: number,
  ): Promise<SearchResult> {
    const rows = Math.min(Math.max(pageSize, 1), MAX_ROWS);
    const url = new URL(`${API_BASE}/advancedsearch.php`);
    url.searchParams.set("q", archiveSearchQuery(query));
    for (const field of [
      "identifier",
      "title",
      "description",
      "creator",
      "year",
      "date",
      "runtime",
      "licenseurl",
    ]) {
      url.searchParams.append("fl[]", field);
    }
    url.searchParams.set("rows", String(rows));
    url.searchParams.set("page", String(page));
    url.searchParams.append("sort[]", "downloads desc");
    url.searchParams.append("sort[]", "avg_rating desc");
    url.searchParams.set("output", "json");

    const data = await this.fetchJson<ArchiveSearchResponse>(url);
    const docs = data.response?.docs ?? [];
    const items = docs
      .filter((doc) => nonEmptyString(doc.identifier))
      .map((doc) => this.searchDocToSummary(doc));

    return {
      query,
      page,
      pageSize,
      total: data.response?.numFound ?? items.length,
      items,
    };
  }

  async getDetails(id: string): Promise<VideoDetails> {
    const data = await this.fetchMetadata(id);
    const metadata = data.metadata;
    if (!metadata) {
      throw new ApiError("NOT_FOUND", "未找到 Internet Archive 条目。", 404);
    }
    const identifier = metadata.identifier || id;
    const streams = playableStreams(identifier, data.files ?? []);
    const durationSeconds = parseDuration(firstString(metadata.runtime));

    return {
      provider: PROVIDER,
      id: identifier,
      videoKind: "vod",
      title: firstString(metadata.title) || identifier,
      description: cleanText(firstString(metadata.description)),
      year: archiveYear(metadata.year, metadata.date),
      creators: toStringArray(metadata.creator),
      thumbnailUrl: archiveThumbnailUrl(identifier),
      durationSeconds,
      licenseUrl: firstString(metadata.licenseurl),
      sourceUrl: archiveDetailsUrl(identifier),
      playable: streams.length > 0,
      streams,
    };
  }

  async getStreams(id: string): Promise<VideoStream[]> {
    const data = await this.fetchMetadata(id);
    const identifier = data.metadata?.identifier || id;
    const streams = playableStreams(identifier, data.files ?? []);
    if (streams.length === 0) {
      throw new ApiError(
        "NO_PLAYABLE_STREAM",
        "该 Internet Archive 条目暂无可直接播放的 MP4/WebM 点播文件。",
        422,
      );
    }
    return streams;
  }

  private searchDocToSummary(doc: ArchiveSearchDoc): VideoSummary {
    const identifier = doc.identifier ?? "";
    return {
      provider: PROVIDER,
      id: identifier,
      videoKind: "vod",
      title: firstString(doc.title) || identifier,
      description: cleanText(firstString(doc.description)),
      year: archiveYear(doc.year, doc.date),
      creators: toStringArray(doc.creator),
      thumbnailUrl: archiveThumbnailUrl(identifier),
      durationSeconds: parseDuration(firstString(doc.runtime)),
      sourceUrl: archiveDetailsUrl(identifier),
      playable: true,
    };
  }

  private async fetchMetadata(id: string): Promise<ArchiveMetadataResponse> {
    const url = new URL(`${API_BASE}/metadata/${encodeURIComponent(id)}`);
    return this.fetchJson<ArchiveMetadataResponse>(url);
  }

  private async fetchJson<T>(url: URL): Promise<T> {
    try {
      const response = await fetchWithTimeout(this.fetcher, url.toString(), {
        headers: {
          Accept: "application/json",
          "User-Agent": "workwork-media-server/1.0",
        },
      });
      if (!response.ok) {
        throw new ApiError(
          "UPSTREAM_UNAVAILABLE",
          "Internet Archive 返回了错误响应。",
          502,
          { status: response.status },
        );
      }
      return (await response.json()) as T;
    } catch (error) {
      if (error instanceof ApiError) throw error;
      throw new ApiError(
        "UPSTREAM_UNAVAILABLE",
        "无法连接 Internet Archive。",
        502,
        error,
      );
    }
  }
}

function archiveSearchQuery(query: string): string {
  const terms = queryTerms(query);
  const textClause =
    terms.length > 0
      ? ` AND (${terms
          .map(
            (term) =>
              `(title:${quotedTerm(term)} OR description:${quotedTerm(term)} OR subject:${quotedTerm(term)})`,
          )
          .join(" AND ")})`
      : "";
  return `mediatype:movies${textClause} AND (format:"MPEG4" OR format:"h.264" OR format:"512Kb MPEG4" OR format:"WebM")`;
}

function queryTerms(query: string): string[] {
  const seen = new Set<string>();
  const terms: string[] = [];
  for (const term of query
    .replace(/[^\p{Letter}\p{Number}\s-]+/gu, " ")
    .split(/\s+/)
    .map((part) => part.trim())
    .filter(Boolean)) {
    const normalized = term.toLocaleLowerCase();
    if (seen.has(normalized)) {
      continue;
    }
    seen.add(normalized);
    terms.push(term);
    if (terms.length >= MAX_QUERY_TERMS) {
      break;
    }
  }
  return terms;
}

function quotedTerm(term: string): string {
  return `"${term.replace(/["\\]/g, " ").trim()}"`;
}

function playableStreams(identifier: string, files: ArchiveFile[]): VideoStream[] {
  return files
    .flatMap((file) => {
      const mimeType = streamMimeType(file);
      const name = nonEmptyString(file.name);
      if (!mimeType || !name) {
        return [];
      }
      const height = positiveInteger(file.height);
      const width = positiveInteger(file.width);
      const url = archiveDownloadUrl(identifier, name);
      return [
        {
          id: `ia-${identifier}-${name}`,
          url,
          originalUrl: url,
          mimeType,
          quality: qualityLabel(file, height),
          width,
          height,
          sizeBytes: positiveInteger(file.size),
          supportsRange: true,
        },
      ];
    })
    .sort((left, right) => streamRank(right) - streamRank(left))
    .slice(0, 4);
}

function streamMimeType(file: ArchiveFile): StreamMimeType | undefined {
  const name = file.name?.toLocaleLowerCase() ?? "";
  const format = file.format?.toLocaleLowerCase() ?? "";
  if (name.endsWith(".webm") || format.includes("webm")) {
    return "video/webm";
  }
  if (
    name.endsWith(".mp4") ||
    format.includes("mpeg4") ||
    format.includes("h.264")
  ) {
    return "video/mp4";
  }
  return undefined;
}

function streamRank(stream: VideoStream): number {
  const heightScore = stream.height ?? 0;
  const mimeScore = stream.mimeType === "video/mp4" ? 1000 : 500;
  const sizeScore = stream.sizeBytes
    ? Math.min(stream.sizeBytes / 1_000_000, 500)
    : 0;
  return mimeScore + heightScore + sizeScore;
}

function qualityLabel(file: ArchiveFile, height: number | undefined): string {
  if (height) {
    return `${height}P`;
  }
  const format = nonEmptyString(file.format);
  if (format) {
    return format;
  }
  return streamMimeType(file) === "video/webm" ? "WebM" : "MP4";
}

function archiveDownloadUrl(identifier: string, fileName: string): string {
  const encodedFile = fileName.split("/").map(encodeURIComponent).join("/");
  return `${API_BASE}/download/${encodeURIComponent(identifier)}/${encodedFile}`;
}

function archiveThumbnailUrl(identifier: string): string {
  return `${API_BASE}/services/img/${encodeURIComponent(identifier)}`;
}

function archiveDetailsUrl(identifier: string): string {
  return `${API_BASE}/details/${encodeURIComponent(identifier)}`;
}

function archiveYear(
  year: string | string[] | undefined,
  date: string | string[] | undefined,
): string | undefined {
  return firstString(year)?.match(/\d{4}/)?.[0] ?? firstString(date)?.match(/\d{4}/)?.[0];
}

function toStringArray(value: string | string[] | undefined): string[] {
  if (Array.isArray(value)) {
    return value.map((item) => item.trim()).filter(Boolean);
  }
  const normalized = value?.trim();
  return normalized ? [normalized] : [];
}

function firstString(value: string | string[] | undefined): string | undefined {
  if (Array.isArray(value)) {
    return value.find((item) => item.trim())?.trim();
  }
  return value?.trim() || undefined;
}

function cleanText(value: string | undefined): string {
  return (value ?? "")
    .replace(/<[^>]*>/g, " ")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/\s+/g, " ")
    .trim();
}

function parseDuration(value: string | undefined): number | undefined {
  if (!value) {
    return undefined;
  }
  const hms = value.match(/^(\d+):(\d{1,2})(?::(\d{1,2}))?$/);
  if (hms) {
    const first = Number(hms[1]);
    const second = Number(hms[2]);
    const third = hms[3] === undefined ? undefined : Number(hms[3]);
    if ([first, second, third ?? 0].some((part) => !Number.isFinite(part))) {
      return undefined;
    }
    return third === undefined ? first * 60 + second : first * 3600 + second * 60 + third;
  }
  const seconds = Number(value);
  return Number.isFinite(seconds) && seconds > 0 ? seconds : undefined;
}

function positiveInteger(value: string | number | undefined): number | undefined {
  const parsed = typeof value === "number" ? value : Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : undefined;
}

function nonEmptyString(value: string | undefined): string | undefined {
  const normalized = value?.trim();
  return normalized ? normalized : undefined;
}
