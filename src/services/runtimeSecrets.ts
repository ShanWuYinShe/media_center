import { randomBytes } from "node:crypto";
import {
  mkdirSync,
  readFileSync,
  writeFileSync,
} from "node:fs";
import { join } from "node:path";

const ASSET_SIGNING_SECRET_NAME = "media-asset-signing-key";

export const ASSET_SIGNING_KEY =
  configuredSecret("MEDIA_ASSET_SIGNING_KEY") ??
  persistentSecret(ASSET_SIGNING_SECRET_NAME);

export const VIDEO_ID_SECRET =
  configuredSecret("MEDIA_PUBLIC_ID_KEY") ?? ASSET_SIGNING_KEY;

function configuredSecret(name: string): string | undefined {
  const value = process.env[name]?.trim();
  return value || undefined;
}

function persistentSecret(name: string): string {
  const root = secretRoot();
  if (!root) {
    return generateSecret();
  }
  const dir = join(root, ".secrets");
  const path = join(dir, `${name}.txt`);
  const existing = readSecretFile(path);
  if (existing) {
    return existing;
  }
  mkdirSync(dir, { recursive: true, mode: 0o700 });
  const secret = generateSecret();
  try {
    writeFileSync(path, `${secret}\n`, { mode: 0o600, flag: "wx" });
    return secret;
  } catch (error) {
    if (!isErrnoException(error) || error.code !== "EEXIST") {
      throw error;
    }
    const createdByOtherProcess = readSecretFile(path);
    if (!createdByOtherProcess) {
      throw error;
    }
    return createdByOtherProcess;
  }
}

function secretRoot(): string | undefined {
  return (
    process.env.MEDIA_SECRET_DIR?.trim() ||
    process.env.BACKEND_CACHE_DIR?.trim() ||
    undefined
  );
}

function readSecretFile(path: string): string | undefined {
  try {
    const value = readFileSync(path, "utf8").trim();
    return value || undefined;
  } catch (error) {
    if (isErrnoException(error) && error.code === "ENOENT") {
      return undefined;
    }
    throw error;
  }
}

function generateSecret(): string {
  return randomBytes(32).toString("hex");
}

function isErrnoException(error: unknown): error is NodeJS.ErrnoException {
  return error instanceof Error && "code" in error;
}
