import { createHash } from "node:crypto";
import { ApiError } from "../errors.js";
import type {
  ProviderName,
  SearchResult,
  VideoDetails,
  VideoStream,
  VideoSummary,
} from "../types.js";
import type { VideoProvider } from "./VideoProvider.js";
import { fetchWithTimeout } from "../services/fetchWithTimeout.js";

type FetchLike = typeof fetch;

// ── B站 API 响应类型 ──────────────────────────────────────────────

type BilibiliSearchItem = {
  bvid?: string;
  aid?: number;
  title?: string; // HTML 转义,含 <em class="keyword"> 高亮标签
  description?: string;
  author?: string;
  pic?: string; // 缩略图,以 // 开头
  duration?: string; // "HH:MM:SS" 或 "MM:SS"
  tag?: string;
  pubdate?: number; // Unix 秒
};

type BilibiliSearchResponse = {
  code?: number;
  message?: string;
  data?: {
    result?: BilibiliSearchItem[];
    numResults?: number;
  };
};

type BilibiliVideoResponse = {
  code?: number;
  message?: string;
  data?: {
    bvid?: string;
    aid?: number;
    title?: string;
    desc?: string;
    pic?: string;
    owner?: { name?: string; face?: string };
    duration?: number; // 秒
    cid?: number;
    pubdate?: number;
  };
};

type BilibiliPlayUrlResponse = {
  code?: number;
  message?: string;
  data?: {
    quality?: number;
    accept_quality?: number[];
    accept_description?: string[];
    durl?: Array<{
      url: string;
      backup_url?: string[];
      order: number;
      length: number;
      size: number;
      quality: number;
    }>;
  };
};

// ── 常量 ──────────────────────────────────────────────────────────

const PROVIDER: ProviderName = "bilibili";
const API_BASE = "https://api.bilibili.com";

/**
 * 解说/盘点/预告类关键词 — 匹配到任一即过滤。
 * 与 category 级 excludes 不同,这是 BilibiliProvider 级别的硬过滤,
 * 对所有分类生效(Dota 专区除外,因为 Dota 短视频居多)。
 */
const COMMENTARY_KEYWORDS = [
  "解说",
  "盘点",
  "速看",
  "影评",
  "混剪",
  "分钟看",
  "预告",
  "花絮",
  "片段",
  "几分钟看",
];

// ── wbi 签名(B站搜索 API 反爬校验) ──────────────────────────────

/**
 * B站 wbi 签名用固定混淆表。
 * 对 img_key + sub_key 做字符重排列,取前 32 字符作为签名密钥。
 * @see https://github.com/SocialSisterYi/bilibili-API-collect/blob/master/docs/misc/sign/wbi.md
 */
const MIXIN_KEY_ENC_TAB = [
  46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
  33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
  26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
  20, 34, 44, 52,
];

function getMixinKey(orig: string): string {
  return MIXIN_KEY_ENC_TAB.map((i) => orig[i])
    .join("")
    .substring(0, 32);
}

/** 从 wbi 图片 URL 提取 key (文件名去掉扩展名) */
function extractKey(url: string): string {
  return (
    url
      .split("/")
      .pop()
      ?.replace(/\.\w+$/, "") ?? ""
  );
}

