/**
 * Cloudflare Worker that accepts SCM webhooks and produces one normalized git event
 * to Kafka over the Confluent REST records API. It does not run inside Hub.
 * The RabbitMQ gateway stays in ../webhook-worker.
 */
export interface Env {
  ENABLED?: string;
  KAFKA_REST_ENDPOINT: string;
  KAFKA_CLUSTER_ID: string;
  KAFKA_TOPIC?: string;
  KAFKA_API_KEY?: string;
  KAFKA_API_SECRET?: string;
  WEBHOOK_SECRET?: string;
  WEBHOOK_SECRETS_JSON?: string;
  BITBUCKET_WEBHOOK_SECRET?: string;
}

export interface IncrementalGitEvent {
  provider: string;
  repoUrl: string;
  ref: string;
  beforeSha: string;
  afterSha: string;
  deliveryId: string;
  eventType: "push" | "create" | "delete" | "pull_request" | "release" | "status" | "check_run";
  rawPayload?: string;
  receivedAt: string;
}

const ZERO_SHA = "0000000000000000000000000000000000000000";

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const pathname = url.pathname;
    const method = request.method;

    if (method === "GET" && (pathname === "/" || pathname === "/health")) {
      return json(200, {
        status: enabled(env) ? "healthy" : "stopped",
        service: "gitmirror-webhook-worker-kafka",
        enabled: enabled(env),
        topic: env.KAFKA_TOPIC || "git.sync.incremental",
        kafkaConfigured: !!(env.KAFKA_REST_ENDPOINT && env.KAFKA_CLUSTER_ID && env.KAFKA_API_KEY),
        timestamp: new Date().toISOString(),
      });
    }

    if (method !== "POST") {
      return json(405, { error: "Method not allowed" });
    }

    if (!enabled(env)) {
      return json(503, {
        status: "stopped",
        message: "Kafka webhook worker is disabled. Set ENABLED=true and deploy to start.",
      });
    }

    try {
      if (pathname.startsWith("/webhook/github") || pathname.startsWith("/api/v1/webhooks/github")) {
        return await handleGitHub(request, env, pathname);
      }
      if (pathname.startsWith("/webhook/ghes") || pathname.startsWith("/api/v1/webhooks/ghes")) {
        return await handleGitHub(request, env, pathname);
      }
      if (pathname.startsWith("/webhook/gitlab") || pathname.startsWith("/api/v1/webhooks/gitlab")) {
        return await handleGitLab(request, env);
      }
      if (pathname.startsWith("/webhook/bitbucket") || pathname.startsWith("/api/v1/webhooks/bitbucket")) {
        return await handleBitbucket(request, env);
      }
      return json(404, { error: "Route not found", path: pathname });
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err);
      return json(500, { error: "Internal Server Error", message });
    }
  },
};

async function handleGitHub(request: Request, env: Env, pathname: string): Promise<Response> {
  const eventType = request.headers.get("x-github-event") || "push";
  const deliveryId = request.headers.get("x-github-delivery") || crypto.randomUUID();
  const signature256 = request.headers.get("x-hub-signature-256");

  if (eventType === "ping") {
    return json(200, { status: "pong" });
  }
  const accepted = new Set(["push", "create", "delete", "pull_request", "release", "status", "check_run"]);
  if (!accepted.has(eventType)) {
    return json(200, { status: "ignored", reason: `Event type '${eventType}' is not an incremental git event` });
  }

  const rawPayload = await request.text();
  const credentialId = credentialFromPath(pathname);
  const secret = resolveWebhookSecret(env, credentialId);
  if (secret) {
    const valid = await verifyHmacSha256(rawPayload, secret, signature256);
    if (!valid) {
      return json(401, { error: "Invalid HMAC signature" });
    }
  }

  const payload = JSON.parse(rawPayload) as {
    ref?: string;
    ref_type?: string;
    before?: string;
    after?: string;
    deleted?: boolean;
    action?: string;
    repository?: { clone_url?: string; html_url?: string };
    pull_request?: { head?: { ref?: string; sha?: string } };
    release?: { tag_name?: string };
    check_run?: { head_sha?: string };
    sha?: string;
  };
  const repoUrl = payload.repository?.clone_url || payload.repository?.html_url || "";
  if (!repoUrl) {
    return json(400, { error: "Webhook payload has no repository URL" });
  }
  if (eventType === "pull_request" || eventType === "release" || eventType === "status" || eventType === "check_run") {
    const event = gitEvent({
      provider: "github",
      repoUrl,
      ref: metadataRef(eventType, payload),
      beforeSha: ZERO_SHA,
      afterSha: payload.check_run?.head_sha || payload.sha || payload.pull_request?.head?.sha || ZERO_SHA,
      deliveryId,
      eventType,
      rawPayload,
    });
    await publishToKafka(event, env);
    return json(202, { status: "produced", provider: "github", deliveryId, repoUrl: event.repoUrl, eventType });
  }
  const deleted = eventType === "delete" || payload.deleted === true;
  const ref = eventType === "push" ? (payload.ref || "") : fullRef(payload.ref, payload.ref_type);
  const afterSha = deleted ? ZERO_SHA : (payload.after || "");
  const event = gitEvent({
    provider: "github",
    repoUrl,
    ref,
    beforeSha: payload.before || ZERO_SHA,
    afterSha,
    deliveryId,
    eventType: deleted ? "delete" : eventType === "create" ? "create" : "push",
  });
  await publishToKafka(event, env);
  return json(202, { status: "produced", provider: "github", deliveryId, repoUrl: event.repoUrl });
}

