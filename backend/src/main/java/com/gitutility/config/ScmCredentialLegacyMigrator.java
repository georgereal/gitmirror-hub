package com.gitutility.config;

import com.gitutility.service.ScmCredentialService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
@DependsOn("databaseSchemaMigrator")
@RequiredArgsConstructor
@Slf4j
public class ScmCredentialLegacyMigrator {

    private final ScmCredentialService credentialService;

    @PostConstruct
    public void migrate() {
        try {
            credentialService.migrateFromLegacyIfEmpty();
        } catch (Exception e) {
            log.warn("Legacy SCM credential migration skipped: {}", e.getMessage());
        }
    }
}
