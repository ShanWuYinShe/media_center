export type ProviderName = "internet-archive" | "youtube";

export type VideoSummary = {
  provider: ProviderName;
  id: string;
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

export type VideoStream = {
  id: string;
  url: string;
  originalUrl: string;
  mimeType: "video/mp4" | "video/webm";
  quality: string;
  width?: number;
  height?: number;
  sizeBytes?: number;
  supportsRange: boolean;
};

export type SearchResult = {
  query: string;
  category?: ContentCategory;
  page: number;
  pageSize: number;
  total: number;
  items: VideoSummary[];
};

export type PublicVideoSummary = Omit<
  VideoSummary,
  "provider" | "sourceUrl" | "playable"
>;

export type PlaybackOption =
  | {
      id: string;
      kind: "stream";
      label: string;
      url: string;
      mimeType: "video/mp4" | "video/webm";
    }
  | {
      id: string;
      kind: "external";
      label: string;
      url: string;
    };

export type PublicVideoDetails = PublicVideoSummary & {
  playbackOptions: PlaybackOption[];
};

export type PublicSearchResult = Omit<SearchResult, "items"> & {
  items: PublicVideoSummary[];
};

export type ContentCategory = {
  id: string;
  title: string;
  description: string;
  query: string;
};
