import { Buffer } from "node:buffer";
import { AwsClient } from "aws4fetch";
import { SYSTEM_PROMPT, USER_PROMPT } from "./prescription.ts";

export type ImageType = "image/jpeg" | "image/png" | "image/webp";

/**
 * One vision model that can read a prescription photo. Each returns the model's raw text, which
 * `parseScanResult` checks, so swapping providers never changes what the app receives.
 */
export interface VisionProvider {
  readonly name: string;
  transcribe(image: ArrayBuffer, type: ImageType): Promise<string>;
}

export interface ProviderEnv {
  /** "deepseek" or "bedrock". */
  AI_PROVIDER: string;
  /** Optional second provider tried when the first one errors. Empty to disable. */
  AI_FALLBACK?: string;
  DEEPSEEK_API_KEY?: string;
  DEEPSEEK_MODEL: string;
  BEDROCK_REGION: string;
  BEDROCK_MODEL: string;
  AWS_ACCESS_KEY_ID?: string;
  AWS_SECRET_ACCESS_KEY?: string;
}

/**
 * The configured provider first, then the fallback if one is set and differs. A provider whose
 * secrets are missing is skipped with a log line rather than failing every scan.
 */
export function providerChain(env: ProviderEnv): VisionProvider[] {
  const names = [env.AI_PROVIDER, env.AI_FALLBACK]
    .map((n) => n?.trim().toLowerCase())
    .filter((n, i, all): n is string => !!n && all.indexOf(n) === i);
  return names.flatMap((name) => {
    try {
      return [createProvider(name, env)];
    } catch (e) {
      console.error("provider misconfigured", name, String((e as Error).message));
      return [];
    }
  });
}

function createProvider(name: string, env: ProviderEnv): VisionProvider {
  switch (name) {
    case "deepseek":
      return new DeepSeekProvider(required(env.DEEPSEEK_API_KEY, "DEEPSEEK_API_KEY"), env.DEEPSEEK_MODEL);
    case "bedrock":
      return new BedrockProvider(
        env.BEDROCK_REGION,
        env.BEDROCK_MODEL,
        required(env.AWS_ACCESS_KEY_ID, "AWS_ACCESS_KEY_ID"),
        required(env.AWS_SECRET_ACCESS_KEY, "AWS_SECRET_ACCESS_KEY"),
      );
    default:
      throw new Error(`unknown AI provider "${name}"`);
  }
}

function required(value: string | undefined, name: string): string {
  if (!value) throw new Error(`${name} is not set`);
  return value;
}

/** Thrown for upstream failures. The message describes the request, never the image. */
export class ProviderError extends Error {}

class DeepSeekProvider implements VisionProvider {
  readonly name = "deepseek";
  private readonly apiKey: string;
  private readonly model: string;

  constructor(apiKey: string, model: string) {
    this.apiKey = apiKey;
    this.model = model;
  }

  async transcribe(image: ArrayBuffer, type: ImageType): Promise<string> {
    let response: Response;
    try {
      response = await this.request(image, type);
    } catch (e) {
      throw new ProviderError(`deepseek: ${String((e as Error).message).slice(0, 500)}`);
    }
    if (!response.ok) {
      // The error body describes the request shape, not the image, so it is safe to log.
      throw new ProviderError(`deepseek ${response.status}: ${(await response.text()).slice(0, 500)}`);
    }
    const completion = (await response.json()) as {
      choices?: { finish_reason?: string; message?: { content?: string } }[];
    };
    const choice = completion.choices?.[0];
    if (choice?.finish_reason === "length") throw new ProviderError("deepseek reply cut off at max_tokens");
    const content = choice?.message?.content ?? "";
    if (!content.trim()) throw new ProviderError(`deepseek empty reply (${choice?.finish_reason ?? "no choice"})`);
    return content;
  }

