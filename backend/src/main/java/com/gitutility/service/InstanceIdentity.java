package com.gitutility.service;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

/**
 * Stable identity for this JVM / pod. Used for pair leases, heartbeats, and Internals.
 */
@Component
@Slf4j
public class InstanceIdentity {

    @Value("${git-utility.instance.id:}")
    private String configuredId;

    @Getter
    private String instanceId;

    @PostConstruct
    void init() {
        if (configuredId != null && !configuredId.isBlank()) {
            instanceId = configuredId.trim();
        } else {
            String host = System.getenv("HOSTNAME");
            if (host == null || host.isBlank()) {
                host = System.getenv("POD_NAME");
            }
            if (host == null || host.isBlank()) {
                try {
                    host = InetAddress.getLocalHost().getHostName();
                } catch (Exception e) {
                    host = "unknown";
                }
            }
            instanceId = host;
        }
        log.info("Hub instance identity: {}", instanceId);
    }
}
