// Security rules for family sharing, run against the Firestore emulator: `npm test`.
import { readFileSync } from "node:fs";
import { after, before, beforeEach, test } from "node:test";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";
import {
  Timestamp,
  addDoc,
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  serverTimestamp,
  setDoc,
  writeBatch,
} from "firebase/firestore";

const PATIENT = "patient-1";
const GUARDIAN = "guardian-1";
const STRANGER = "stranger-1";
const CODE = "ABCD2345";

let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-medremind",
    firestore: { rules: readFileSync(new URL("../../../firestore.rules", import.meta.url), "utf8") },
  });
});

after(async () => env?.cleanup());

beforeEach(async () => {
  await env.clearFirestore();
  // A patient with a schedule, one dose logged, and a live pairing code.
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, `patients/${PATIENT}`), { name: "আম্মা", slots: [] });
    await setDoc(doc(db, `patients/${PATIENT}/logs/4_2026-10-07`), { date: "2026-10-07", status: "TAKEN" });
    await setDoc(doc(db, `pairCodes/${CODE}`), {
      patient: PATIENT,
      name: "আম্মা",
      expiresAt: Timestamp.fromMillis(Date.now() + 10 * 60_000),
    });
  });
});

const anonymous = (uid) => env.authenticatedContext(uid, { firebase: { sign_in_provider: "anonymous" } }).firestore();
const google = (uid) =>
  env.authenticatedContext(uid, { firebase: { sign_in_provider: "google.com" }, email: `${uid}@example.com` }).firestore();

/** What the app's Guardian.link() writes. */
function join(db, uid, code = CODE, patient = PATIENT) {
  const batch = writeBatch(db);
  batch.set(doc(db, `patients/${patient}/guardians/${uid}`), { code, name: "Rahim", email: null, addedAt: serverTimestamp() });
  batch.set(doc(db, `users/${uid}/linked/${patient}`), { name: "আম্মা", addedAt: serverTimestamp() });
  return batch.commit();
}

test("the patient reads and writes their own data", async () => {
  const db = anonymous(PATIENT);
  await assertSucceeds(setDoc(doc(db, `patients/${PATIENT}`), { name: "আম্মা", lastSeen: serverTimestamp() }));
  await assertSucceeds(
    setDoc(doc(db, `patients/${PATIENT}/logs/1_2026-10-08`), {
      groupId: 1, date: "2026-10-08", label: "সকাল", medicines: "Empa 10", status: "MISSED",
      scheduledAt: 1, actionedAt: 0, updatedAt: serverTimestamp(),
    }),
  );
  await assertSucceeds(getDocs(collection(db, `patients/${PATIENT}/guardians`)));
});

test("strangers see nothing", async () => {
  const db = google(STRANGER);
  await assertFails(getDoc(doc(db, `patients/${PATIENT}`)));
  await assertFails(getDocs(collection(db, `patients/${PATIENT}/logs`)));
  await assertFails(setDoc(doc(db, `patients/${PATIENT}`), { name: "x" }));
});

test("pairing codes cannot be listed, only fetched by exact code", async () => {
  const db = google(STRANGER);
  await assertFails(getDocs(collection(db, "pairCodes")));
  await assertSucceeds(getDoc(doc(db, `pairCodes/${CODE}`)));
});

test("a Google user with a live code becomes a guardian and can read", async () => {
  const db = google(GUARDIAN);
  await assertSucceeds(join(db, GUARDIAN));
  await assertSucceeds(getDoc(doc(db, `patients/${PATIENT}`)));
  await assertSucceeds(getDocs(collection(db, `patients/${PATIENT}/logs`)));
  await assertSucceeds(getDocs(collection(db, `users/${GUARDIAN}/linked`)));
});

test("an anonymous user cannot become a guardian", async () => {
  await assertFails(join(anonymous(GUARDIAN), GUARDIAN));
});

test("expired, unknown or another patient's codes are refused", async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), "pairCodes/OLDC0DE2"), {
      patient: PATIENT, name: "x", expiresAt: Timestamp.fromMillis(Date.now() - 60_000),
    });
    await setDoc(doc(ctx.firestore(), "pairCodes/OTHERP22"), {
      patient: "someone-else", name: "x", expiresAt: Timestamp.fromMillis(Date.now() + 60_000),
    });
  });
  const db = google(GUARDIAN);
  await assertFails(join(db, GUARDIAN, "OLDC0DE2"));
  await assertFails(join(db, GUARDIAN, "NOSUCH22"));
  await assertFails(join(db, GUARDIAN, "OTHERP22"));
});

