package com.gitutility.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ProgressMonitor;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Throttled JGit {@link ProgressMonitor}. Phase changes (beginTask) emit immediately;
 * {@code update()} ticks are rate-limited (~400ms) so a vscode-scale clone does not flood
 * the database or the WebSocket. Ticks are never persisted as audit rows.
 */
@Slf4j
public class LiveGitProgressMonitor implements ProgressMonitor {

    public static final long THROTTLE_MS = 400;
    public static final long ETA_MIN_ELAPSED_MS = 2000;

    private final Long jobId;
    private final Long mappingId;
    private final String operation;
    private final String remoteRole;
    private final String remoteLabel;
    private final WebSocketNotificationService webSocketNotificationService;
    private final BiConsumer<String, String> onPhaseChange;
    private final Supplier<Map<String, Object>> pipelineSupplier;
    private final Supplier<Map<String, Object>> trafficSupplier;
    private final java.util.function.Supplier<Long> wallElapsedMsSupplier;
    private final Runnable onTick;

    private String currentPhase = "";
    private int totalWork;
    private int completedWork;
    private long lastEmitAtMs;
    private long phaseStartedAtMs;
    int objectsReceived;
    private volatile String lastProgressMessage = "";

    public LiveGitProgressMonitor(Long jobId,
                                  Long mappingId,
                                  String operation,
                                  WebSocketNotificationService webSocketNotificationService,
                                  BiConsumer<String, String> onPhaseChange) {
        this(jobId, mappingId, operation, null, null, webSocketNotificationService, onPhaseChange, null, null, null, null);
    }

    public LiveGitProgressMonitor(Long jobId,
                                  Long mappingId,
                                  String operation,
                                  String remoteRole,
                                  String remoteLabel,
                                  WebSocketNotificationService webSocketNotificationService,
                                  BiConsumer<String, String> onPhaseChange,
                                  Supplier<Map<String, Object>> pipelineSupplier,
                                  Supplier<Map<String, Object>> trafficSupplier) {
        this(jobId, mappingId, operation, remoteRole, remoteLabel, webSocketNotificationService,
                onPhaseChange, pipelineSupplier, trafficSupplier, null, null);
    }

    public LiveGitProgressMonitor(Long jobId,
                                  Long mappingId,
                                  String operation,
                                  String remoteRole,
                                  String remoteLabel,
                                  WebSocketNotificationService webSocketNotificationService,
                                  BiConsumer<String, String> onPhaseChange,
                                  Supplier<Map<String, Object>> pipelineSupplier,
                                  Supplier<Map<String, Object>> trafficSupplier,
                                  java.util.function.Supplier<Long> wallElapsedMsSupplier,
                                  Runnable onTick) {
        this.jobId = jobId;
        this.mappingId = mappingId;
        this.operation = operation != null ? operation : "git";
        this.remoteRole = remoteRole;
        this.remoteLabel = remoteLabel;
        this.webSocketNotificationService = webSocketNotificationService;
        this.onPhaseChange = onPhaseChange;
        this.pipelineSupplier = pipelineSupplier;
        this.trafficSupplier = trafficSupplier;
        this.wallElapsedMsSupplier = wallElapsedMsSupplier;
        this.onTick = onTick;
    }

    public int getObjectsReceived() {
        return objectsReceived;
    }

    /** Latest formatted progress line (for heartbeats when JGit is quiet between ticks). */
    public String lastProgressMessage() {
        return lastProgressMessage;
    }

    @Override
    public void start(int totalTasks) {
        // no-op
    }

    @Override
    public void beginTask(String title, int totalWork) {
        this.currentPhase = title != null ? title : operation;
        this.totalWork = totalWork;
        this.completedWork = 0;
        this.lastEmitAtMs = 0;
        this.phaseStartedAtMs = System.currentTimeMillis();

        boolean countingObjects = title != null && title.toLowerCase().contains("object");
        if (countingObjects && totalWork > 0) {
            objectsReceived = Math.max(objectsReceived, totalWork);
        }

        // Emit phase start to WebSocket immediately; also notify audit so the log is not
        // silent during long advertise/negotiate gaps before the first endTask.
        if (onPhaseChange != null) {
            onPhaseChange.accept(currentPhase, formatMessage());
        }
        emit(true);
    }

