import fs from "node:fs";
import path from "node:path";

/** Git incremental types. A non-create zero afterSha is also a delete inside Hub. */
export const GIT_EVENT_TYPES = ["push", "create", "delete"];

/** Metadata types. Hub requires rawPayload for these. */
export const METADATA_EVENT_TYPES = ["pull_request", "release", "status", "check_run"];

export const HUB_EVENT_TYPES = [...GIT_EVENT_TYPES, ...METADATA_EVENT_TYPES];

const CANONICAL_FIELDS = [
  "provider",
  "repoUrl",
  "ref",
  "beforeSha",
  "afterSha",
  "deliveryId",
  "eventType",
  "rawPayload",
  "receivedAt",
];

const metadataTypes = new Set(METADATA_EVENT_TYPES);
const hubTypes = new Set(HUB_EVENT_TYPES);

/**
 * Feature flag 1. Matches today's IncrementalGitEvent and copies those fields through.
 */
export function normalizedFormat() {
  return {
    id: "normalized-v1",
    when: { pointer: "/eventType", in: HUB_EVENT_TYPES },
    fields: Object.fromEntries(CANONICAL_FIELDS.map((field) => [field, `/${field}`])),
    eventTypeMap: {},
  };
}

export function getPointer(doc, pointer) {
  if (pointer == null || pointer === "") {
    return doc;
  }
  if (!String(pointer).startsWith("/")) {
    throw new Error(`JSON Pointer must start with /: ${pointer}`);
  }
  const parts = String(pointer).split("/").slice(1).map(unescapePointer);
  let current = doc;
  for (const part of parts) {
    if (current == null || typeof current !== "object") {
      return undefined;
    }
    current = current[part];
  }
  return current;
}

function unescapePointer(segment) {
  return segment.replace(/~1/g, "/").replace(/~0/g, "~");
}

export function matches(record, format) {
  const when = format.when;
  if (!when || !when.pointer || !Array.isArray(when.in)) {
    return false;
  }
  const value = getPointer(record, when.pointer);
  if (value == null) {
    return false;
  }
  return when.in.includes(String(value).trim());
}

export function selectFormat(record, formats) {
  const hits = formats.filter((format) => matches(record, format));
  return hits;
}

export function applyFormat(record, format, now = () => new Date().toISOString()) {
  const event = {};
  const fields = format.fields || {};
  for (const field of CANONICAL_FIELDS) {
    const pointer = fields[field];
    if (!pointer) {
      continue;
    }
    let value = getPointer(record, pointer);
    if (value == null) {
      continue;
    }
    if (field === "rawPayload" && typeof value !== "string") {
      value = JSON.stringify(value);
    } else if (typeof value !== "string") {
      value = String(value);
    }
    if (field === "eventType") {
      const mapped = format.eventTypeMap?.[value];
      value = (mapped == null ? value : mapped).trim().toLowerCase();
    }
    if (value === "") {
      continue;
    }
    event[field] = value;
  }
  if (!event.receivedAt) {
    event.receivedAt = now();
  }
  return event;
}

export function validateEvent(event) {
  const errors = [];
  if (!event.repoUrl || !String(event.repoUrl).trim()) {
    errors.push("missing repoUrl");
  }
  const eventType = event.eventType == null ? "" : String(event.eventType).trim();
  if (!hubTypes.has(eventType)) {
    errors.push(
      `eventType '${eventType}' is not processed (expected ${HUB_EVENT_TYPES.join(", ")})`
    );
  }
  if (metadataTypes.has(eventType) && (!event.rawPayload || !String(event.rawPayload).trim())) {
    errors.push(`metadata event ${eventType} has empty rawPayload`);
  }
  return errors;
}

export function loadFormats(dir) {
  if (!dir) {
    throw new Error("formats directory is required when KAFKA_EVENT_FORMAT=enriched");
  }
  if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) {
    throw new Error(`formats directory not found: ${dir}`);
  }
  const names = fs
    .readdirSync(dir)
    .filter((name) => name.endsWith(".json") && !name.startsWith("."))
    .sort();
  if (names.length === 0) {
    throw new Error(`no mapping files in ${dir}`);
  }
  return names.map((name) => {
    const file = path.join(dir, name);
    const format = JSON.parse(fs.readFileSync(file, "utf8"));
    if (!format.id) {
      throw new Error(`${name} is missing id`);
    }
    if (!format.when?.pointer || !Array.isArray(format.when.in) || format.when.in.length === 0) {
      throw new Error(`${name} needs when.pointer and a non-empty when.in list`);
    }
    if (!format.fields || typeof format.fields !== "object") {
      throw new Error(`${name} needs a fields object of JSON Pointers`);
    }
    return format;
  });
}

export function loadSamples(dir) {
  if (!dir) {
    throw new Error("samples directory is required for check");
  }
  if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) {
    throw new Error(`samples directory not found: ${dir}`);
  }
  const names = fs
    .readdirSync(dir)
    .filter((name) => name.endsWith(".json") && !name.startsWith("."))
    .sort();
  if (names.length === 0) {
    throw new Error(`no sample files in ${dir}`);
  }
  return names.map((name) => ({
    name,
    record: JSON.parse(fs.readFileSync(path.join(dir, name), "utf8")),
  }));
}

/**
 * @returns {{ ok: boolean, lines: string[], failures: string[] }}
 */
export function checkSamples(samples, formats) {
  const lines = [];
  const failures = [];
  for (const sample of samples) {
    let hits;
    try {
      hits = selectFormat(sample.record, formats);
    } catch (err) {
      failures.push(`${sample.name}: ${err.message}`);
      continue;
    }
    if (hits.length === 0) {
      failures.push(`${sample.name}: no format matched`);
      continue;
    }
    if (hits.length > 1) {
      failures.push(
        `${sample.name}: matched ${hits.map((hit) => hit.id).join(", ")} (a sample must match one format)`
      );
      continue;
    }
    const event = applyFormat(sample.record, hits[0]);
    const errors = validateEvent(event);
    if (errors.length > 0) {
      failures.push(`${sample.name} via ${hits[0].id}: ${errors.join("; ")}`);
      continue;
    }
    lines.push(`# ${sample.name} -> ${hits[0].id}`);
    lines.push(JSON.stringify(event, null, 2));
  }
  return { ok: failures.length === 0, lines, failures };
}
