import { createHash } from "node:crypto";
import { createReadStream, createWriteStream } from "node:fs";
import {
  mkdir,
  readFile,
  readdir,
  rename,
  stat,
  unlink,
  utimes,
  writeFile,
} from "node:fs/promises";
import path from "node:path";
import { PassThrough, Readable } from "node:stream";

const DEFAULT_CACHE_DIR = process.env.BACKEND_CACHE_DIR ?? "/cache";
const DEFAULT_MAX_BYTES = Number(
  process.env.BACKEND_CACHE_MAX_BYTES ?? 50 * 1024 * 1024 * 1024,
);
const DEFAULT_MAX_FULL_RESPONSE_BYTES = Number(
  process.env.BACKEND_CACHE_MAX_FULL_RESPONSE_BYTES ?? 20 * 1024 * 1024,
);

export type CachedResponse = {
  status: number;
  headers: Record<string, string>;
  stream: NodeJS.ReadableStream;
};

type CachedMeta = {
  status: number;
  headers: Record<string, string>;
  cachedAt: number;
};

type CacheEntryStat = {
  name: string;
  size: number;
  atimeMs: number;
};

export class HttpResponseCache {
  private readonly cacheDir: string;
  private readonly maxBytes: number;
  private readonly maxFullResponseBytes: number;
  private readonly inflight = new Set<string>();
  private ready: Promise<void>;

  constructor(
    opts: {
      cacheDir?: string;
      maxBytes?: number;
      maxFullResponseBytes?: number;
    } = {},
  ) {
    this.cacheDir = opts.cacheDir ?? DEFAULT_CACHE_DIR;
    this.maxBytes = opts.maxBytes ?? DEFAULT_MAX_BYTES;
    this.maxFullResponseBytes =
      opts.maxFullResponseBytes ?? DEFAULT_MAX_FULL_RESPONSE_BYTES;
    this.ready = mkdir(this.cacheDir, { recursive: true }).then(
      () => undefined,
    );
  }

  keyFor(url: string, range?: string): string {
    return createHash("sha256")
      .update(`${url}\n${range ?? ""}`)
      .digest("hex");
  }

  async get(key: string): Promise<CachedResponse | undefined> {
    await this.ready;
    const bodyPath = this.bodyPath(key);
    const metaPath = this.metaPath(key);
    const [bodyStat, meta] = await Promise.all([
      tryStat(bodyPath),
      readMeta(metaPath),
    ]);
    if (!bodyStat || !meta) {
      return undefined;
    }
    const now = new Date();
    utimes(bodyPath, now, bodyStat.mtime).catch(() => undefined);
    return {
      status: meta.status,
      headers: meta.headers,
      stream: createReadStream(bodyPath),
    };
  }

  shouldCache(
    status: number,
    range: string | undefined,
    contentLength?: number,
  ): boolean {
    if (status !== 200 && status !== 206) {
      return false;
    }
    if (range) {
      return true;
    }
    return (
      contentLength !== undefined && contentLength <= this.maxFullResponseBytes
    );
  }

  streamAndCache(
    key: string,
    source: Readable,
    meta: CachedMeta,
  ): NodeJS.ReadableStream {
    const passthrough = new PassThrough();
    if (this.inflight.has(key)) {
      source.on("error", (error) => {
        passthrough.destroy(error);
      });
      passthrough.on("close", () => {
        if (!source.destroyed) {
          source.destroy();
        }
      });
      source.pipe(passthrough);
      return passthrough;
    }
    this.inflight.add(key);
    this.ready
      .then(() => this.writeThrough(key, source, passthrough, meta))
      .catch((error) => {
        passthrough.destroy(error);
        this.inflight.delete(key);
      });
    return passthrough;
  }

  private async writeThrough(
    key: string,
    source: Readable,
    passthrough: PassThrough,
    meta: CachedMeta,
  ): Promise<void> {
    const tmpBody = this.bodyPath(`${key}.tmp`);
    const writer = createWriteStream(tmpBody);
    let completed = false;
    let sourceEnded = false;
    let cleanedUp = false;
    const cleanupAbortedWrite = () => {
      if (cleanedUp) {
        return;
      }
      cleanedUp = true;
      writer.destroy();
      this.inflight.delete(key);
      unlink(tmpBody).catch(() => undefined);
    };
    source.on("data", (chunk: Buffer) => {
      writer.write(chunk);
      passthrough.write(chunk);
    });
    source.on("end", () => {
      sourceEnded = true;
      writer.end();
      passthrough.end();
    });
    source.on("error", (error) => {
      writer.destroy();
      passthrough.destroy(error);
      this.inflight.delete(key);
      unlink(tmpBody).catch(() => undefined);
    });
    writer.on("finish", () => {
      completed = true;
      void this.promoteTmpEntry(key, tmpBody, meta);
    });
    writer.on("error", (error) => {
      passthrough.destroy(error);
      this.inflight.delete(key);
      unlink(tmpBody).catch(() => undefined);
    });
    passthrough.on("close", () => {
      if (!completed && !sourceEnded) {
        if (!source.destroyed) {
          source.destroy();
        }
        cleanupAbortedWrite();
      }
    });
  }

  private async promoteTmpEntry(
    key: string,
    tmpBody: string,
    meta: CachedMeta,
  ): Promise<void> {
    try {
      await rename(tmpBody, this.bodyPath(key));
      await writeFile(this.metaPath(key), JSON.stringify(meta), "utf8");
      await this.evictIfNeeded();
    } catch {
      await unlink(tmpBody).catch(() => undefined);
    } finally {
      this.inflight.delete(key);
    }
  }

  private async evictIfNeeded(): Promise<void> {
    const entries = await this.listEntries();
    let total = entries.reduce((sum, entry) => sum + entry.size, 0);
    if (total <= this.maxBytes) {
      return;
    }
    entries.sort((a, b) => a.atimeMs - b.atimeMs);
    for (const entry of entries) {
      if (total <= this.maxBytes) {
        break;
      }
      await unlink(path.join(this.cacheDir, entry.name)).catch(() => undefined);
      if (!entry.name.endsWith(".meta.json")) {
        await unlink(this.metaPath(entry.name)).catch(() => undefined);
      }
      total -= entry.size;
    }
  }

  private async listEntries(): Promise<CacheEntryStat[]> {
    const names = await readdir(this.cacheDir).catch((): string[] => []);
    const out: CacheEntryStat[] = [];
    for (const name of names) {
      const full = path.join(this.cacheDir, name);
      const fileStat = await tryStat(full);
      if (fileStat?.isFile()) {
        out.push({ name, size: fileStat.size, atimeMs: fileStat.atimeMs });
      }
    }
    return out;
  }

  private bodyPath(key: string): string {
    return path.join(this.cacheDir, key);
  }

  private metaPath(key: string): string {
    return path.join(this.cacheDir, `${key}.meta.json`);
  }
}

async function tryStat(filePath: string) {
  try {
    return await stat(filePath);
  } catch {
    return undefined;
  }
}

async function readMeta(filePath: string): Promise<CachedMeta | undefined> {
  try {
    const raw = await readFile(filePath, "utf8");
    return JSON.parse(raw) as CachedMeta;
  } catch {
    return undefined;
  }
}
