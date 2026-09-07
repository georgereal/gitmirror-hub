# Security notes

GitMirror Hub is a **localhost operator console**. There is no login. Treat it like a database admin UI:

- Bind only to loopback (`localhost` / `127.0.0.1`). Do not expose port `8080` on a public interface.
- Anyone who can reach the API can manage pairs, purge queues, redrive the DLQ, run simulation/chaos toggles, and read/write stored SCM credentials (ciphertext still requires `GIT_UTILITY_ENCRYPTION_KEY` to decrypt at rest).
- The H2 console is **off** unless `GIT_H2_CONSOLE_ENABLED=true`.
- CORS defaults to localhost origin patterns. Override with `GIT_CORS_ALLOWED_ORIGIN_PATTERNS` only if you know the risk.
- Set a unique `GIT_UTILITY_ENCRYPTION_KEY` (32+ characters). Boot fails if it is missing or equal to the forbidden lab default string.

## Secrets & rotation

Webhook HMAC secrets, CloudAMQP passwords, GitHub App PEMs, and PATs must never be committed. Configure them via environment variables and `wrangler secret put`.

If this repository was ever private with real broker or webhook values in docs/history, **rotate those secrets** before opening the repo (or after any accidental leak). Removing files from `HEAD` does not remove them from older commits.

## Reporting

Please open a private security advisory or contact the maintainers if you find a vulnerability. Do not file public issues that include live credentials.