async function handleGitLab(request: Request, env: Env): Promise<Response> {
  const token = request.headers.get("x-gitlab-token");
  if (env.WEBHOOK_SECRET && token !== env.WEBHOOK_SECRET) {
    return json(401, { error: "Unauthorized token" });
  }
  const rawPayload = await request.text();
  const payload = JSON.parse(rawPayload) as {
    ref?: string;
    before?: string;
    after?: string;
    checkout_sha?: string | null;
    project?: { git_http_url?: string; http_url?: string; web_url?: string };
  };
  const repoUrl = payload.project?.git_http_url || payload.project?.http_url || payload.project?.web_url || "";
  if (!repoUrl) {
    return json(400, { error: "GitLab payload has no project URL" });
  }
  const afterSha = payload.after || payload.checkout_sha || ZERO_SHA;
  const deliveryId = crypto.randomUUID();
  const event = gitEvent({
    provider: "gitlab",
    repoUrl,
    ref: payload.ref || "",
    beforeSha: payload.before || ZERO_SHA,
    afterSha,
    deliveryId,
    eventType: afterSha === ZERO_SHA ? "delete" : "push",
  });
  await publishToKafka(event, env);
  return json(202, { status: "produced", provider: "gitlab", deliveryId });
}

async function handleBitbucket(request: Request, env: Env): Promise<Response> {
  const eventKey = request.headers.get("x-event-key") || "repo:push";
  const deliveryId = request.headers.get("x-request-uuid") || crypto.randomUUID();
  const signature = request.headers.get("x-hub-signature") || request.headers.get("x-hub-signature-256");
  const rawPayload = await request.text();
  const secret = env.BITBUCKET_WEBHOOK_SECRET || env.WEBHOOK_SECRET;
  if (secret && signature) {
    const valid = await verifyHmacSha256(rawPayload, secret, signature);
    if (!valid) {
      return json(401, { error: "Invalid Bitbucket HMAC signature" });
    }
  }
  if (!eventKey.includes("push")) {
    return json(200, { status: "ignored", reason: `Event '${eventKey}' is not an incremental git event` });
  }
  const payload = JSON.parse(rawPayload) as {
    repository?: { links?: { html?: { href?: string } } };
    push?: { changes?: Array<{ new?: { name?: string; target?: { hash?: string } } | null; old?: { name?: string; target?: { hash?: string } } | null }> };
  };
  const repoUrl = payload.repository?.links?.html?.href || "";
  const change = payload.push?.changes?.[0];
  const refName = change?.new?.name || change?.old?.name || "";
  const afterSha = change?.new?.target?.hash || ZERO_SHA;
  const beforeSha = change?.old?.target?.hash || ZERO_SHA;
  if (!repoUrl) {
    return json(400, { error: "Bitbucket payload has no repository URL" });
  }
  const event = gitEvent({
    provider: "bitbucket",
    repoUrl,
    ref: refName.startsWith("refs/") ? refName : `refs/heads/${refName}`,
    beforeSha,
    afterSha,
    deliveryId,
    eventType: change?.new == null ? "delete" : "push",
  });
  await publishToKafka(event, env);
  return json(202, { status: "produced", provider: "bitbucket", deliveryId });
}

