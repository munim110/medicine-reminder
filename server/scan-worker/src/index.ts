import { verifyFirebaseIdToken } from "./auth.ts";
import { parseScanResult } from "./prescription.ts";
import { type ImageType, type ProviderEnv, providerChain } from "./providers.ts";

export interface Env extends ProviderEnv {
  DB: D1Database;
  FIREBASE_PROJECT_ID: string;
  DAILY_SCANS_PER_USER: string;
  DAILY_SCANS_TOTAL: string;
}

const MAX_IMAGE_BYTES = 4 * 1024 * 1024;
const IMAGE_TYPES = ["image/jpeg", "image/png", "image/webp"];

/**
 * POST /scan — body is the prescription photo, `Authorization: Bearer <Firebase ID token>`.
 *
 * Images and results are health data: they are never logged or stored here.
 */
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (new URL(request.url).pathname !== "/scan") return error(404, "not_found");
    if (request.method !== "POST") return error(405, "method_not_allowed");

    let uid: string;
    try {
      uid = await verifyFirebaseIdToken(request.headers.get("Authorization"), env.FIREBASE_PROJECT_ID);
    } catch {
      return error(401, "unauthorized");
    }

    const type = (request.headers.get("Content-Type") ?? "").split(";")[0].trim();
    if (!IMAGE_TYPES.includes(type)) return error(415, "unsupported_image_type");
    // Refuse an oversized upload before buffering it: the Worker's memory is far smaller than
    // the largest body Cloudflare accepts.
    if (Number(request.headers.get("Content-Length") ?? 0) > MAX_IMAGE_BYTES) return error(413, "image_too_large");
    const image = await request.arrayBuffer();
    if (image.byteLength === 0 || image.byteLength > MAX_IMAGE_BYTES) return error(413, "image_too_large");

    let quota: Quota;
    try {
      quota = await takeQuota(env, uid);
    } catch (e) {
      console.error("quota unavailable", String((e as Error).message));
      return error(503, "service_busy");
    }
    if (!quota.ok) return error(429, quota.reason);

    // Providers are tried in order (AI_PROVIDER, then AI_FALLBACK). A provider error, an empty or
    // cut-off reply, or one with no JSON in it moves on; a scan nobody could read is given back
    // to the person.
    for (const provider of providerChain(env)) {
      let content: string;
      try {
        content = await provider.transcribe(image, type as ImageType);
      } catch (e) {
        console.error("provider failed", provider.name, String((e as Error).message));
        continue;
      }
      try {
        return Response.json({ ...parseScanResult(content), scansLeftToday: quota.left });
      } catch {
        console.error("unparseable reply", provider.name);
      }
    }
    await giveBack(env, uid, quota.day).catch(() => undefined);
    return error(502, "scan_failed");
  },
} satisfies ExportedHandler<Env>;

type Quota = { ok: true; left: number; day: string } | { ok: false; reason: string };

/** Counts this scan against the user's and everyone's daily limit. */
async function takeQuota(env: Env, uid: string): Promise<Quota> {
  // Days roll over at midnight in Bangladesh (UTC+6), not UTC.
  const day = new Date(Date.now() + 6 * 3600_000).toISOString().slice(0, 10);
  const perUser = Number(env.DAILY_SCANS_PER_USER);

  const used = await increment(env.DB, uid, day);
  if (used > perUser) return { ok: false, reason: "daily_limit_reached" };
  if ((await increment(env.DB, "*", day)) > Number(env.DAILY_SCANS_TOTAL)) {
    // Refused for everyone: don't charge it to this person.
    await increment(env.DB, uid, day, -1);
    return { ok: false, reason: "service_busy" };
  }
  return { ok: true, left: perUser - used, day };
}

/**
 * Returns a scan that produced nothing to the person, so failures never use up their allowance.
 * The global count keeps it: the providers were still paid, and it is what bounds the spend.
 */
async function giveBack(env: Env, uid: string, day: string): Promise<void> {
  await increment(env.DB, uid, day, -1);
}

async function increment(db: D1Database, uid: string, day: string, by = 1): Promise<number> {
  const row = await db
    .prepare(
      `INSERT INTO usage (uid, day, count) VALUES (?1, ?2, MAX(?3, 0))
       ON CONFLICT (uid, day) DO UPDATE SET count = MAX(count + ?3, 0)
       RETURNING count`,
    )
    .bind(uid, day, by)
    .first<{ count: number }>();
  return row?.count ?? Number.MAX_SAFE_INTEGER;
}

function error(status: number, code: string): Response {
  return Response.json({ error: code }, { status });
}
