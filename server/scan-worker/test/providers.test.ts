import assert from "node:assert/strict";
import { test } from "node:test";
import { ProviderError, providerChain, type ProviderEnv } from "../src/providers.ts";

const base: ProviderEnv = {
  AI_PROVIDER: "deepseek",
  AI_FALLBACK: "",
  DEEPSEEK_API_KEY: "test",
  DEEPSEEK_MODEL: "deepseek-flash",
  BEDROCK_REGION: "ap-south-1",
  BEDROCK_MODEL: "anthropic.claude-haiku-5-5",
  AWS_ACCESS_KEY_ID: "test",
  AWS_SECRET_ACCESS_KEY: "test",
};

test("primary then fallback, deduplicated", () => {
  assert.deepEqual(providerChain({ ...base, AI_PROVIDER: "bedrock", AI_FALLBACK: "deepseek" }).map((p) => p.name), ["bedrock", "deepseek"]);
  assert.deepEqual(providerChain({ ...base, AI_FALLBACK: " DeepSeek " }).map((p) => p.name), ["deepseek"]);
});

test("a provider without its secrets is skipped, not fatal", () => {
  const chain = providerChain({ ...base, AI_PROVIDER: "bedrock", AI_FALLBACK: "deepseek", AWS_SECRET_ACCESS_KEY: undefined });
  assert.deepEqual(chain.map((p) => p.name), ["deepseek"]);
});

test("unknown provider names are skipped", () => {
  assert.deepEqual(providerChain({ ...base, AI_PROVIDER: "gpt" }).map((p) => p.name), []);
});

test("bedrock calls Converse for the configured model and joins the text reply", async (t) => {
  const sent: Request[] = [];
  t.mock.method(globalThis, "fetch", async (request: Request) => {
    sent.push(request.clone());
    return Response.json({ stopReason: "end_turn", output: { message: { content: [{ text: '{"isPrescription":' }, { text: "true}" }] } } });
  });
  const [bedrock] = providerChain({ ...base, AI_PROVIDER: "bedrock", BEDROCK_MODEL: "moonshotai.kimi-k2.5" });
  assert.equal(await bedrock.transcribe(new Uint8Array([1, 2, 3]).buffer, "image/png"), '{"isPrescription":true}');

  assert.equal(sent[0].url, "https://bedrock-runtime.ap-south-1.amazonaws.com/model/moonshotai.kimi-k2.5/converse");
  assert.match(sent[0].headers.get("authorization") ?? "", /^AWS4-HMAC-SHA256 Credential=test\/\d+\/ap-south-1\/bedrock\/aws4_request/);
  const body = await sent[0].json();
  assert.deepEqual(body.messages[0].content[0], { image: { format: "png", source: { bytes: "AQID" } } });
  assert.deepEqual(body.inferenceConfig, { maxTokens: 4000, temperature: 0 });
  assert.equal(body.additionalModelRequestFields, undefined);
});

test("bedrock turns Claude's thinking off and reports cut-off or failed replies", async (t) => {
  const replies = [
    Response.json({ stopReason: "max_tokens", output: { message: { content: [{ text: "{" }] } } }),
    new Response('{"message":"not available for this account"}', { status: 403 }),
  ];
  const sent: Request[] = [];
  t.mock.method(globalThis, "fetch", async (request: Request) => (sent.push(request.clone()), replies.shift()!));
  const [bedrock] = providerChain({ ...base, AI_PROVIDER: "bedrock", BEDROCK_MODEL: "global.anthropic.claude-haiku-5-5" });
  await assert.rejects(bedrock.transcribe(new ArrayBuffer(1), "image/jpeg"), /cut off at max_tokens/);
  await assert.rejects(bedrock.transcribe(new ArrayBuffer(1), "image/jpeg"), /bedrock 403: .*not available/);

  const body = await sent[0].json();
  assert.deepEqual(body.additionalModelRequestFields, { thinking: { type: "disabled" } });
  assert.deepEqual(body.inferenceConfig, { maxTokens: 4000 });
});

test("empty, cut-off or filtered replies are provider errors, so the fallback is tried", async (t) => {
  const replies = [
    Response.json({ stopReason: "content_filtered", output: { message: { content: [] } } }),
    Response.json({ stopReason: "end_turn", output: { message: { content: [{ text: "  " }] } } }),
    Response.json({ choices: [{ finish_reason: "length", message: { content: '{"isPres' } }] }),
    Response.json({ choices: [] }),
  ];
  t.mock.method(globalThis, "fetch", async () => replies.shift()!);
  const [bedrock] = providerChain({ ...base, AI_PROVIDER: "bedrock", BEDROCK_MODEL: "moonshotai.kimi-k2.5" });
  const [deepseek] = providerChain(base);
  await assert.rejects(bedrock.transcribe(new ArrayBuffer(1), "image/jpeg"), ProviderError);
  await assert.rejects(bedrock.transcribe(new ArrayBuffer(1), "image/jpeg"), ProviderError);
  await assert.rejects(deepseek.transcribe(new ArrayBuffer(1), "image/jpeg"), ProviderError);
  await assert.rejects(deepseek.transcribe(new ArrayBuffer(1), "image/jpeg"), ProviderError);
});
