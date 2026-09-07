package com.gitutility.service;

import com.gitutility.model.entity.SyncJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebSocketNotificationService {

    private final SimpMessagingTemplate messagingTemplate;

    public void notifyJobUpdated(SyncJob job) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "JOB_UPDATE");
            payload.put("job", job);
            messagingTemplate.convertAndSend("/topic/sync-events", payload);
        } catch (Exception e) {
            log.warn("Failed to broadcast job update over WebSocket: {}", e.getMessage());
        }
    }

    /**
     * Pipeline stage change for the Observability UI. Uses the stage label as the progress
     * message so the badge shows "Git LFS" / "PR metadata" instead of the raw id.
     */
    public void notifyPipeline(Long jobId, Long mappingId, String stageId, String stageLabel,
                               Map<String, Object> pipeline, Map<String, Object> providerTraffic) {
        String id = stageId != null && !stageId.isBlank() ? stageId : "pipeline";
        String label = stageLabel != null && !stageLabel.isBlank() ? stageLabel : id;
        notifyJobProgress(jobId, mappingId, "pipeline", id, 0, 0, label,
                null, null, null, null, null, pipeline, providerTraffic);
    }

    /**
     * Throttled live fetch/push ticks for the Observability UI. Not persisted as audit rows.
     */
    public void notifyJobProgress(Long jobId, Long mappingId, String operation, String phase,
                                  int current, int total, String message) {
        notifyJobProgress(jobId, mappingId, operation, phase, current, total, message,
                null, null, null, null, null, null, null);
    }

    public void notifyJobProgress(Long jobId, Long mappingId, String operation, String phase,
                                  int current, int total, String message,
                                  Long etaMs, Long elapsedMs,
                                  String remoteRole, String remoteLabel,
                                  Map<String, Object> pipeline,
                                  Map<String, Object> providerTraffic) {
        notifyJobProgress(jobId, mappingId, operation, phase, current, total, message,
                etaMs, elapsedMs, null, remoteRole, remoteLabel, pipeline, providerTraffic);
    }

    public void notifyJobProgress(Long jobId, Long mappingId, String operation, String phase,
                                  int current, int total, String message,
                                  Long etaMs, Long elapsedMs, Long wallElapsedMs,
                                  String remoteRole, String remoteLabel,
                                  Map<String, Object> pipeline,
                                  Map<String, Object> providerTraffic) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "JOB_PROGRESS");
            payload.put("jobId", jobId);
            payload.put("mappingId", mappingId);
            payload.put("operation", operation);
            payload.put("phase", phase);
            payload.put("current", current);
            payload.put("total", total);
            int percent = total > 0 ? (int) Math.min(100, Math.round(100.0 * current / total)) : 0;
            payload.put("percent", percent);
            payload.put("message", message);
            if (etaMs != null) {
                payload.put("etaMs", etaMs);
            }
            if (elapsedMs != null) {
                payload.put("elapsedMs", elapsedMs);
            }
            if (wallElapsedMs != null) {
                payload.put("wallElapsedMs", wallElapsedMs);
            }
            if (remoteRole != null) {
                payload.put("remoteRole", remoteRole);
            }
            if (remoteLabel != null) {
                payload.put("remoteLabel", remoteLabel);
            }
            if (pipeline != null) {
                payload.put("pipeline", pipeline);
            }
            if (providerTraffic != null) {
                payload.put("providerTraffic", providerTraffic);
            }
            messagingTemplate.convertAndSend("/topic/sync-events", payload);
        } catch (Exception e) {
            log.debug("Failed to broadcast job progress over WebSocket: {}", e.getMessage());
        }
    }

    public void notifyQueueUpdated(Map<String, Object> queueStats) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "QUEUE_UPDATE");
            payload.put("stats", queueStats);
            messagingTemplate.convertAndSend("/topic/sync-events", payload);
        } catch (Exception e) {
            log.warn("Failed to broadcast queue update over WebSocket: {}", e.getMessage());
        }
    }

    public void notifySimulationUpdated(Map<String, Object> simulationState) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "SIMULATION_UPDATE");
            payload.put("simulation", simulationState);
            messagingTemplate.convertAndSend("/topic/sync-events", payload);
        } catch (Exception e) {
            log.warn("Failed to broadcast simulation update over WebSocket: {}", e.getMessage());
        }
    }

    public void notifyDiffProgress(Long mappingId, String phase, int current, int total, String message,
                                   Map<String, Object> pipeline, Map<String, Object> counts, boolean complete) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", complete ? "DIFF_COMPLETE" : "DIFF_PROGRESS");
            payload.put("mappingId", mappingId);
            payload.put("operation", "diff");
            payload.put("phase", phase);
            payload.put("current", current);
            payload.put("total", total);
            int percent = total > 0 ? (int) Math.min(100, Math.round(100.0 * current / total)) : 0;
            payload.put("percent", percent);
            payload.put("message", message);
            if (pipeline != null) {
                payload.put("pipeline", pipeline);
            }
            if (counts != null) {
                payload.put("counts", counts);
            }
            messagingTemplate.convertAndSend("/topic/sync-events", payload);
        } catch (Exception e) {
            log.debug("Failed to broadcast diff progress over WebSocket: {}", e.getMessage());
        }
    }

    public void notifyUnmappedWebhookReceived(com.gitutility.model.entity.UnmappedWebhookEvent event) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "UNMAPPED_WEBHOOK_EVENT");
            payload.put("event", event);
            messagingTemplate.convertAndSend("/topic/sync-events", payload);
        } catch (Exception e) {
            log.warn("Failed to broadcast unmapped webhook event over WebSocket: {}", e.getMessage());
        }
    }
}
