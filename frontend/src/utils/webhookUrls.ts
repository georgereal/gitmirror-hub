/** Cloudflare Worker public origin. Paste this into GitHub App → Webhook URL. */
export const EDGE_WEBHOOK_BASE = 'https://gitmirror-webhook-worker.<your-username>.workers.dev';

/** Spring Boot when you skip the Worker (GitHub.com cannot reach localhost). */
export const HUB_WEBHOOK_ORIGIN = 'http://localhost:8080';

export type WebhookKind = 'github' | 'ghes' | 'bitbucket' | 'gitlab' | 'origin';

/** App-level ingest URLs — one per provider, not per installation card. */
export function appWebhookUrls(kind: WebhookKind) {
  const edgePath = `/webhook/${kind}`;
  const hubPath = kind === 'ghes' ? '/api/v1/webhooks/ghes' : `/api/v1/webhooks/${kind === 'origin' ? 'github' : kind}`;
  return {
    edge: `${EDGE_WEBHOOK_BASE}${edgePath}`,
    hub: `${HUB_WEBHOOK_ORIGIN}${hubPath}`,
  };
}