/** 对请求参数做 wbi 签名,返回添加了 w_rid 和 wts 的新参数 Map */
function signWbiParams(
  params: Record<string, string>,
  imgKey: string,
  subKey: string,
): Record<string, string> {
  const mixinKey = getMixinKey(imgKey + subKey);
  const wts = Math.floor(Date.now() / 1000).toString();
  const all: Record<string, string> = { ...params, wts };
  // 按 key 字典序排列后拼成 query string
  const query = Object.keys(all)
    .sort()
    .map((k) => {
      // 过滤掉 !'()* 这些字符
      const v = all[k].replace(/[!'()*]/g, "");
      return `${encodeURIComponent(k)}=${encodeURIComponent(v)}`;
    })
    .join("&");
  const wRid = createHash("md5")
    .update(query + mixinKey)
    .digest("hex");
  return { ...all, w_rid: wRid };
}

/**
 * 将查询字符串拆分为 include / exclude 两部分。
 * "电影 完整版 NOT 解说 NOT 盘点" → { include: "电影 完整版", excludeTerms: ["解说","盘点"] }
 */
function parseQuery(query: string): {
  include: string;
  excludeTerms: string[];
} {
  const tokens = query
    .split(/\s+/)
    .map((t) => t.trim())
    .filter(Boolean);
  const includes: string[] = [];
  const excludes: string[] = [];
  let negate = false;
  for (const token of tokens) {
    if (token === "NOT" || token === "-") {
      negate = true;
      continue;
    }
    if (negate) {
      excludes.push(token);
      negate = false;
    } else {
      includes.push(token);
    }
  }
  return { include: includes.join(" "), excludeTerms: excludes };
}

/** 去掉 B站搜索结果中的 HTML 高亮标签 */
function cleanHtmlTitle(raw: string): string {
  return raw
    .replace(/<[^>]*>/g, "")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .trim();
}

/** B站缩略图以 // 开头,补全协议 */
function normalizePicUrl(url?: string): string | undefined {
  if (!url) return undefined;
  return url.startsWith("//") ? `https:${url}` : url;
}

function isDotaQuery(query: string): boolean {
  const normalized = query.toLocaleLowerCase();
  return (
    normalized.includes("dota") ||
    normalized.includes("魔兽争霸") ||
    normalized.includes("allstars")
  );
}

/** 把 B站清晰度编码映射为简短标签(无 accept_description 时兜底)。 */
function qualityLabel(quality: number): string {
  if (quality >= 120) return "4K";
  if (quality >= 116) return "1080P60";
  if (quality >= 112) return "1080P+";
  if (quality >= 80) return "1080P";
  if (quality >= 64) return "720P";
  if (quality >= 32) return "480P";
  return "360P";
}

/** 把 B站 accept_description("高清 720P")压缩为简短分辨率标签("720P")。 */
function compactQualityDesc(desc: string, quality: number): string {
  const match = desc.match(/(\d+\s*[PpKk][^\s]*|4\s*[Kk])/);
  if (match) {
    return match[0].replace(/\s+/g, "").toUpperCase();
  }
  return qualityLabel(quality);
}

/** 解析 "HH:MM:SS" 或 "MM:SS" 为秒数 */
function parseDuration(d?: string): number {
  if (!d) return 0;
  const parts = d.split(":").map(Number);
  if (parts.some(isNaN)) return 0;
  if (parts.length === 3) return parts[0] * 3600 + parts[1] * 60 + parts[2];
  if (parts.length === 2) return parts[0] * 60 + parts[1];
  return 0;
}

// ── Provider ──────────────────────────────────────────────────────

export class BilibiliProvider implements VideoProvider {
  readonly name = PROVIDER;

  // wbi 签名密钥缓存
  private wbiImgKey: string | null = null;
  private wbiSubKey: string | null = null;
  private wbiKeyExpiresAt = 0; // Unix ms
  private static readonly WBI_KEY_TTL = 10 * 60 * 1000; // 10 分钟

  constructor(private readonly fetcher: FetchLike = fetch) {}

  /**
   * 获取 wbi 签名密钥,带缓存(10 分钟 TTL)。
   * 从 /x/web-interface/nav 的 wbi_img 字段提取 img_key 和 sub_key。
   */
  private async ensureWbiKeys(): Promise<{ imgKey: string; subKey: string }> {
    const now = Date.now();
    if (this.wbiImgKey && this.wbiSubKey && now < this.wbiKeyExpiresAt) {
      return { imgKey: this.wbiImgKey, subKey: this.wbiSubKey };
    }
    const navUrl = new URL(`${API_BASE}/x/web-interface/nav`);
    const navData = await this.getJson<{
      data?: { wbi_img?: { img_url?: string; sub_url?: string } };
    }>(navUrl);
    const img = navData.data?.wbi_img?.img_url;
    const sub = navData.data?.wbi_img?.sub_url;
    if (!img || !sub) {
      throw new ApiError("PROVIDER_UNAVAILABLE", "无法获取 B站签名密钥。", 502);
    }
    this.wbiImgKey = extractKey(img);
    this.wbiSubKey = extractKey(sub);
    this.wbiKeyExpiresAt = now + BilibiliProvider.WBI_KEY_TTL;
    return { imgKey: this.wbiImgKey, subKey: this.wbiSubKey };
  }

  async search(
    query: string,
    page: number,
    pageSize: number,
  ): Promise<SearchResult> {
    const { include, excludeTerms } = parseQuery(query);
    const dotaQuery = isDotaQuery(include || query);

    const params: Record<string, string> = {
      search_type: "video",
      keyword: include || query,
      page: String(page),
      page_size: String(Math.min(pageSize, 50)),
      order: "totalrank",
    };

    // wbi 签名
    const { imgKey, subKey } = await this.ensureWbiKeys();
    const signed = signWbiParams(params, imgKey, subKey);

    // 手动构建 query string(与签名计算使用相同编码),避免 URL.searchParams 的
    // application/x-www-form-urlencoded 编码与 encodeURIComponent 不一致导致签名校验失败
    const queryString = Object.keys(signed)
      .sort()
      .map((k) => {
        const v = signed[k].replace(/[!'()*]/g, "");
        return `${encodeURIComponent(k)}=${encodeURIComponent(v)}`;
      })
      .join("&");
    const url = new URL(
      `${API_BASE}/x/web-interface/wbi/search/type?${queryString}`,
    );

    const data = await this.getJson<BilibiliSearchResponse>(url);
    if (data.code !== 0) {
      return { query, page, pageSize, total: 0, items: [] };
    }

    const rawItems = data.data?.result ?? [];
    const items = rawItems
      .filter((item) => item.bvid)
      .filter(
        (item) =>
          dotaQuery ||
          !this.isCommentary(cleanHtmlTitle(item.title ?? ""), excludeTerms),
      )
      .map((item) => this.searchItemToSummary(item));

    return {
      query,
      page,
      pageSize,
      total: data.data?.numResults ?? items.length,
      items,
    };
  }

  async getDetails(id: string): Promise<VideoDetails> {
    const url = new URL(`${API_BASE}/x/web-interface/view`);
    url.searchParams.set("bvid", id);

    const data = await this.getJson<BilibiliVideoResponse>(url);
    if (data.code !== 0 || !data.data?.bvid) {
      throw new ApiError("NOT_FOUND", "未找到视频详情。", 404);
    }
    const v = data.data;

    // 复用已获取的 cid，避免 getStreams 重复请求 view API
    let streams: VideoStream[] = [];
    if (v.cid) {
      streams = await this.fetchStreams(id, v.cid);
    }

    return {
      provider: PROVIDER,
      id: v.bvid ?? "",
      videoKind: "vod",
      title: v.title?.trim() || (v.bvid ?? ""),
      description: v.desc ?? "",
      year: v.pubdate
        ? new Date(v.pubdate * 1000).getFullYear().toString()
        : undefined,
      creators: v.owner?.name ? [v.owner.name] : [],
      thumbnailUrl: normalizePicUrl(v.pic),
      sourceUrl: `https://www.bilibili.com/video/${v.bvid}`,
      playable: streams.length > 0,
      streams,
    };
  }

  async getStreams(id: string): Promise<VideoStream[]> {
    // 获取 cid
    const infoUrl = new URL(`${API_BASE}/x/web-interface/view`);
    infoUrl.searchParams.set("bvid", id);
    const info = await this.getJson<BilibiliVideoResponse>(infoUrl);
    if (info.code !== 0 || !info.data?.cid) {
      throw new ApiError("NO_PLAYABLE_STREAM", "无法获取视频流信息。", 422);
    }
    return this.fetchStreams(id, info.data.cid);
  }

  /**
   * 根据 bvid + cid 请求点播播放地址(fnval=0 → 旧版 MP4 直链)。
   * 先探测可用清晰度(accept_quality),再为每个清晰度各取一条直链,
   * 这样详情页可以让用户选择清晰度。未登录时通常有 720P / 360P 两档。
   */
  private async fetchStreams(
    bvid: string,
    cid: number,
  ): Promise<VideoStream[]> {
    // 第一次请求高清晰度,顺带读取 accept_quality 列表
    const probe = await this.fetchPlayUrl(bvid, cid, 80);
    if ((probe.data?.durl ?? []).length === 0) {
      return [];
    }
    const acceptQuality = probe.data?.accept_quality ?? [];
    const acceptDesc = probe.data?.accept_description ?? [];
    const descByQuality = new Map<number, string>();
    acceptQuality.forEach((q, index) => {
      const desc = acceptDesc[index];
      if (desc) descByQuality.set(q, desc);
    });

    // 候选清晰度:accept_quality 已按从高到低排列,最多取 4 档
    const candidateQualities = (
      acceptQuality.length > 0 ? acceptQuality : [probe.data?.quality ?? 32]
    ).slice(0, 4);

    const streams: VideoStream[] = [];
    const seenLabels = new Set<string>();
    for (const qn of candidateQualities) {
      const data =
        qn === (probe.data?.quality ?? -1)
          ? probe
          : await this.fetchPlayUrl(bvid, cid, qn);
      const durl = data.data?.durl?.[0];
      if (!durl) continue;
      const actualQuality = data.data?.quality ?? qn;
      const desc = descByQuality.get(actualQuality);
      const label = desc
        ? compactQualityDesc(desc, actualQuality)
        : qualityLabel(actualQuality);
      // 未登录时不同 qn 可能回退到同一档,按标签去重
      if (seenLabels.has(label)) continue;
      seenLabels.add(label);
      streams.push({
        id: `bili-${bvid}-${actualQuality}`,
        url: durl.url,
        originalUrl: durl.url,
        mimeType: "video/mp4" as const,
        quality: label,
        sizeBytes: durl.size,
        supportsRange: true,
      });
    }
    return streams;
  }

  /** 请求单一清晰度的 playurl(fnval=0 → 旧版 MP4 直链)。 */
  private async fetchPlayUrl(
    bvid: string,
    cid: number,
    qn: number,
  ): Promise<BilibiliPlayUrlResponse> {
    const playUrl = new URL(`${API_BASE}/x/player/playurl`);
    playUrl.searchParams.set("bvid", bvid);
    playUrl.searchParams.set("cid", String(cid));
    playUrl.searchParams.set("fnval", "0");
    playUrl.searchParams.set("qn", String(qn));
    playUrl.searchParams.set("fourk", "1");
    return this.getJson<BilibiliPlayUrlResponse>(playUrl);
  }

  // ── 内部方法 ──────────────────────────────────────────────────

  /**
   * 判断标题是否为解说类内容。
   * title: 已清理的纯文本标题
   * extraExcludes: 分类级排除词(来自 contentCategories.json excludes)
   */
  private isCommentary(title: string, extraExcludes: string[] = []): boolean {
    const allPatterns = [...COMMENTARY_KEYWORDS, ...extraExcludes];
    return allPatterns.some((p) => title.includes(p));
  }

  private searchItemToSummary(item: BilibiliSearchItem): VideoSummary {
    const title = cleanHtmlTitle(item.title ?? "");
    const durationSec = parseDuration(item.duration);
    return {
      provider: PROVIDER,
      id: item.bvid ?? "",
      videoKind: "vod",
      title: title || (item.bvid ?? ""),
      description: item.description ?? "",
      year: item.pubdate
        ? new Date(item.pubdate * 1000).getFullYear().toString()
        : undefined,
      creators: item.author ? [item.author] : [],
      thumbnailUrl: normalizePicUrl(item.pic),
      durationSeconds: durationSec > 0 ? durationSec : undefined,
      sourceUrl: item.bvid
        ? `https://www.bilibili.com/video/${item.bvid}`
        : undefined,
      playable: true,
    };
  }

  private async getJson<T>(url: URL): Promise<T> {
    try {
      const response = await fetchWithTimeout(this.fetcher, url.toString(), {
        headers: {
          "User-Agent":
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
          Referer: "https://www.bilibili.com",
        },
      });
      if (!response.ok) {
        throw new ApiError(
          "PROVIDER_UNAVAILABLE",
          "B站 API 返回了错误响应。",
          502,
          { status: response.status },
        );
      }
      return (await response.json()) as T;
    } catch (error) {
      if (error instanceof ApiError) throw error;
      throw new ApiError(
        "PROVIDER_UNAVAILABLE",
        "无法连接 B站 API。",
        502,
        error,
      );
    }
  }
}
