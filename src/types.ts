export type ProviderName = "internet-archive" | "youtube" | "bilibili";
export type VideoKind = "vod" | "live";

export type MediaSourceConfig = {
  id: ProviderName;
  enabled: boolean;
};

export type VideoSummary = {
  provider: ProviderName;
  id: string;
  videoKind: VideoKind;
  title: string;
  description: string;
  year?: string;
  creators: string[];
  thumbnailUrl?: string;
  durationSeconds?: number;
  sourceUrl?: string;
  playable: boolean;
};

export type VideoDetails = VideoSummary & {
  licenseUrl?: string;
  sourceUrl: string;
  streams: VideoStream[];
};

export type StreamMimeType =
  | "video/mp4"
  | "video/webm"
  | "application/dash+xml";

export type VideoStream = {
  id: string;
  url: string;
  originalUrl: string;
  mimeType: StreamMimeType;
  quality: string;
  width?: number;
  height?: number;
  sizeBytes?: number;
  supportsRange: boolean;
};

export type PlaybackOption = {
  id: string;
  kind: "vod";
  label: string;
  url: string;
  mimeType: StreamMimeType;
};

export type SearchResult = {
  query: string;
  category?: ContentCategory;
  page: number;
  pageSize: number;
  total: number;
  items: VideoSummary[];
};

export type PlaybackKind = PlaybackOption["kind"];

export type PublicVideoSummary = Omit<
  VideoSummary,
  "provider" | "sourceUrl" | "playable"
> & {
  playbackKind: PlaybackKind;
};

export type PublicVideoDetails = PublicVideoSummary & {
  playbackOptions: PlaybackOption[];
};

export type PublicSearchResult = Omit<SearchResult, "items" | "category"> & {
  category?: PublicContentCategory;
  items: PublicVideoSummary[];
};

export type ContentCategory = {
  id: string;
  title: string;
  description: string;
  query: string;
  sourceQueries: Partial<Record<ProviderName, string>>;
  keywords: string[];
  excludes: string[];
};

export type PublicContentCategory = Omit<
  ContentCategory,
  "query" | "sourceQueries" | "keywords" | "excludes"
>;
