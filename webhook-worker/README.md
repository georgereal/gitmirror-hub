# GitMirror Hub - Serverless Webhook Worker (Cloudflare Workers)

An ultra-lightweight, high-performance, and resilient serverless webhook gateway built for **Cloudflare Workers**.

It receives incoming Git push webhooks (from **GitHub**, **GitLab**, or **Bitbucket**), verifies HMAC signatures at the edge with **0ms cold-start**, wraps the event envelope, and immediately publishes it to **RabbitMQ / CloudAMQP** via the Management HTTP API in **<15ms**, returning `HTTP 202 Accepted` to GitHub.

---

## 🌟 Why Cloudflare Workers for Webhooks?

1. **100% Free**: Up to **100,000 requests per day** on Cloudflare's free tier.
2. **0ms Cold Starts**: Runs on Cloudflare's global V8 isolate network.
3. **Guaranteed Uptime & Zero Dropped Webhooks**: Even when your backend server is restarting, upgrading, or offline, GitHub webhooks are safely captured and buffered in RabbitMQ.
4. **Instant HTTPS URL**: Cloudflare provides a secure `https://gitmirror-webhook-worker.<your-subdomain>.workers.dev` endpoint with automatic SSL.

---

## 🔑 How Wrangler Connects to Your Cloudflare Account

Wrangler uses **OAuth browser authentication** (or an API Token) to link your terminal to your Cloudflare account. You do not need to manually configure complicated credentials.

---

## 🛠️ Step-by-Step Setup, Authentication & Deployment

### Step 1: Install Dependencies

From the project root:

```bash
cd webhook-worker
npm install
```

---

### Step 2: Log in to Cloudflare with Wrangler

Authenticate your terminal session with your Cloudflare account:

```bash
npx wrangler login
```

