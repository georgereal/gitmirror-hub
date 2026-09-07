# AI Agent Guidelines & Repository Conventions

Welcome to the **GitMirror Hub** codebase.

## Mandatory Workflow for AI Agents

### 1. Read Documentation First Before Exploring Code
Before executing broad keyword searches or navigating files arbitrarily:
1. Read **[`ARCHITECTURE.md`](ARCHITECTURE.md)** for system design, queue topology, and data flow.
2. Read **[`REPO_MAP.md`](REPO_MAP.md)** for file locations, component responsibilities, and API routes.
3. Read **[`INSTRUCTIONS.md`](INSTRUCTIONS.md)** for operational commands, testing recipes, and environment variables.

### 2. Event-Driven Documentation Updates
Do **not** update documentation files on every intermediate edit or small code tweak.

Only synchronize **[`REPO_MAP.md`](REPO_MAP.md)**, **[`ARCHITECTURE.md`](ARCHITECTURE.md)**, and **[`INSTRUCTIONS.md`](INSTRUCTIONS.md)** on:
* **Explicit user request** (e.g. "update docs", "refresh repo map").
* **Git commits & PR milestones** (before committing changes, ensure docs reflect the new codebase state).
* **Major architectural changes** (adding a new service/worker module, changing queue bindings/exchanges, or adding SCM integrations).

### 3. Module Overview
- `backend/`: Java 21/23 + Spring Boot 3.3.3 + JGit + Spring AMQP (RabbitMQ / CloudAMQP).
- `frontend/`: React 18 + Vite + Tailwind CSS + SockJS / STOMP.
- `webhook-worker/`: TypeScript Cloudflare Worker for edge webhook ingestion.