  private request(image: ArrayBuffer, type: ImageType): Promise<Response> {
    return fetch("https://api.deepseek.com/chat/completions", {
      method: "POST",
      signal: AbortSignal.timeout(PROVIDER_TIMEOUT_MILLIS),
      headers: { Authorization: `Bearer ${this.apiKey}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        model: this.model,
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
  }
}

/**
 * Any vision model on Amazon Bedrock through the Converse API (`bedrock-runtime.<region>`), so
 * changing model is only BEDROCK_MODEL. The IAM user needs `bedrock:InvokeModel` on that model.
 * Cross-region models are called by inference-profile ID, e.g. "global.anthropic.claude-haiku-5-5".
 */
class BedrockProvider implements VisionProvider {
  readonly name = "bedrock";
  private readonly client: AwsClient;
  private readonly url: string;
  private readonly model: string;

  constructor(region: string, model: string, accessKey: string, secretKey: string) {
    this.model = model;
    this.url = `https://bedrock-runtime.${region}.amazonaws.com/model/${encodeURIComponent(model)}/converse`;
    // One attempt: the app shows its own retry, and a slow retry here would hold the user waiting.
    this.client = new AwsClient({ accessKeyId: accessKey, secretAccessKey: secretKey, service: "bedrock", region, retries: 0 });
  }

  async transcribe(image: ArrayBuffer, type: ImageType): Promise<string> {
    let response: Response;
    try {
      response = await this.client.fetch(this.url, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        signal: AbortSignal.timeout(PROVIDER_TIMEOUT_MILLIS),
        body: JSON.stringify({
          system: [{ text: SYSTEM_PROMPT }],
          messages: [
            {
              role: "user",
              content: [
                { image: { format: type.slice("image/".length), source: { bytes: Buffer.from(image).toString("base64") } } },
                { text: USER_PROMPT },
              ],
            },
          ],
          inferenceConfig: { maxTokens: 4000, ...temperature(this.model) },
          ...thinkingOff(this.model),
        }),
      });
    } catch (e) {
      throw new ProviderError(`bedrock: ${String((e as Error).message).slice(0, 500)}`);
    }
    if (!response.ok) {
      // The error body describes the request shape, not the image, so it is safe to log.
      throw new ProviderError(`bedrock ${response.status}: ${(await response.text()).slice(0, 500)}`);
    }
    const reply = (await response.json()) as {
      stopReason?: string;
      output?: { message?: { content?: { text?: string }[] } };
    };
    // Cut off, filtered or blocked: whatever came back is not a whole answer.
    if (reply.stopReason === "max_tokens") throw new ProviderError("bedrock reply cut off at max_tokens");
    if (reply.stopReason && reply.stopReason !== "end_turn" && reply.stopReason !== "stop_sequence") {
      throw new ProviderError(`bedrock stopped: ${reply.stopReason}`);
    }
    const text = (reply.output?.message?.content ?? []).flatMap((block) => (block.text ? [block.text] : [])).join("");
    if (!text.trim()) throw new ProviderError("bedrock empty reply");
    return text;
  }
}

/**
 * Each provider gets this long. Two in a row stay inside the app's 120 s read timeout, so a
 * fallback answer still reaches the phone.
 */
const PROVIDER_TIMEOUT_MILLIS = 50_000;

/** Steady transcription. Claude keeps its default sampling, as it did before this used Converse. */
function temperature(model: string): { temperature?: number } {
  return model.includes("anthropic.") ? {} : { temperature: 0 };
}

/**
 * Transcription needs no reasoning, and thinking only adds cost and wait. How to turn it off
 * differs by model: Haiku accepts `disabled`, Sonnet 5.5 uses `between_tools` (no tools here, so
 * no thinking), and Opus cannot turn it off, so it gets the lowest effort instead. Other models
 * on Bedrock (Kimi K2.5, Llama, Nova) do not think unless asked.
 */
function thinkingOff(model: string): { additionalModelRequestFields?: Record<string, unknown> } {
  if (model.includes("haiku")) return { additionalModelRequestFields: { thinking: { type: "disabled" } } };
  if (model.includes("sonnet")) return { additionalModelRequestFields: { thinking: { type: "between_tools" } } };
  if (model.includes("opus")) return { additionalModelRequestFields: { output_config: { effort: "low" } } };
  return {};
}
