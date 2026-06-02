import type { SearchResult, VideoDetails, VideoStream } from "../types.js";

export interface VideoProvider {
  readonly name: string;
  search(query: string, page: number, pageSize: number): Promise<SearchResult>;
  getDetails(id: string): Promise<VideoDetails>;
  getStreams(id: string): Promise<VideoStream[]>;
}
