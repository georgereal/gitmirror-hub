export interface Env {
  RABBITMQ_HTTP_URL: string;
  RABBITMQ_VHOST: string;
  RABBITMQ_USER?: string;
  RABBITMQ_PASSWORD?: string;
  RABBITMQ_EXCHANGE?: string;
  RABBITMQ_ROUTING_KEY?: string;
  WEBHOOK_SECRET?: string;
  /** JSON object of credentialId (string) -> HMAC secret. */
  WEBHOOK_SECRETS_JSON?: string;
  BITBUCKET_WEBHOOK_SECRET?: string;
}

export interface InboundWebhookEnvelope {
  provider: "github" | "gitlab" | "bitbucket" | "ghes";
  mappingId: number | null;
  eventType: string;
  deliveryId: string;
  headers: Record<string, string>;
  rawPayload: string;
  signature?: string;
  receivedAt: string;
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    const pathname = url.pathname;
    const method = request.method;

    // 1. Health check & Root info
    if (method === "GET" && (pathname === "/" || pathname === "/health")) {
      return new Response(
        JSON.stringify({
          status: "healthy",
          service: "gitmirror-webhook-worker",
          timestamp: new Date().toISOString(),
          rabbitmqConfigured: !!(env.RABBITMQ_HTTP_URL && env.RABBITMQ_USER),
        }),
        {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }
      );
    }

    // 2. Only accept POST for webhooks
    if (method !== "POST") {
      return new Response(JSON.stringify({ error: "Method not allowed" }), {
        status: 405,
        headers: { "Content-Type": "application/json" },
      });
    }

    try {
      // 3. Routing
      if (pathname.startsWith("/webhook/github") || pathname.startsWith("/api/v1/webhooks/github")) {
        return await handleGitHubWebhook(request, env, pathname);
      } else if (pathname.startsWith("/webhook/ghes") || pathname.startsWith("/api/v1/webhooks/ghes")) {
        return await handleGitHubWebhook(request, env, pathname);
      } else if (pathname.startsWith("/webhook/gitlab") || pathname.startsWith("/api/v1/webhooks/gitlab")) {
        return await handleGitLabWebhook(request, env);
      } else if (pathname.startsWith("/webhook/bitbucket") || pathname.startsWith("/api/v1/webhooks/bitbucket")) {
        return await handleBitbucketWebhook(request, env);
      } else {
        return new Response(JSON.stringify({ error: "Route not found", path: pathname }), {
          status: 404,
          headers: { "Content-Type": "application/json" },
        });
      }
    } catch (err: any) {
      return new Response(
        JSON.stringify({ error: "Internal Server Error", message: err.message || String(err) }),
        { status: 500, headers: { "Content-Type": "application/json" } }
      );
    }
  },
};

/**
 * Handles incoming GitHub Push Webhooks
 */
