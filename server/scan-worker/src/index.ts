import { Buffer } from "node:buffer";
import { verifyFirebaseIdToken } from "./auth";
import { parseScanResult, SYSTEM_PROMPT, USER_PROMPT } from "./prescription";

export interface Env {
  DB: D1Database;
  DEEPSEEK_API_KEY: string;
  DEEPSEEK_MODEL: string;
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
    const image = await request.arrayBuffer();
    if (image.byteLength === 0 || image.byteLength > MAX_IMAGE_BYTES) return error(413, "image_too_large");

    const quota = await takeQuota(env, uid);
    if (!quota.ok) return error(429, quota.reason);

    const upstream = await fetch("https://api.deepseek.com/chat/completions", {
      method: "POST",
      headers: { Authorization: `Bearer ${env.DEEPSEEK_API_KEY}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        model: env.DEEPSEEK_MODEL,
        // Thinking is on by default. On real handwriting it used ~14k tokens (15× the cost, a minute
        // of waiting) without reading names any better, and could exhaust max_tokens with no answer.
        thinking: { type: "disabled" },
        temperature: 0,
        max_tokens: 4000,
        response_format: { type: "json_object" },
        messages: [
          { role: "system", content: SYSTEM_PROMPT },
          {
            role: "user",
            content: [
              {
                type: "image_url",
                image_url: { url: `data:${type};base64,${Buffer.from(image).toString("base64")}`, detail: "high" },
              },
              { type: "text", text: USER_PROMPT },
            ],
          },
        ],
      }),
    });
    if (!upstream.ok) {
      // The error body describes the request shape, not the image, so it is safe to log.
      console.error("deepseek failed", upstream.status, (await upstream.text()).slice(0, 500));
      return error(502, "scan_failed");
    }

    const completion = (await upstream.json()) as { choices?: { message?: { content?: string } }[] };
    try {
      const result = parseScanResult(completion.choices?.[0]?.message?.content ?? "");
      return Response.json({ ...result, scansLeftToday: quota.left });
    } catch {
      console.error("deepseek returned unparseable content");
      return error(502, "scan_unreadable");
    }
  },
} satisfies ExportedHandler<Env>;

type Quota = { ok: true; left: number } | { ok: false; reason: string };

/** Counts this scan against the user's and everyone's daily limit. */
async function takeQuota(env: Env, uid: string): Promise<Quota> {
  // Days roll over at midnight in Bangladesh (UTC+6), not UTC.
  const day = new Date(Date.now() + 6 * 3600_000).toISOString().slice(0, 10);
  const perUser = Number(env.DAILY_SCANS_PER_USER);

  const used = await increment(env.DB, uid, day);
  if (used > perUser) return { ok: false, reason: "daily_limit_reached" };
  if ((await increment(env.DB, "*", day)) > Number(env.DAILY_SCANS_TOTAL)) {
    return { ok: false, reason: "service_busy" };
  }
  return { ok: true, left: perUser - used };
}

async function increment(db: D1Database, uid: string, day: string): Promise<number> {
  const row = await db
    .prepare(
      `INSERT INTO usage (uid, day, count) VALUES (?1, ?2, 1)
       ON CONFLICT (uid, day) DO UPDATE SET count = count + 1
       RETURNING count`,
    )
    .bind(uid, day)
    .first<{ count: number }>();
  return row?.count ?? Number.MAX_SAFE_INTEGER;
}

function error(status: number, code: string): Response {
  return Response.json({ error: code }, { status });
}