async function publishToKafka(event: IncrementalGitEvent, env: Env): Promise<void> {
  const endpoint = (env.KAFKA_REST_ENDPOINT || "").replace(/\/$/, "");
  const clusterId = env.KAFKA_CLUSTER_ID || "";
  const topic = env.KAFKA_TOPIC || "git.sync.incremental";
  if (!endpoint || !clusterId || !env.KAFKA_API_KEY || !env.KAFKA_API_SECRET) {
    throw new Error("Kafka REST is not configured (endpoint, cluster id, API key, API secret)");
  }
  const produceUrl =
    `${endpoint}/kafka/v3/clusters/${encodeURIComponent(clusterId)}/topics/${encodeURIComponent(topic)}/records`;
  const response = await fetch(produceUrl, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: "Basic " + btoa(`${env.KAFKA_API_KEY}:${env.KAFKA_API_SECRET}`),
    },
    body: JSON.stringify({
      key: { type: "STRING", data: repoKey(event.repoUrl) },
      value: { type: "JSON", data: event },
    }),
  });
  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`Kafka REST rejected produce (${response.status}): ${errorText}`);
  }
}

function fullRef(ref: string | undefined, refType: string | undefined): string {
  if (!ref) {
    return "";
  }
  if (ref.startsWith("refs/")) {
    return ref;
  }
  return refType === "tag" ? `refs/tags/${ref}` : `refs/heads/${ref}`;
}

function metadataRef(eventType: string, payload: {
  pull_request?: { head?: { ref?: string } };
  release?: { tag_name?: string };
  sha?: string;
  check_run?: { head_sha?: string };
}): string {
  if (eventType === "pull_request") {
    const head = payload.pull_request?.head?.ref;
    return head ? `refs/heads/${head}` : "";
  }
  if (eventType === "release" && payload.release?.tag_name) {
    return `refs/tags/${payload.release.tag_name}`;
  }
  return payload.check_run?.head_sha || payload.sha || "";
}

function gitEvent(event: IncrementalGitEvent): IncrementalGitEvent {
  return { ...event, receivedAt: new Date().toISOString() };
}

function enabled(env: Env): boolean {
  return (env.ENABLED || "true").trim().toLowerCase() !== "false";
}

function credentialFromPath(pathname: string): string | null {
  const parts = pathname.split("/").filter(Boolean);
  const credIdx = parts.indexOf("credential");
  const id = credIdx >= 0 ? parts[credIdx + 1] : undefined;
  return id && /^\d+$/.test(id) ? id : null;
}

function repoKey(url: string): string {
  let s = url.trim().toLowerCase().replace(/\/+$/, "").replace(/\.git$/, "");
  s = s.replace(/^(https?|ssh|git):\/\//, "");
  s = s.replace(/^git@([^:]+):/, "$1/");
  s = s.replace(/^[^@/]+@/, "");
  return s;
}

function resolveWebhookSecret(env: Env, credentialId: string | null): string | undefined {
  if (credentialId && env.WEBHOOK_SECRETS_JSON) {
    try {
      const map = JSON.parse(env.WEBHOOK_SECRETS_JSON) as Record<string, string>;
      if (map[credentialId]) {
        return map[credentialId];
      }
    } catch {
      // fall through
    }
  }
  return env.WEBHOOK_SECRET;
}

async function verifyHmacSha256(payload: string, secret: string, signatureHeader: string | null): Promise<boolean> {
  if (!signatureHeader) {
    return false;
  }
  const expected = signatureHeader.startsWith("sha256=")
    ? signatureHeader.slice("sha256=".length)
    : signatureHeader;
  const encoder = new TextEncoder();
  const cryptoKey = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const signatureBuffer = await crypto.subtle.sign("HMAC", cryptoKey, encoder.encode(payload));
  const hex = Array.from(new Uint8Array(signatureBuffer))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
  return hex.toLowerCase() === expected.toLowerCase();
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
