import { z } from "zod";

export const DASH_MANIFEST_MIME_TYPE = "application/dash+xml";
const DASH_MANIFEST_REFERENCE_PREFIX = "workwork-dash:";

const rangeSchema = z.string().regex(/^\d+-\d+$/);

const representationSchema = z.object({
  id: z.string().min(1).max(120),
  url: z.string().url(),
  mimeType: z.enum(["video/mp4", "audio/mp4"]),
  codecs: z.string().min(1).max(200).optional(),
  bandwidth: z.number().int().positive().optional(),
  width: z.number().int().positive().optional(),
  height: z.number().int().positive().optional(),
  frameRate: z.string().min(1).max(40).optional(),
  initialization: rangeSchema.optional(),
  indexRange: rangeSchema.optional(),
});

const dashManifestSchema = z.object({
  version: z.literal(1),
  durationSeconds: z.number().positive().optional(),
  minBufferTimeSeconds: z.number().positive().optional(),
  video: representationSchema.extend({ mimeType: z.literal("video/mp4") }),
  audio: representationSchema
    .extend({ mimeType: z.literal("audio/mp4") })
    .optional(),
});

export type DashRepresentation = z.infer<typeof representationSchema>;
export type DashVideoRepresentation = DashRepresentation & {
  mimeType: "video/mp4";
};
export type DashAudioRepresentation = DashRepresentation & {
  mimeType: "audio/mp4";
};
export type DashManifest = z.infer<typeof dashManifestSchema>;

export function createDashManifestReference(manifest: DashManifest): string {
  const payload = Buffer.from(
    JSON.stringify(dashManifestSchema.parse(manifest)),
  ).toString("base64url");
  return `${DASH_MANIFEST_REFERENCE_PREFIX}${payload}`;
}

export function isDashManifestReference(value: string): boolean {
  return value.startsWith(DASH_MANIFEST_REFERENCE_PREFIX);
}

export function dashManifestPayloadFromReference(reference: string): string {
  return reference.slice(DASH_MANIFEST_REFERENCE_PREFIX.length);
}

export function parseDashManifestPayload(payload: string): DashManifest {
  if (!/^[A-Za-z0-9_-]+$/.test(payload)) {
    throw new Error("dash manifest payload is not base64url");
  }
  const decoded = Buffer.from(payload, "base64url").toString("utf8");
  return dashManifestSchema.parse(JSON.parse(decoded) as unknown);
}

export function renderDashMpd(
  manifest: DashManifest,
  mediaUrlFor: (rawUrl: string) => string,
): string {
  const duration = manifest.durationSeconds
    ? ` mediaPresentationDuration="${xmlAttr(durationValue(manifest.durationSeconds))}"`
    : "";
  const minBufferTime = durationValue(manifest.minBufferTimeSeconds ?? 1.5);
  const audio = manifest.audio
    ? `\n${renderAdaptationSet("audio", manifest.audio, mediaUrlFor)}`
    : "";
  return `<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011"${duration} minBufferTime="${xmlAttr(minBufferTime)}">
  <Period${duration ? ` duration="${xmlAttr(durationValue(manifest.durationSeconds ?? 0))}"` : ""}>
${renderAdaptationSet("video", manifest.video, mediaUrlFor)}${audio}
  </Period>
</MPD>
`;
}

function renderAdaptationSet(
  contentType: "video" | "audio",
  representation: DashRepresentation,
  mediaUrlFor: (rawUrl: string) => string,
): string {
  const attrs = [
    `id="${contentType}"`,
    `contentType="${contentType}"`,
    `mimeType="${xmlAttr(representation.mimeType)}"`,
    `segmentAlignment="true"`,
    `startWithSAP="1"`,
  ];
  return `    <AdaptationSet ${attrs.join(" ")}>
${renderRepresentation(representation, mediaUrlFor)}
    </AdaptationSet>`;
}

function renderRepresentation(
  representation: DashRepresentation,
  mediaUrlFor: (rawUrl: string) => string,
): string {
  const attrs = [
    `id="${xmlAttr(representation.id)}"`,
    representation.bandwidth
      ? `bandwidth="${representation.bandwidth}"`
      : undefined,
    representation.codecs
      ? `codecs="${xmlAttr(representation.codecs)}"`
      : undefined,
    representation.width ? `width="${representation.width}"` : undefined,
    representation.height ? `height="${representation.height}"` : undefined,
    representation.frameRate
      ? `frameRate="${xmlAttr(representation.frameRate)}"`
      : undefined,
  ].filter((attr): attr is string => Boolean(attr));
  const segmentBase = renderSegmentBase(representation);
  return `      <Representation ${attrs.join(" ")}>
        <BaseURL>${xmlText(mediaUrlFor(representation.url))}</BaseURL>${segmentBase}
      </Representation>`;
}

function renderSegmentBase(representation: DashRepresentation): string {
  if (!representation.initialization && !representation.indexRange) {
    return "";
  }
  const indexRange = representation.indexRange
    ? ` indexRange="${xmlAttr(representation.indexRange)}"`
    : "";
  const initialization = representation.initialization
    ? `\n          <Initialization range="${xmlAttr(representation.initialization)}"/>`
    : "";
  return `
        <SegmentBase${indexRange}>${initialization}
        </SegmentBase>`;
}

function durationValue(seconds: number): string {
  return `PT${trimNumber(seconds)}S`;
}

function trimNumber(value: number): string {
  return Number.isInteger(value)
    ? String(value)
    : value.toFixed(3).replace(/0+$/, "");
}

function xmlAttr(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/"/g, "&quot;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

function xmlText(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}
