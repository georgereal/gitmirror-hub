package com.gitutility.controller;

import com.gitutility.persistence.PersistenceDescriptor;
import com.gitutility.persistence.PersistenceModule;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/persistence")
@RequiredArgsConstructor
public class PersistenceController {

    private final PersistenceModule persistenceModule;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getPersistenceModule() {
        PersistenceDescriptor d = persistenceModule.descriptor();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", d.getProvider().wireId());
        body.put("displayName", d.getDisplayName());
        body.put("description", d.getDescription());
        body.put("fileBacked", d.isFileBacked());
        body.put("externalStore", d.isExternalStore());
        body.put("requiresConnection", d.isRequiresConnection());
        body.put("supportsConsole", d.isSupportsConsole());
        return ResponseEntity.ok(body);
    }
}
