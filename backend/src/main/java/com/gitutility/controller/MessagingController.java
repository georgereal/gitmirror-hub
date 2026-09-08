package com.gitutility.controller;

import com.gitutility.messaging.MessagingDescriptor;
import com.gitutility.messaging.MessagingModule;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/messaging")
@RequiredArgsConstructor
public class MessagingController {

    private final MessagingModule messagingModule;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getMessagingModule() {
        MessagingDescriptor d = messagingModule.descriptor();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", d.getProvider().wireId());
        body.put("displayName", d.getDisplayName());
        body.put("description", d.getDescription());
        body.put("durableBroker", d.isDurableBroker());
        body.put("supportsQueueManager", d.isSupportsQueueManager());
        body.put("supportsDlq", d.isSupportsDlq());
        body.put("supportsPurge", d.isSupportsPurge());
        body.put("supportsPauseConsumers", d.isSupportsPauseConsumers());
        body.put("supportsInboundBrokerQueue", d.isSupportsInboundBrokerQueue());
        return ResponseEntity.ok(body);
    }
}
