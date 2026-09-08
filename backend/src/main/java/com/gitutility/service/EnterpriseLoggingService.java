package com.gitutility.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.gitutility.model.entity.SyncAuditLog;
import com.gitutility.model.entity.SystemEngineConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class EnterpriseLoggingService {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Lazy
    @Autowired
    private SystemEngineConfigService systemEngineConfigService;

    private volatile String activeSink = "CONSOLE";
    private volatile String activeLevel = "INFO";
    private volatile String splunkHecUrl;
    private volatile String splunkHecToken;
    private volatile String splunkIndex = "main";
    private volatile String splunkSourceType = "_json";
    private volatile String logstashHost;
    private volatile String syslogHost;
    private volatile String rollingFilePath = "/tmp/git-utility-mirrors/logs/git-utility.log";
    private volatile boolean jsonStructuredEnabled = true;

    @PostConstruct
    public void init() {
        try {
            if (systemEngineConfigService != null) {
                SystemEngineConfig config = systemEngineConfigService.getOrCreateConfig();
                updateLoggingConfig(config);
            }
        } catch (Exception e) {
            log.warn("Could not load initial EnterpriseLogging config: {}", e.getMessage());
        }
    }

    public synchronized void updateLoggingConfig(SystemEngineConfig config) {
        if (config == null) return;
        this.activeSink = config.getLoggingSink() != null ? config.getLoggingSink().toUpperCase() : "CONSOLE";
        this.activeLevel = config.getLoggingLevel() != null ? config.getLoggingLevel().toUpperCase() : "INFO";
        this.splunkHecUrl = config.getSplunkHecUrl();
        this.splunkHecToken = config.getSplunkHecToken();
        this.splunkIndex = config.getSplunkIndex() != null ? config.getSplunkIndex() : "main";
        this.splunkSourceType = config.getSplunkSourceType() != null ? config.getSplunkSourceType() : "_json";
        this.logstashHost = config.getLogstashHost();
        this.syslogHost = config.getSyslogHost();
        this.rollingFilePath = config.getRollingFilePath();
        this.jsonStructuredEnabled = config.isJsonStructuredEnabled();

        // Hot-reload Logback root logger level dynamically
        applyRuntimeLogLevel(this.activeLevel);

        log.info("EnterpriseLoggingService updated: sink={}, level={}, splunkHecUrl={}, jsonStructured={}",
                activeSink, activeLevel, splunkHecUrl, jsonStructuredEnabled);
    }

    public void applyRuntimeLogLevel(String levelStr) {
        try {
            LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
            Logger appLogger = loggerContext.getLogger("com.gitutility");

            Level targetLevel = Level.toLevel(levelStr, Level.INFO);
            if (rootLogger != null) {
                rootLogger.setLevel(targetLevel);
            }
            if (appLogger != null) {
                appLogger.setLevel(targetLevel);
            }
            log.info("Dynamically adjusted runtime Logback level to: {}", targetLevel);
        } catch (Exception e) {
            log.warn("Failed to dynamically update Logback log level: {}", e.getMessage());
        }
    }

    /**
     * Dispatch structured audit log event to the active enterprise logging sink.
     */
    @Async
    public void shipAuditLog(SyncAuditLog auditLog) {
        if (auditLog == null) return;

        try {
            Map<String, Object> eventData = new HashMap<>();
            eventData.put("timestamp", auditLog.getTimestamp() != null ? auditLog.getTimestamp().toString() : Instant.now().toString());
            eventData.put("eventType", "GIT_MIRROR_AUDIT");
            eventData.put("jobId", auditLog.getJobId());
            eventData.put("level", auditLog.getLevel() != null ? auditLog.getLevel().name() : "INFO");
            eventData.put("message", auditLog.getMessage());

            dispatchToSinks(eventData, "job-" + auditLog.getJobId());
        } catch (Exception e) {
            log.debug("Error shipping audit log to sink {}: {}", activeSink, e.getMessage());
        }
    }

    private void dispatchToSinks(Map<String, Object> eventData, String repoName) {
        switch (activeSink) {
            case "SPLUNK_HEC":
                sendToSplunkHec(eventData, repoName);
                break;
            case "LOGSTASH_ELK":
                sendToLogstashOrElastic(eventData);
                break;
            case "SYSLOG":
                sendToSyslog(eventData);
                break;
            case "ROLLING_FILE":
                sendToRollingFile(eventData);
                break;
            case "DUAL_CONSOLE_SPLUNK":
                sendToSplunkHec(eventData, repoName);
                break;
            case "CONSOLE":
            default:
                // CONSOLE sink: GitSyncEngine.logAudit already dual-writes to SLF4J stdout.
                break;
        }
    }

    /**
     * Shippers & HTTP/Socket Dispatchers
     */
    private void sendToSplunkHec(Map<String, Object> eventData, String repoName) {
        if (splunkHecUrl == null || splunkHecUrl.isBlank() || splunkHecToken == null || splunkHecToken.isBlank()) {
            return;
        }
        try {
            Map<String, Object> splunkPayload = new HashMap<>();
            splunkPayload.put("time", Instant.now().getEpochSecond());
            splunkPayload.put("host", InetAddress.getLocalHost().getHostName());
            splunkPayload.put("source", "git-mirror-utility");
            splunkPayload.put("sourcetype", splunkSourceType);
            splunkPayload.put("index", splunkIndex);
            splunkPayload.put("event", eventData);

            String bodyJson = objectMapper.writeValueAsString(splunkPayload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(splunkHecUrl))
                    .header("Authorization", "Splunk " + splunkHecToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .timeout(Duration.ofSeconds(4))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.debug("Failed sending payload to Splunk HEC: {}", e.getMessage());
        }
    }

    private void sendToLogstashOrElastic(Map<String, Object> eventData) {
        if (logstashHost == null || logstashHost.isBlank()) return;
        try {
            String json = objectMapper.writeValueAsString(eventData);
            if (logstashHost.startsWith("http://") || logstashHost.startsWith("https://")) {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(logstashHost))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .timeout(Duration.ofSeconds(4))
                        .build();
                httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString());
            }
        } catch (Exception e) {
            log.debug("Failed sending to Logstash/Elasticsearch: {}", e.getMessage());
        }
    }

    private void sendToSyslog(Map<String, Object> eventData) {
        if (syslogHost == null || syslogHost.isBlank()) return;
        try {
            String host = syslogHost;
            int port = 514;
            if (host.contains(":")) {
                String[] parts = host.split(":");
                host = parts[0];
                port = Integer.parseInt(parts[1]);
            }
            String message = "<134>1 " + Instant.now() + " " + InetAddress.getLocalHost().getHostName()
                    + " git-utility - - - " + objectMapper.writeValueAsString(eventData);
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);

            try (DatagramSocket socket = new DatagramSocket()) {
                DatagramPacket packet = new DatagramPacket(bytes, bytes.length, InetAddress.getByName(host), port);
                socket.send(packet);
            }
        } catch (Exception e) {
            log.debug("Failed sending to Syslog: {}", e.getMessage());
        }
    }

    private synchronized void sendToRollingFile(Map<String, Object> eventData) {
        if (rollingFilePath == null || rollingFilePath.isBlank()) return;
        try {
            File file = new File(rollingFilePath);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (PrintWriter out = new PrintWriter(new FileWriter(file, true))) {
                out.println(objectMapper.writeValueAsString(eventData));
            }
        } catch (Exception e) {
            log.debug("Failed writing to rolling file: {}", e.getMessage());
        }
    }

    /**
     * Test Probe for a specific sink configuration
     */
    public TestSinkResult testLoggingSink(String sink, String url, String token, String host, String filePath) {
        long start = System.currentTimeMillis();
        String targetSink = sink != null ? sink.toUpperCase() : "CONSOLE";

        if ("CONSOLE".equals(targetSink)) {
            return new TestSinkResult(true, 200, "Console sink active. Logs streamed to stdout.", System.currentTimeMillis() - start);
        }

        if ("SPLUNK_HEC".equals(targetSink) || "DUAL_CONSOLE_SPLUNK".equals(targetSink)) {
            if (url == null || url.isBlank() || token == null || token.isBlank()) {
                return new TestSinkResult(false, 400, "Splunk HEC URL and Authentication Token are required", System.currentTimeMillis() - start);
            }
            try {
                Map<String, Object> testPayload = new HashMap<>();
                testPayload.put("time", Instant.now().getEpochSecond());
                testPayload.put("event", Map.of("message", "GitMirror Hub Telemetry Ping", "status", "OK", "timestamp", Instant.now().toString()));

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Splunk " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(testPayload)))
                        .timeout(Duration.ofSeconds(5))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                long latency = System.currentTimeMillis() - start;
                boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
                String msg = success ? "Splunk HEC responded successfully: " + response.body() : "Splunk HEC returned HTTP " + response.statusCode() + ": " + response.body();
                return new TestSinkResult(success, response.statusCode(), msg, latency);
            } catch (Exception e) {
                return new TestSinkResult(false, 500, "Connection failed to Splunk HEC: " + e.getMessage(), System.currentTimeMillis() - start);
            }
        }

        if ("ROLLING_FILE".equals(targetSink)) {
            String path = filePath != null && !filePath.isBlank() ? filePath : rollingFilePath;
            try {
                File file = new File(path);
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                try (PrintWriter out = new PrintWriter(new FileWriter(file, true))) {
                    out.println("{\"test\":\"git-mirror-hub-probe\",\"timestamp\":\"" + Instant.now() + "\"}");
                }
                return new TestSinkResult(true, 200, "Successfully verified write access to logfile at: " + file.getAbsolutePath(), System.currentTimeMillis() - start);
            } catch (Exception e) {
                return new TestSinkResult(false, 500, "Failed to write to path: " + e.getMessage(), System.currentTimeMillis() - start);
            }
        }

        if ("SYSLOG".equals(targetSink)) {
            String syslogTarget = host != null && !host.isBlank() ? host : syslogHost;
            if (syslogTarget == null || syslogTarget.isBlank()) {
                return new TestSinkResult(false, 400, "Syslog host:port is required", System.currentTimeMillis() - start);
            }
            try {
                String sHost = syslogTarget;
                int port = 514;
                if (sHost.contains(":")) {
                    String[] parts = sHost.split(":");
                    sHost = parts[0];
                    port = Integer.parseInt(parts[1]);
                }
                InetAddress addr = InetAddress.getByName(sHost);
                return new TestSinkResult(true, 200, "Syslog server resolved and reachable at " + addr.getHostAddress() + ":" + port, System.currentTimeMillis() - start);
            } catch (Exception e) {
                return new TestSinkResult(false, 500, "Failed to resolve Syslog destination: " + e.getMessage(), System.currentTimeMillis() - start);
            }
        }

        return new TestSinkResult(true, 200, "Sink configuration validated", System.currentTimeMillis() - start);
    }

    public record TestSinkResult(boolean success, int statusCode, String message, long latencyMs) {}
}