    @Override
    public void update(int completed) {
        if (completed > 0) {
            completedWork += completed;
            if (currentPhase != null && currentPhase.toLowerCase().contains("object") && objectsReceived == 0) {
                objectsReceived += completed;
            }
        }
        log.debug("[job-{}] {} {}: {}/{}", jobId, operation, currentPhase, completedWork, totalWork);
        emit(false);
    }

    @Override
    public void endTask() {
        if (totalWork > 0) {
            completedWork = totalWork;
        }
        String message = formatMessage();
        if (onPhaseChange != null) {
            onPhaseChange.accept(currentPhase, message);
        }
        emit(true);
    }

    private volatile java.util.function.Supplier<Boolean> cancelCheck;

    public void setCancelCheck(java.util.function.Supplier<Boolean> cancelCheck) {
        this.cancelCheck = cancelCheck;
    }

    @Override
    public boolean isCancelled() {
        return cancelCheck != null && Boolean.TRUE.equals(cancelCheck.get());
    }

    @Override
    public void showDuration(boolean enabled) {
        // no-op
    }

    private void emit(boolean force) {
        if (webSocketNotificationService == null || jobId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && lastEmitAtMs > 0 && (now - lastEmitAtMs) < THROTTLE_MS) {
            return;
        }
        lastEmitAtMs = now;
        long elapsedMs = phaseStartedAtMs > 0 ? Math.max(0, now - phaseStartedAtMs) : 0;
        Long etaMs = null;
        if (totalWork > 0 && completedWork > 0 && elapsedMs >= ETA_MIN_ELAPSED_MS) {
            etaMs = Math.round((totalWork - completedWork) * (elapsedMs / (double) completedWork));
        }
        if (onTick != null) {
            try {
                onTick.run();
            } catch (Exception e) {
                log.debug("Progress tick callback failed: {}", e.getMessage());
            }
        }
        Long wallElapsedMs = wallElapsedMsSupplier != null ? wallElapsedMsSupplier.get() : null;
        String message = formatMessage();
        lastProgressMessage = message;
        webSocketNotificationService.notifyJobProgress(
                jobId,
                mappingId,
                operation,
                currentPhase,
                completedWork,
                totalWork,
                message,
                etaMs,
                elapsedMs,
                wallElapsedMs,
                remoteRole,
                remoteLabel,
                pipelineSupplier != null ? pipelineSupplier.get() : null,
                trafficSupplier != null ? trafficSupplier.get() : null
        );
    }

    static Long estimateEtaMs(int current, int total, long elapsedMs) {
        if (total <= 0 || current <= 0 || elapsedMs < ETA_MIN_ELAPSED_MS) {
            return null;
        }
        return Math.round((total - current) * (elapsedMs / (double) current));
    }

    private String formatMessage() {
        String body;
        if (totalWork > 0) {
            int pct = (int) Math.min(100, Math.round(100.0 * completedWork / totalWork));
            body = currentPhase + ": " + completedWork + "/" + totalWork + " objects (" + pct + "%)";
        } else if (completedWork > 0) {
            body = currentPhase + ": " + completedWork + " objects";
        } else {
            body = currentPhase != null && !currentPhase.isBlank()
                    ? currentPhase
                    : ("Working on " + operation + "...");
        }
        String prefix = progressPrefix(remoteRole, remoteLabel);
        return prefix != null ? prefix + body : body;
    }

    static String progressPrefix(String remoteRole, String remoteLabel) {
        if (remoteLabel == null || remoteLabel.isBlank()) {
            return null;
        }
        String roleLabel;
        if ("source".equals(remoteRole)) {
            roleLabel = "Source fetch";
        } else if ("inspect".equals(remoteRole)) {
            roleLabel = "Inspect destination";
        } else if ("destination".equals(remoteRole)) {
            roleLabel = "Destination push";
        } else {
            roleLabel = remoteRole != null && !remoteRole.isBlank() ? remoteRole : "Git";
        }
        return roleLabel + " · " + remoteLabel + ": ";
    }
}
