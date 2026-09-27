#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import { checkSamples, loadFormats, loadSamples, normalizedFormat } from "./apply.js";

const FLAGS = new Set(["normalized-v1", "enriched"]);

function parseArgs(argv) {
  const args = { _: [] };
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (!token.startsWith("--")) {
      args._.push(token);
      continue;
    }
    const key = token.slice(2);
    const next = argv[i + 1];
    if (next == null || next.startsWith("--")) {
      args[key] = true;
    } else {
      args[key] = next;
      i += 1;
    }
  }
  return args;
}

function readConfig(file) {
  if (!file) {
    return { values: {}, dir: process.cwd() };
  }
  const abs = path.resolve(file);
  return { values: JSON.parse(fs.readFileSync(abs, "utf8")), dir: path.dirname(abs) };
}

function pick(cli, env, fileValue) {
  if (cli != null && cli !== true) {
    return { value: cli, from: "cli" };
  }
  if (env != null && env !== "") {
    return { value: env, from: "env" };
  }
  if (fileValue != null && fileValue !== "") {
    return { value: fileValue, from: "file" };
  }
  return { value: undefined, from: "none" };
}

function resolvePath(chosen, configDir) {
  if (!chosen.value) {
    return undefined;
  }
  const base = chosen.from === "file" ? configDir : process.cwd();
  return path.isAbsolute(chosen.value) ? chosen.value : path.resolve(base, chosen.value);
}

function settingsFrom(argv) {
  const args = parseArgs(argv);
  const configPath = args.config || process.env.KAFKA_EVENT_FORMAT_CONFIG;
  const { values, dir } = readConfig(configPath);
  const format = pick(args.format, process.env.KAFKA_EVENT_FORMAT, values.format);
  const formatsDir = pick(args.formats, process.env.KAFKA_EVENT_FORMATS_DIR, values.formatsDir);
  const samplesDir = pick(args.samples, process.env.KAFKA_EVENT_FORMAT_SAMPLES, values.samplesDir);
  const consume = pick(args.consume, process.env.KAFKA_EVENT_FORMAT_CONSUME, values.consume);
  const produce = pick(
    args.produce,
    process.env.KAFKA_EVENT_FORMAT_PRODUCE || process.env.GIT_WEBHOOK_KAFKA_INCREMENTAL_TOPIC,
    values.produce
  );
  const bootstrap = pick(
    args["bootstrap-servers"],
    process.env.GIT_WEBHOOK_KAFKA_BOOTSTRAP_SERVERS,
    values.bootstrapServers
  );
  const groupId = pick(args["group-id"], process.env.KAFKA_EVENT_FORMAT_GROUP_ID, values.groupId);
  const security = pick(
    args["security-protocol"],
    process.env.GIT_WEBHOOK_KAFKA_SECURITY_PROTOCOL,
    values.securityProtocol
  );
  const saslMechanism = pick(
    args["sasl-mechanism"],
    process.env.GIT_WEBHOOK_KAFKA_SASL_MECHANISM,
    values.saslMechanism
  );
  const saslUsername = pick(
    args["sasl-username"],
    process.env.GIT_WEBHOOK_KAFKA_SASL_USERNAME,
    values.saslUsername
  );
  const saslPassword = pick(
    args["sasl-password"],
    process.env.GIT_WEBHOOK_KAFKA_SASL_PASSWORD,
    values.saslPassword
  );
  return {
    command: args._[0],
    format: format.value,
    formatsDir: resolvePath(formatsDir, dir),
    samplesDir: resolvePath(samplesDir, dir),
    consume: consume.value,
    produce: produce.value || "git.sync.incremental",
    bootstrapServers: bootstrap.value,
    groupId: groupId.value || "kafka-event-format",
    securityProtocol: security.value || "PLAINTEXT",
    saslMechanism: saslMechanism.value || "PLAIN",
    saslUsername: saslUsername.value,
    saslPassword: saslPassword.value,
  };
}

function activeFormats(settings) {
  if (!FLAGS.has(settings.format)) {
    throw new Error("KAFKA_EVENT_FORMAT must be normalized-v1 (current events) or enriched (mapping files)");
  }
  if (settings.format === "normalized-v1") {
    return [normalizedFormat()];
  }
  return loadFormats(settings.formatsDir);
}

function usage() {
  return `kafka-format check|bridge

Feature flag (KAFKA_EVENT_FORMAT or --format):
  normalized-v1   current IncrementalGitEvent, copied through
  enriched        mapping files in --formats / KAFKA_EVENT_FORMATS_DIR

check  --format enriched --formats ./formats --samples ./samples
bridge --format enriched --formats ./formats --consume enriched.git.events --produce git.sync.incremental

A JSON config file (--config / KAFKA_EVENT_FORMAT_CONFIG) supplies the same fields.
CLI overrides env. Env overrides the file.`;
}

async function main() {
  const settings = settingsFrom(process.argv.slice(2));
  if (!settings.command || settings.command === "help") {
    console.log(usage());
    return;
  }
  const formats = activeFormats(settings);
  if (settings.command === "check") {
    const samples = loadSamples(settings.samplesDir);
    const result = checkSamples(samples, formats);
    if (result.lines.length > 0) {
      console.log(result.lines.join("\n"));
    }
    if (!result.ok) {
      for (const failure of result.failures) {
        console.error(failure);
      }
      process.exitCode = 1;
      return;
    }
    console.error(`ok: ${samples.length} samples, format=${settings.format}`);
    return;
  }
  if (settings.command === "bridge") {
    await runBridge(settings, formats);
    return;
  }
  console.error(usage());
  process.exitCode = 1;
}

main().catch((err) => {
  console.error(err.message || err);
  process.exitCode = 1;
});
