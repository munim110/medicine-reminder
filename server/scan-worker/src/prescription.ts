/** What the app receives. Every field is checked here; the model's output is never passed through raw. */
export interface ScanResult {
  isPrescription: boolean;
  medicines: Medicine[];
  notes: string | null;
}

export interface Medicine {
  name: string;
  form: Form | null;
  strength: string | null;
  /** The dose pattern exactly as written, e.g. "1+0+1". Shown to the reviewer next to the parsed values. */
  doseText: string | null;
  morning: number | null;
  noon: number | null;
  evening: number | null;
  night: number | null;
  frequency: Frequency | null;
  food: Food | null;
  /**
   * The duration exactly as written, e.g. "২০ দিন" or "চলবে". Shown for information only: the app sets
   * no end dates, and the family removes a medicine themselves when the course is over.
   */
  durationText: string | null;
  instructions: string | null;
  confidence: Confidence;
}

const FORMS = ["tablet", "capsule", "syrup", "injection", "drops", "inhaler", "cream", "other"] as const;
const FOODS = ["before", "after", "empty_stomach", "any"] as const;
const FREQUENCIES = ["daily", "weekly", "monthly", "as_needed"] as const;
const CONFIDENCES = ["high", "medium", "low"] as const;
type Form = (typeof FORMS)[number];
type Food = (typeof FOODS)[number];
type Frequency = (typeof FREQUENCIES)[number];
type Confidence = (typeof CONFIDENCES)[number];

// The model copies the dose pattern verbatim and code below turns "1+0+1" into slots. Left to the
// model, the mapping was inconsistent on real handwriting (1+0+1 became morning + evening).
export const SYSTEM_PROMPT = `You transcribe photographed medical prescriptions from Bangladesh into JSON.

Transcribe only what is written. Never add, guess or "correct" a medicine. A wrong medicine is far
worse than a missing one: if a name is hard to read, give your best reading and set confidence to "low".

Prescriptions may be handwritten or printed, in English and/or Bengali. Medicine brand names are
usually in English, often prefixed "Tab.", "Cap.", "Syp.", "Inj." — drop the prefix from the name
and use it for "form".

Text that is crossed out or struck through is NOT prescribed: skip it entirely. Printed stamps often
list several options with all but one struck through; include only the option left standing.

Copy these two fields character for character, without interpreting them:
- "doseText": the dose pattern, e.g. "1+0+1", "1-0-1", "০+০+১", "½+0+½". Keep Bengali digits as Bengali.
- "durationText": the duration, e.g. "২০ দিন", "7 days", "১ মাস", "চলবে", "cont.". null if none is written.
Do not convert them to numbers and do not invent a duration that is not written.

frequency: "daily" for normal dose patterns; "weekly" or "monthly" when written as per week / per month
(e.g. "প্রতি সপ্তাহে", "সপ্তাহে ১টি"); "as_needed" for "প্রয়োজনে", "SOS", "PRN"; null if unclear.

food: "খাবার আগে", "before meal", "a.c." → "before"; "খাবার পরে", "খাবার পর", "after meal", "p.c." →
"after"; "খালি পেটে", "empty stomach" → "empty_stomach"; not stated → null.

Ignore the patient's name, age, address, diagnosis, tests and general advice. Never output any of them.

Reply with JSON only, exactly this shape:
{
  "isPrescription": boolean,
  "medicines": [{
    "name": string,
    "form": "tablet" | "capsule" | "syrup" | "injection" | "drops" | "inhaler" | "cream" | "other" | null,
    "strength": string | null,
    "doseText": string | null,
    "frequency": "daily" | "weekly" | "monthly" | "as_needed" | null,
    "food": "before" | "after" | "empty_stomach" | "any" | null,
    "durationText": string | null,
    "instructions": string | null,
    "confidence": "high" | "medium" | "low"
  }],
  "notes": string | null
}
Use "notes" for anything the person reviewing should double-check. If the image is not a
prescription, return isPrescription false and an empty medicines list.`;

export const USER_PROMPT = "Transcribe the medicines on this prescription as JSON.";

/** Parses the model's reply and coerces it into [ScanResult], dropping anything malformed. */
export function parseScanResult(content: string): ScanResult {
  // The object itself, without code fences or any "Here is the JSON:" a model wraps it in.
  const start = content.indexOf("{");
  const end = content.lastIndexOf("}");
  if (start < 0 || end < start) throw new SyntaxError("no JSON object in reply");
  const raw = JSON.parse(content.slice(start, end + 1)) as Record<string, unknown>;
  const medicines = Array.isArray(raw.medicines) ? raw.medicines : [];
  return {
    isPrescription: raw.isPrescription === true,
    medicines: medicines.slice(0, 30).map(toMedicine).filter((m): m is Medicine => m !== null),
    notes: text(raw.notes, 500),
  };
}

function toMedicine(value: unknown): Medicine | null {
  if (typeof value !== "object" || value === null) return null;
  const m = value as Record<string, unknown>;
  const name = text(m.name, 100);
  if (!name) return null;

  const doseText = text(m.doseText, 40);
  const durationText = text(m.durationText, 40);
  const slots = parseDosePattern(doseText);
  const modelConfidence = oneOf(m.confidence, CONFIDENCES) ?? "low";

  return {
    name,
    form: oneOf(m.form, FORMS),
    strength: text(m.strength, 40),
    doseText,
    morning: slots?.[0] ?? null,
    noon: slots?.[1] ?? null,
    evening: slots?.[2] ?? null,
    night: slots?.[3] ?? null,
    frequency: oneOf(m.frequency, FREQUENCIES),
    food: oneOf(m.food, FOODS),
    durationText,
    instructions: text(m.instructions, 200),
    // A dose pattern that was written but could not be read is never "high", whatever the model says.
    confidence: doseText && !slots && modelConfidence === "high" ? "medium" : modelConfidence,
  };
}

const BENGALI_DIGITS = "০১২৩৪৫৬৭৮৯";

function latinDigits(s: string): string {
  return s.replace(/[০-৯]/g, (d) => String(BENGALI_DIGITS.indexOf(d)));
}

/**
 * "1+0+1" → [1, 0, 0, 1] as morning, noon, evening, night. Three parts are morning + noon + night,
 * the Bangladeshi convention; four parts include evening. Anything else returns null.
 */
export function parseDosePattern(doseText: string | null): [number, number, number, number] | null {
  if (!doseText) return null;
  const parts = latinDigits(doseText)
    .replace(/½/g, "0.5")
    .split(/\s*[+\-–—]\s*/)
    .map((p) => p.trim());
  if (parts.length !== 3 && parts.length !== 4) return null;

  const values = parts.map((p) => {
    if (/^\d+(\.\d+)?$/.test(p)) return Number(p);
    const fraction = p.match(/^(\d+)\/(\d+)$/);
    return fraction && Number(fraction[2]) > 0 ? Number(fraction[1]) / Number(fraction[2]) : NaN;
  });
  if (values.some((v) => Number.isNaN(v) || v < 0 || v > 10)) return null;

  return values.length === 3
    ? [values[0], values[1], 0, values[2]]
    : [values[0], values[1], values[2], values[3]];
}

function text(value: unknown, max: number): string | null {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed ? trimmed.slice(0, max) : null;
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[]): T | null {
  return typeof value === "string" && (allowed as readonly string[]).includes(value) ? (value as T) : null;
}
