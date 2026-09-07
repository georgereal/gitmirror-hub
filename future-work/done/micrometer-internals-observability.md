# Done: Micrometer + Internals observability screen

> **Status:** Complete (v1).  
> **Shipped:** Hub Internals UI at `/observability/internals` (no Prometheus required).

## Delivered

| Area | Location |
| :--- | :--- |
| Deps | `spring-boot-starter-actuator` + Micrometer |
| Executors | Metered `syncTaskExecutor`, `lfsDiscoveryExecutor`, `lfsTransferExecutor`, `prCreateExecutor` in `AsyncConfig` |
| Hub meters | `HubMetrics` — CB, pause, lane unacked, job timers, Actions cancels |
| SCM quota gauges | `ScmQuotaTracker` |
| API | `GET /api/v1/runtime-metrics` → `RuntimeMetricsController` |
| UI | `InternalsPage` + Header nav `/observability/internals` |
| Docs | Stall triage in `INSTRUCTIONS.md` |

## Optional leftovers (not blocking)

These were explicit non-goals of v1. Promote to [`../README.md`](../README.md) only if we decide to schedule them:

- Prometheus scrape export / Grafana
- Per-job Micrometer tags (avoided for cardinality)
- Replacing job-progress WebSockets / `ProviderRateMeter`
