# SCM Provider Setup Guide

Operator guide for creating and wiring **source-control identities** (GitHub Apps, PATs, and later GitLab / Bitbucket / etc.) into GitMirror Hub.

Hub runtime setup, queues, and multi-pod ops remain in [`INSTRUCTIONS.md`](INSTRUCTIONS.md). Edge webhook Worker details: [`webhook-worker/README.md`](webhook-worker/README.md). Security notes: [`SECURITY.md`](SECURITY.md).

This document will grow **one section per provider**. Start here for GitHub App creation on github.com.

---

## Contents

1. [GitHub Cloud — GitHub App](#1-github-cloud--github-app)
2. [GitHub Enterprise Server (GHES) — GitHub App](#2-github-enterprise-server-ghes--github-app) *(outline)*
3. [GitHub — Personal Access Token (PAT)](#3-github--personal-access-token-pat) *(short)*
4. [GitLab](#4-gitlab) *(planned)*
5. [Bitbucket Cloud](#5-bitbucket-cloud) *(planned)*
6. [Cursor Origin / Azure DevOps / generic Git](#6-cursor-origin--azure-devops--generic-git) *(planned)*

---

## 1. GitHub Cloud — GitHub App

Preferred for org-scale mirroring: short-lived installation tokens, install-scoped access, and one webhook for many repos.

### 1.1 Create the App

1. Sign in to GitHub as a user who can manage Apps for the target org (or your user account for personal installs).
2. Open **Settings → Developer settings → GitHub Apps → New GitHub App**  
   - Org path: `https://github.com/organizations/<ORG>/settings/apps` → **New GitHub App**  
   - User path: `https://github.com/settings/apps` → **New GitHub App**
3. Fill the registration form:

| Field | Recommended value |
| :--- | :--- |
| **GitHub App name** | Unique name, e.g. `gitmirror-hub-<env>` |
| **Homepage URL** | Your Hub UI or internal docs URL (required by GitHub; not used for auth by the Hub today) |
| **Callback URL** | Optional for this Hub flow. Leave blank or set a placeholder if GitHub requires one; the Hub uses **App ID + private key + installation ID**, not end-user OAuth login |
| **Setup URL** | Optional |
| **Webhook → Active** | Checked |
| **Webhook URL** | Must be a URL **GitHub can reach** — **not** `http://localhost:…` (github.com cannot call your laptop). Use the Cloudflare Worker URL when ready, or a temporary public URL / placeholder and **edit the App later** (App → General → Webhook). After the Hub credential card exists, prefer `…/webhook/github/credential/<credentialId>` — see [§1.4](#14-webhook-url--secret). You can uncheck **Active** until the URL is real. |
| **Webhook secret** | Strong random string; **same value** you paste into the Hub credential card (and Worker secrets if used). Generate with:<br>• **macOS/Linux:** `openssl rand -hex 32`<br>• **Windows PowerShell:** `[Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLower()`<br>• **Windows cmd:** `openssl rand -hex 32` (Git for Windows) or `powershell -NoProfile -Command "[Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLower()"`<br>Full notes: [§1.4](#14-webhook-url--secret). |
| **SSL verification** | Enable (for `https://` Worker / production URLs) |
| **Where can this GitHub App be installed?** | **Only on this account** or **Any account**, per your org policy |

4. Click **Create GitHub App**.

### 1.2 Repository permissions

Under **Permissions → Repository permissions**, set at least:

| Permission | Access | Why |
| :--- | :--- | :--- |
| **Contents** | **Read and write** | Clone / fetch / push refs (mirror traffic). *Subscribe to Push alone does not grant Git write.* |
| **Metadata** | **Read-only** | Required for repo discovery |
| **Pull requests** | **Read and write** | PR / review mirroring (if used) |
| **Commit statuses** | **Read and write** | Status mirroring (if used) |
| **Actions** | **Read and write** | Cancel mirror-triggered workflow runs (Hub “Suppress mirror-triggered Actions”) |
| **Workflows** | **Read and write** | Required if the tree contains `.github/workflows`; otherwise pushes can fail with `REJECTED_OTHER_REASON` |

Leave other permissions at **No access** unless you need them.

After changing permissions on an existing App, GitHub prompts you to **review / accept** the new permissions on each installation.

### 1.3 Subscribe to events

Under **Subscribe to events**, enable:

| Event | Required? |
| :--- | :--- |
| **Push** | Yes — incremental mirror triggers |
| **Pull request** | If you sync PR state via webhooks |
| **Status** / check-related events | If you mirror CI status |

Save changes.

### 1.4 Webhook URL & secret

**Webhook URL** is where GitHub **POSTs events** (push, pull_request, …) after something happens on an installed repo. It must be reachable from the public internet (or your GHES network) and point at **your** ingestion path — usually the Cloudflare Worker, or the Hub API in a lab with a tunnel. It is **not** the Hub UI URL and **not** the git clone URL.

Flow: `GitHub → (Webhook URL) → Worker or Hub → queue → sync job`.

Prefer **per-credential** URLs from Hub **Settings → Providers** (copy from the credential card after save):

| Target | Example |
| :--- | :--- |
| Cloudflare Worker (recommended) | `https://gitmirror-webhook-worker.<you>.workers.dev/webhook/github/credential/<credentialId>` |
| Direct Hub (dev / tunneled) | `https://<public-host>/api/v1/webhooks/github/credential/<credentialId>` |
| Legacy / generic Worker path | `https://…/webhook/github` (see Worker README) |

`http://localhost:8080/...` only works if GitHub can reach that host (it cannot from github.com without a tunnel such as ngrok/cloudflared).

**Webhook secret** is a shared HMAC key: GitHub signs each delivery; the Worker/Hub verify with the same string. Generate one yourself, paste it into the App form **and** the Hub credential card (and Worker `WEBHOOK_SECRETS_JSON` / `WEBHOOK_SECRET` if edge verification is on).

#### Generate a webhook secret

**macOS / Linux (bash/zsh):**

```bash
openssl rand -hex 32
# or:
# python3 -c "import secrets; print(secrets.token_hex(32))"
```

**Windows PowerShell:**

```powershell
# 32 random bytes as hex (64 characters) — preferred
[Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLower()

# Alternative if OpenSSL is on PATH (Git for Windows often includes it):
openssl rand -hex 32
```

**Windows Command Prompt (cmd.exe)** — OpenSSL from Git for Windows, or PowerShell one-liner:

```bat
openssl rand -hex 32
```

```bat
powershell -NoProfile -Command "[Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLower()"
```

Copy the printed string into:

1. GitHub App → **Webhook secret**
2. Hub → credential → **Webhook secret**
3. Worker secrets (if used)

- You may create the App with a temporary webhook URL, then edit the App once the Hub card exists and you have a stable `credentialId`.
- Keep the secret out of git; treat it like a password.
### 1.5 Generate a private key

1. On the App page → **General** → **Private keys** → **Generate a private key**.
2. Download the `.pem` file. Store it securely; GitHub will not show it again.
3. You will paste the PEM contents into the Hub credential form (`privateKeyPem`).

Also note from the App page (Hub fields):

| GitHub UI label | Hub field |
| :--- | :--- |
| **App ID** | `appId` |
| **Client ID** | `clientId` (optional / for some flows) |
| **Client secret** | Only if your Hub UI asks for it (OAuth-related; not required for pure installation-token mirroring) |
| Private key PEM | `privateKeyPem` |
| Webhook secret | `webhookSecret` |

### 1.6 Install the App

1. On the App page → **Install App** (or **Install** from the left nav).
2. Choose the **user or organization**, then **Only select repositories** (or all repos, per policy).
3. Select the repos the Hub may mirror (at least destinations you will push to; sources if they are private and App-authenticated).
4. After install, open the installation URL or **Settings → Applications → Installed GitHub Apps → Configure**.
5. Copy the **Installation ID** from the URL:  
   `https://github.com/settings/installations/<INSTALLATION_ID>`  
   or  
   `https://github.com/organizations/<ORG>/settings/installations/<INSTALLATION_ID>`  
   → Hub field `installationId`.

Public third-party sources often need **no** App install on the source (anonymous HTTPS). Destinations that receive pushes **do** need the App installed with Contents write.

### 1.7 Register the credential in GitMirror Hub

1. Open Hub UI → **Settings → Providers** (credentials / GitHub card).
2. Auth mode: **GitHub App**.
3. Paste **App ID**, **Installation ID**, **private key PEM**, **webhook secret** (and Client ID if prompted).
4. Save. Copy the **per-credential webhook URL** back into the GitHub App webhook settings if you deferred that step.
5. Use **Check Access** on a pair that binds this credential before relying on Sync / webhooks.

Encryption at rest uses `GIT_UTILITY_ENCRYPTION_KEY` — see [`SECURITY.md`](SECURITY.md). Never commit PEMs or webhook secrets.

### 1.8 Actions suppression (mirror pushes)

App/PAT pushes **do** trigger GitHub Actions (unlike workflow `GITHUB_TOKEN`). With **System Engine → Suppress mirror-triggered Actions** enabled:

- Destination write auth should be a **GitHub App** (not a PAT) so the Hub can cancel runs for `{app-slug}[bot]`.
- Optional hard prevention on GitHub.com / Enterprise Cloud: **Actions → Policies → Workflow execution protections** — keep humans on the allow-list, **exclude** the mirror App.

Details: [`INSTRUCTIONS.md`](INSTRUCTIONS.md) § “Preventing Actions from running on mirror sync”.

### 1.9 Checklist

- [ ] App created with Contents R/W (+ Metadata, Actions, Workflows as needed)
- [ ] Events: Push (+ PR / status if used)
- [ ] Private key generated and stored only in Hub (encrypted)
- [ ] App installed on destination org/repos; Installation ID recorded
- [ ] Webhook URL + secret match Hub credential (+ Worker)
- [ ] Hub credential card saved; Check Access OK

---

## 2. GitHub Enterprise Server (GHES) — GitHub App

*(Outline — expand with host-specific screenshots / URLs later.)*

Same App model as Cloud, but:

1. Create the App on the **GHES** host: `https://<ghes-host>/settings/apps` (user) or org developer settings on that host.
2. Hub credential card must store the **GHES base URL** on that card.
3. Webhook path uses the GHES Worker/Hub routes: `/webhook/ghes/credential/<id>` (or `/api/v1/webhooks/ghes/credential/<id>`).
4. Permissions and events match [§1.2](#12-repository-permissions)–[§1.3](#13-subscribe-to-events). Workflow execution protections may be unavailable on older GHES — rely on the Hub cancel sweeper.

---

## 3. GitHub — Personal Access Token (PAT)

Fine-grained PATs work for smaller setups; App is preferred for Actions suppression and install scoping.

1. **Settings → Developer settings → Personal access tokens → Fine-grained tokens**.
2. Repository access: select the repos to mirror.
3. Permissions: **Contents: Read and write** (plus PR/metadata as needed).
4. Paste the token into a Hub credential card with auth mode **PAT**.
5. Repository webhooks (not App webhooks) can point at Worker `/webhook/github/...` with a matching secret — see [`INSTRUCTIONS.md`](INSTRUCTIONS.md) Step 3 Option B.

---

## 4. GitLab

*(Planned — group/project access tokens, webhook URL `/webhook/gitlab`, secret header.)*

---

## 5. Bitbucket Cloud

*(Planned — app passwords / workspace tokens, webhook `/webhook/bitbucket`, `BITBUCKET_WEBHOOK_SECRET`.)*

---

## 6. Cursor Origin / Azure DevOps / generic Git

*(Planned — PAT or SSH as supported by Hub credential types; webhooks only where the platform supports them.)*

---

## Related docs

| Doc | Topic |
| :--- | :--- |
| [`INSTRUCTIONS.md`](INSTRUCTIONS.md) | Pair creation, webhook Step 3, failure recipes |
| [`webhook-worker/README.md`](webhook-worker/README.md) | Edge HMAC secrets and deploy |
| [`ENTERPRISE_GITHUB_MIRRORING_DESIGN.md`](ENTERPRISE_GITHUB_MIRRORING_DESIGN.md) | §10.5 permission checklist, Actions / Dependabot side effects |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Credential model (`scm_credentials`, install tokens) |