1. Wrangler will automatically open your default browser to `dash.cloudflare.com`.
2. Sign in to your Cloudflare account (or sign up for a free account if you don't have one).
3. Click **"Allow"** to authorize Wrangler.
4. Your terminal will display:
   ```text
   Successfully logged in.
   ```

To verify the active account:
```bash
npx wrangler whoami
```
This will print your logged-in email and **Cloudflare Account ID**.

---

### Step 3: Fill in `.env` (gitignored)

`wrangler.toml` has no CloudAMQP values. Copy the example and edit the copy:

```bash
cd webhook-worker
cp .env.example .env
```

`.env` is listed in `.gitignore`. If your CloudAMQP URL is `amqps://myuser:mypassword@lemur.cloudamqp.com/myvhost`, the file is:

```text
ENABLED=true
RABBITMQ_HTTP_URL=https://lemur.cloudamqp.com
RABBITMQ_VHOST=myvhost
RABBITMQ_USER=myuser
RABBITMQ_PASSWORD=mypassword
RABBITMQ_EXCHANGE=git.sync.exchange
RABBITMQ_ROUTING_KEY=git.webhook.inbound
WEBHOOK_SECRET=<same secret as the GitHub webhook>
```

`npm run deploy` uploads every non-empty line as a Cloudflare secret. You do not run `wrangler secret put` for these.

> **Note on `WEBHOOK_SECRET`**:
> To generate a secure 32-byte hexadecimal secret:
> ```bash
> openssl rand -hex 20
> ```
> The value set in `WEBHOOK_SECRET` **must exactly match** the **Webhook Secret** configured in your **GitHub App** or **Repository Webhook Settings**.

---

### Step 4: Test Locally (Optional)

Start the local development server with Wrangler:

```bash
npm run dev
```

In a separate terminal, test the local health check:
```bash
curl http://localhost:8787/health
```

Expected JSON response:
```json
{
  "status": "healthy",
  "service": "gitmirror-webhook-worker",
  "timestamp": "2026-08-28T16:00:00.000Z",
  "rabbitmqConfigured": true
}
```

---

### Step 5: Deploy to Cloudflare

Deploy your worker to Cloudflare's global edge network:

```bash
npm run deploy
```
*(or `npx wrangler deploy`)*

Wrangler will package your TypeScript worker and output your live public URL:

```text
Total Upload: ~12 KiB / gzip: ~3.5 KiB
Uploaded gitmirror-webhook-worker (1.10 sec)
Deployed gitmirror-webhook-worker triggers (0.12 sec)
  https://gitmirror-webhook-worker.<your-workers-subdomain>.workers.dev
```

`npm run dev` is a local preview only. Ctrl+C stops that process and leaves the deployed Worker running.

**Stop the deployed Worker and keep secrets:** set `ENABLED=false` in `.env`, then `npm run deploy`. Health reports `"status": "stopped"`. POSTs return 503. Start again with `ENABLED=true` and `npm run deploy`.

**Hide the public URL** (secrets stay): set `workers_dev = false` and `npm run deploy`. Set it back to `true` and deploy to open the URL.

**Delete the Worker** (the next deploy must set secrets again): `npx wrangler delete`.

The Kafka publisher is a different script: [`webhook-worker-kafka/`](../webhook-worker-kafka/README.md).

---

### Step 6: Verify Live Deployment

Run a quick health check against your live deployed URL:

```bash
curl https://gitmirror-webhook-worker.<your-workers-subdomain>.workers.dev/health
```

---

## 📡 Configuring Webhooks in GitHub

You can configure webhooks either via a **GitHub App (Recommended for Multi-Repo / Org-Wide setups)** or via **Individual Repository Webhooks**.

---

### Option A: GitHub App Webhook Setup (Recommended)

In your GitHub Account/Organization Settings (*Developer settings > GitHub Apps > [Your App]*):

| Setting Field | Exact Configuration Value |
| :--- | :--- |
| **Webhook URL** | `https://gitmirror-webhook-worker.<your-workers-subdomain>.workers.dev/webhook/github` |
| **Webhook Secret** | Paste the exact string configured in `WEBHOOK_SECRET` |
| **SSL Verification** | **Enable SSL verification** (Active by default on `workers.dev`) |
| **Active** | `[x] Active` (checked) |

#### Required GitHub App Permissions:
* **Repository Permissions**:
  * `Contents`: **Read & write** (allows mirroring commits, branches, and tags)
  * `Metadata`: **Read-only** (mandatory for repository inspection)
  * `Pull requests`: **Read & write** (if PR and review replication is used)
  * `Commit statuses`: **Read & write** (if CI status check mirroring is used)

#### Subscribed Events:
* `[x] Push` (Triggered on git push to a repository)
* `[x] Pull request` (Optional: if syncing PR state)

---

### Option B: Individual Repository Webhook Setup

In your repository on GitHub (*Settings > Webhooks > Add webhook*):

1. **Payload URL**:
   * For generic dynamic repo matching:
     ```
     https://gitmirror-webhook-worker.<your-workers-subdomain>.workers.dev/webhook/github
     ```
   * Or for a specific pair mapping (e.g. mapping ID `1`):
     ```
     https://gitmirror-webhook-worker.<your-workers-subdomain>.workers.dev/webhook/github/1
     ```
2. **Content type**: `application/json`
3. **Secret**: Paste the exact string configured in `WEBHOOK_SECRET`
4. **SSL verification**: `Enable SSL verification`
5. **Which events would you like to trigger this webhook?**: `Just the push event`
6. Click **Add webhook**.

---

## 🌐 Configuring Webhooks in Bitbucket Cloud

In Bitbucket Cloud, webhooks can be registered per-repository to forward push, PR, and commit status events to Cloudflare Workers with HMAC-SHA256 edge verification.

### Step 1: Open Repository Webhook Settings in Bitbucket
1. Go to your repository in Bitbucket: `https://bitbucket.org/<workspace>/<repo-name>`.
2. Scroll to the bottom of the left navigation sidebar and click **Repository settings** (⚙️ gear icon) — or navigate directly to:
   ```
   https://bitbucket.org/<workspace>/<repo-name>/admin/webhooks
   ```
3. Click **Add webhook**.

### Step 2: Configure Webhook Fields
| Setting Field | Exact Configuration Value |
| :--- | :--- |
| **Title** | `GitMirror Hub Ingestion Gateway` |
| **URL** | `https://gitmirror-webhook-worker.<your-workers-subdomain>.workers.dev/webhook/bitbucket` |
| **Status** | `[x] Active` (checked) |
| **SSL / TLS verification** | `[x] Verify SSL certs` (checked) |
| **Secret** | Enter a secure secret (e.g. generated via `openssl rand -hex 20`) |

### Step 3: Select Subscribed Trigger Events
* **Repository**:
  * `[x] Push`
* **Pull request**:
  * `[x] Created`
  * `[x] Updated`
  * `[x] Merged (Fulfilled)`
  * `[x] Declined`
  * `[x] Comment created`

### Step 4: Put the Bitbucket secret in `.env`
If you entered a secret in Bitbucket's webhook settings, add it to `webhook-worker/.env` and deploy:

```text
BITBUCKET_WEBHOOK_SECRET=<the Bitbucket webhook secret>
```

```bash
cd webhook-worker
npm run deploy
```

Bitbucket signs all webhook requests with an `X-Hub-Signature: sha256=...` header. The Worker will automatically verify the signature at the edge before enqueuing to CloudAMQP.

---

## 🔍 Live Monitoring & Diagnostics

To stream live logs and view incoming webhooks hitting your Worker in real-time:

```bash
npx wrangler tail
```

---

## 🤖 Non-Interactive / CI/CD Deployment (API Token)

If you are deploying from a headless environment (like GitHub Actions, GitLab CI, or Docker) without a browser:

1. In the Cloudflare Dashboard, navigate to **My Profile > API Tokens > Create Token**.
2. Select the **Edit Cloudflare Workers** template.
3. Export the credentials in your environment:
   ```bash
   export CLOUDFLARE_API_TOKEN="your_cloudflare_api_token"
   export CLOUDFLARE_ACCOUNT_ID="your_account_id"
   npx wrangler deploy
   ```
