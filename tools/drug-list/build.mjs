// Builds app/src/main/assets/drugs.tsv from the "Medicinal Products in Bangladesh" dataset
// (University of Dhaka, Mendeley Data, CC BY 4.0): https://data.mendeley.com/datasets/zhtvkny53n
//
//   node tools/drug-list/build.mjs "<downloaded csv>" app/src/main/assets/drugs.tsv
//
// One line per brand + form + strength: brand, strength, generic name, form. Manufacturer and
// package columns are dropped to keep the asset small.
import { readFileSync, writeFileSync } from "node:fs";

const [, , csvPath, outPath] = process.argv;
if (!csvPath || !outPath) throw new Error("usage: build.mjs <csv> <out.tsv>");

function cells(line) {
  const out = [];
  let cur = "";
  let quoted = false;
  for (const ch of line) {
    if (ch === '"') quoted = !quoted;
    else if (ch === "," && !quoted) {
      out.push(cur);
      cur = "";
    } else cur += ch;
  }
  out.push(cur);
  return out.map((c) => c.replace(/[\t\r\n]+/g, " ").replace(/\s+/g, " ").trim());
}

const [header, ...lines] = readFileSync(csvPath, "utf8").split(/\r?\n/).filter(Boolean);
const col = Object.fromEntries(cells(header).map((name, i) => [name, i]));
const seen = new Set();
const rows = [];
for (const line of lines) {
  const c = cells(line);
  const brand = c[col.brandName];
  if (!brand) continue;
  const row = [brand, c[col.strength] ?? "", c[col.genericName] ?? "", c[col.dosageType] ?? ""];
  const key = `${brand.toLowerCase()}|${row[3].toLowerCase()}|${row[1].toLowerCase()}`;
  if (seen.has(key)) continue;
  seen.add(key);
  rows.push(row.join("\t"));
}
rows.sort((a, b) => a.localeCompare(b, "en", { sensitivity: "base" }));
writeFileSync(outPath, rows.join("\n") + "\n");
console.log(`${rows.length} products → ${outPath}`);
