import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const workerDir = join(dirname(fileURLToPath(import.meta.url)), "..");
const mode = process.argv[2];
const required = ["RABBITMQ_HTTP_URL", "RABBITMQ_VHOST", "RABBITMQ_USER", "RABBITMQ_PASSWORD"];

if (mode !== "dev" && mode !== "deploy") {
  console.error("Usage: node scripts/use-env.mjs dev|deploy");
  process.exit(1);
}

const envPath = join(workerDir, ".env");
if (!existsSync(envPath)) {
  console.error("Missing webhook-worker/.env. Copy .env.example to .env and fill in the CloudAMQP values.");
  process.exit(1);
}
if (mode === "dev" && existsSync(join(workerDir, ".dev.vars"))) {
  console.error("webhook-worker/.dev.vars hides .env during local dev. Move those values into .env and delete .dev.vars.");
  process.exit(1);
}

const values = parseEnv(readFileSync(envPath, "utf8"));
const missing = required.filter((key) => !values[key] || !values[key].trim());
if (missing.length > 0) {
  console.error("webhook-worker/.env is missing: " + missing.join(", "));
  process.exit(1);
}
if (values.RABBITMQ_HTTP_URL.includes("YOUR-INSTANCE") || values.RABBITMQ_VHOST === "YOUR_VHOST") {
  console.error("Replace the placeholders in webhook-worker/.env before continuing.");
  process.exit(1);
}

const bindings = Object.fromEntries(
  Object.entries(values).filter(([, value]) => value.trim() !== "")
);

const wrangler = join(workerDir, "node_modules", "wrangler", "bin", "wrangler.js");
let status = 1;
if (mode === "dev") {
  status = spawnSync(process.execPath, [wrangler, "dev"], {
    cwd: workerDir,
    stdio: "inherit",
  }).status ?? 1;
} else {
  const dir = mkdtempSync(join(tmpdir(), "webhook-worker-env-"));
  const file = join(dir, "secrets.json");
  writeFileSync(file, JSON.stringify(bindings));
  status = spawnSync(process.execPath, [wrangler, "deploy", "--secrets-file", file], {
    cwd: workerDir,
    stdio: "inherit",
  }).status ?? 1;
  rmSync(dir, { recursive: true, force: true });
}
process.exit(status);

function parseEnv(text) {
  const out = {};
  for (const raw of text.split("\n")) {
    const line = raw.trim();
    if (!line || line.startsWith("#")) {
      continue;
    }
    const eq = line.indexOf("=");
    if (eq <= 0) {
      continue;
    }
    const key = line.slice(0, eq).trim();
    let value = line.slice(eq + 1).trim();
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }
    out[key] = value;
  }
  return out;
}