test("nobody can add someone else as a guardian", async () => {
  const db = google(STRANGER);
  await assertFails(
    setDoc(doc(db, `patients/${PATIENT}/guardians/${GUARDIAN}`), { code: CODE, name: "x", email: null, addedAt: serverTimestamp() }),
  );
});

test("guardians cannot write patient data, only request edits as themselves", async () => {
  const db = google(GUARDIAN);
  await join(db, GUARDIAN);
  await assertFails(setDoc(doc(db, `patients/${PATIENT}`), { name: "hacked" }));
  await assertFails(setDoc(doc(db, `patients/${PATIENT}/logs/1_2026-10-08`), { date: "2026-10-08", status: "TAKEN" }));
  await assertSucceeds(
    addDoc(collection(db, `patients/${PATIENT}/edits`), {
      type: "add_stock", medicineId: 3, amount: 30, by: GUARDIAN, byName: "Rahim", createdAt: serverTimestamp(),
    }),
  );
  await assertFails(
    addDoc(collection(db, `patients/${PATIENT}/edits`), {
      type: "add_stock", medicineId: 3, amount: 30, by: "someone-else", byName: "x", createdAt: serverTimestamp(),
    }),
  );
  await assertFails(
    addDoc(collection(db, `patients/${PATIENT}/edits`), {
      type: "run_code", by: GUARDIAN, byName: "x", createdAt: serverTimestamp(),
    }),
  );
});

test("strangers cannot request edits", async () => {
  await assertFails(
    addDoc(collection(google(STRANGER), `patients/${PATIENT}/edits`), {
      type: "add_stock", medicineId: 3, amount: 30, by: STRANGER, byName: "x", createdAt: serverTimestamp(),
    }),
  );
});

test("the patient applies and deletes edits; the guardian cannot delete them", async () => {
  const g = google(GUARDIAN);
  await join(g, GUARDIAN);
  const edit = await addDoc(collection(g, `patients/${PATIENT}/edits`), {
    type: "set_time", slotId: 1, hour: 8, minute: 0, by: GUARDIAN, byName: "Rahim", createdAt: serverTimestamp(),
  });
  await assertFails(deleteDoc(doc(g, edit.path)));
  await assertSucceeds(getDocs(collection(anonymous(PATIENT), `patients/${PATIENT}/edits`)));
  await assertSucceeds(deleteDoc(doc(anonymous(PATIENT), edit.path)));
});

test("removing a guardian ends their access", async () => {
  const g = google(GUARDIAN);
  await join(g, GUARDIAN);
  await assertSucceeds(deleteDoc(doc(anonymous(PATIENT), `patients/${PATIENT}/guardians/${GUARDIAN}`)));
  await assertFails(getDoc(doc(g, `patients/${PATIENT}`)));
});

test("a guardian can leave on their own", async () => {
  const g = google(GUARDIAN);
  await join(g, GUARDIAN);
  await assertSucceeds(deleteDoc(doc(g, `patients/${PATIENT}/guardians/${GUARDIAN}`)));
  await assertSucceeds(deleteDoc(doc(g, `users/${GUARDIAN}/linked/${PATIENT}`)));
});

test("patients only issue codes for themselves, with a short expiry", async () => {
  const db = anonymous(PATIENT);
  await assertSucceeds(
    setDoc(doc(db, "pairCodes/NEWC0DE2"), { patient: PATIENT, name: "আম্মা", expiresAt: Timestamp.fromMillis(Date.now() + 15 * 60_000) }),
  );
  await assertFails(
    setDoc(doc(db, "pairCodes/NEWC0DE3"), { patient: "someone-else", name: "x", expiresAt: Timestamp.fromMillis(Date.now() + 60_000) }),
  );
  await assertFails(
    setDoc(doc(db, "pairCodes/NEWC0DE4"), { patient: PATIENT, name: "x", expiresAt: Timestamp.fromMillis(Date.now() + 48 * 3600_000) }),
  );
});

test("a guardian's own list is private to them", async () => {
  await join(google(GUARDIAN), GUARDIAN);
  await assertFails(getDocs(collection(google(STRANGER), `users/${GUARDIAN}/linked`)));
});
