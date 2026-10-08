import assert from "node:assert/strict";
import { test } from "node:test";
import { parseDosePattern, parseScanResult } from "../src/prescription.ts";

test("three-part patterns are morning + noon + night", () => {
  assert.deepEqual(parseDosePattern("1+0+1"), [1, 0, 0, 1]);
  assert.deepEqual(parseDosePattern("0-1-0"), [0, 1, 0, 0]);
  assert.deepEqual(parseDosePattern("০+০+১"), [0, 0, 0, 1]);
  assert.deepEqual(parseDosePattern("½+0+½"), [0.5, 0, 0, 0.5]);
  assert.deepEqual(parseDosePattern("1/2 + 0 + 1"), [0.5, 0, 0, 1]);
});

test("four-part patterns include evening", () => {
  assert.deepEqual(parseDosePattern("1+0+1+0"), [1, 0, 1, 0]);
  assert.deepEqual(parseDosePattern("১+১+১+১"), [1, 1, 1, 1]);
});

test("unreadable patterns give null rather than a guess", () => {
  for (const p of [null, "", "1+0", "1+x+1", "once daily", "1+0+1+0+1"]) assert.equal(parseDosePattern(p), null);
});

test("model output is coerced and never trusted", () => {
  const r = parseScanResult("```json\n" + JSON.stringify({
    isPrescription: true,
    medicines: [
      { name: " Rolip ", form: "tablet", strength: "10", doseText: "0+0+1", durationText: "চলবে", food: "after", confidence: "high" },
      { name: "Evo", form: "pill", doseText: "garbled", confidence: "high" },
      { name: "", doseText: "1+0+1" },
      "not an object",
    ],
    notes: 42,
  }) + "\n```");
  assert.equal(r.medicines.length, 2);
  assert.deepEqual(
    { ...r.medicines[0] },
    { name: "Rolip", form: "tablet", strength: "10", doseText: "0+0+1", morning: 0, noon: 0, evening: 0, night: 1,
      frequency: null, food: "after", durationText: "চলবে", instructions: null, confidence: "high" },
  );
  assert.equal(r.medicines[1].form, null);
  assert.equal(r.medicines[1].morning, null);
  assert.equal(r.medicines[1].confidence, "medium");
  assert.equal(r.notes, null);
});