async function handleGitHubWebhook(request: Request, env: Env, pathname: string): Promise<Response> {
  const eventType = request.headers.get("x-github-event") || "push";
  const deliveryId = request.headers.get("x-github-delivery") || crypto.randomUUID();
  const signature256 = request.headers.get("x-hub-signature-256");

  // GitHub sends ping event upon webhook creation
  if (eventType === "ping") {
    return new Response(
      JSON.stringify({ status: "pong", message: "GitHub webhook configured successfully" }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    );
  }

  // Only push events trigger mirror synchronization
  if (eventType !== "push") {
    return new Response(
      JSON.stringify({ status: "ignored", reason: `Event type '${eventType}' is not processed` }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    );
  }

  const rawPayload = await request.text();

  const parts = pathname.split("/").filter(Boolean);
  const credIdx = parts.indexOf("credential");
  const credentialId = credIdx >= 0 && parts[credIdx + 1] && /^\d+$/.test(parts[credIdx + 1])
    ? parts[credIdx + 1]
    : null;

  const secretForPath = resolveWebhookSecret(env, credentialId);
  if (secretForPath) {
    const isValid = await verifyHmacSha256(rawPayload, secretForPath, signature256);
    if (!isValid) {
      return new Response(JSON.stringify({ error: "Invalid HMAC signature" }), {
        status: 401,
        headers: { "Content-Type": "application/json" },
      });
    }
  }

  // Mapping-specific URL: /webhook/github/{mappingId} (not /credential/{id})
  let mappingId: number | null = null;
  const lastPart = parts[parts.length - 1];
  if (!credentialId && /^\d+$/.test(lastPart)) {
    mappingId = parseInt(lastPart, 10);
  }

  // Package inbound envelope
  const envelope: InboundWebhookEnvelope = {
    provider: "github",
    mappingId,
    eventType,
    deliveryId,
    headers: extractHeaders(request.headers),
    rawPayload,
    signature: signature256 || undefined,
    receivedAt: new Date().toISOString(),
  };

  // Publish directly to RabbitMQ / CloudAMQP HTTP Management API
  await publishToRabbitMQ(envelope, env);

  return new Response(
    JSON.stringify({
      status: "enqueued",
      provider: "github",
      deliveryId,
      mappingId,
      queuedAt: envelope.receivedAt,
    }),
    {
      status: 202,
      headers: { "Content-Type": "application/json" },
    }
  );
}

/**
 * Handles incoming GitLab Push Webhooks
 */
async function handleGitLabWebhook(request: Request, env: Env): Promise<Response> {
  const eventType = request.headers.get("x-gitlab-event") || "Push Hook";
  const token = request.headers.get("x-gitlab-token");

  if (env.WEBHOOK_SECRET && token !== env.WEBHOOK_SECRET) {
    return new Response(JSON.stringify({ error: "Unauthorized token" }), {
      status: 401,
      headers: { "Content-Type": "application/json" },
    });
  }

  const rawPayload = await request.text();
  const deliveryId = crypto.randomUUID();

  const envelope: InboundWebhookEnvelope = {
    provider: "gitlab",
    mappingId: null,
    eventType: eventType.toLowerCase().includes("push") ? "push" : eventType,
    deliveryId,
    headers: extractHeaders(request.headers),
    rawPayload,
    receivedAt: new Date().toISOString(),
  };

  await publishToRabbitMQ(envelope, env);

  return new Response(
    JSON.stringify({ status: "enqueued", provider: "gitlab", deliveryId }),
    { status: 202, headers: { "Content-Type": "application/json" } }
  );
}

/**
 * Handles incoming Bitbucket Push Webhooks
 */
async function handleBitbucketWebhook(request: Request, env: Env): Promise<Response> {
  const eventType = request.headers.get("x-event-key") || "repo:push";
  const deliveryId = request.headers.get("x-request-uuid") || crypto.randomUUID();
  const signature = request.headers.get("x-hub-signature") || request.headers.get("x-hub-signature-256");
  const rawPayload = await request.text();

  // Validate HMAC signature if BITBUCKET_WEBHOOK_SECRET or WEBHOOK_SECRET is set
  const secret = env.BITBUCKET_WEBHOOK_SECRET || env.WEBHOOK_SECRET;
  if (secret && signature) {
    const isValid = await verifyHmacSha256(rawPayload, secret, signature);
    if (!isValid) {
      return new Response(JSON.stringify({ error: "Invalid Bitbucket HMAC signature" }), {
        status: 401,
        headers: { "Content-Type": "application/json" },
      });
    }
  }

  const envelope: InboundWebhookEnvelope = {
    provider: "bitbucket",
    mappingId: null,
    eventType: eventType.includes("push") ? "push" : eventType,
    deliveryId,
    headers: extractHeaders(request.headers),
    rawPayload,
    signature: signature || undefined,
    receivedAt: new Date().toISOString(),
  };

  await publishToRabbitMQ(envelope, env);

  return new Response(
    JSON.stringify({ status: "enqueued", provider: "bitbucket", deliveryId }),
    { status: 202, headers: { "Content-Type": "application/json" } }
  );
}

/**
 * Publishes envelope to RabbitMQ / CloudAMQP via RabbitMQ Management HTTP API
 */
async function publishToRabbitMQ(envelope: InboundWebhookEnvelope, env: Env): Promise<void> {
  const baseUrl = (env.RABBITMQ_HTTP_URL || "https://lemur.cloudamqp.com").replace(/\/$/, "");
  const vhost = env.RABBITMQ_VHOST || "/";
  const exchange = env.RABBITMQ_EXCHANGE || "git.sync.exchange";
  const routingKey = env.RABBITMQ_ROUTING_KEY || "git.webhook.inbound";

  // Endpoint: /api/exchanges/<vhost>/<exchange>/publish
  const publishUrl = `${baseUrl}/api/exchanges/${encodeURIComponent(vhost)}/${encodeURIComponent(exchange)}/publish`;

  const user = env.RABBITMQ_USER || "";
  const password = env.RABBITMQ_PASSWORD || "";
  const authHeader = "Basic " + btoa(`${user}:${password}`);

  const body = {
    properties: {
      delivery_mode: 2, // Persistent message
      content_type: "application/json",
      timestamp: Math.floor(Date.now() / 1000),
      message_id: envelope.deliveryId,
    },
    routing_key: routingKey,
    payload: JSON.stringify(envelope),
    payload_encoding: "string",
  };

  const response = await fetch(publishUrl, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: authHeader,
    },
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`RabbitMQ Management API rejected publish (${response.status}): ${errorText}`);
  }

  const result: any = await response.json();
  if (result.routed === false) {
    console.warn("RabbitMQ published message was not routed to any queue. Check exchange bindings.");
  }
}

function resolveWebhookSecret(env: Env, credentialId: string | null): string | undefined {
  if (credentialId && env.WEBHOOK_SECRETS_JSON) {
    try {
      const map = JSON.parse(env.WEBHOOK_SECRETS_JSON) as Record<string, string>;
      if (map[credentialId]) {
        return map[credentialId];
      }
    } catch {
      // fall through to legacy secret
    }
  }
  return env.WEBHOOK_SECRET;
}

/**
 * Validates HMAC-SHA256 signature using native Web Crypto API
 */
async function verifyHmacSha256(payload: string, secret: string, signatureHeader: string | null): Promise<boolean> {
  if (!signatureHeader) {
    return false;
  }

  const expectedSignature = signatureHeader.startsWith("sha256=")
    ? signatureHeader.slice("sha256=".length)
    : signatureHeader;
  const encoder = new TextEncoder();
  const keyData = encoder.encode(secret);

  const cryptoKey = await crypto.subtle.importKey(
    "raw",
    keyData,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );

  const signatureBuffer = await crypto.subtle.sign("HMAC", cryptoKey, encoder.encode(payload));
  const hexSignature = Array.from(new Uint8Array(signatureBuffer))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");

  return hexSignature.toLowerCase() === expectedSignature.toLowerCase();
}

/**
 * Helper to collect relevant headers into key-value map
 */
function extractHeaders(headers: Headers): Record<string, string> {
  const result: Record<string, string> = {};
  for (const [key, val] of headers.entries()) {
    if (
      key.startsWith("x-github") ||
      key.startsWith("x-gitlab") ||
      key.startsWith("x-event") ||
      key.startsWith("user-agent") ||
      key === "content-type"
    ) {
      result[key] = val;
    }
  }
  return result;
}
