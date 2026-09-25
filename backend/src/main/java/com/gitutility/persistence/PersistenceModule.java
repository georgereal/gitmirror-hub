package com.gitutility.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PersistenceModule {

    @Value("${git-utility.persistence.provider:h2}")
    private String providerProperty;

    public PersistenceProvider provider() {
        return PersistenceProvider.from(providerProperty);
    }

    public PersistenceDescriptor descriptor() {
        return switch (provider()) {
            case H2 -> PersistenceDescriptor.builder()
                    .provider(PersistenceProvider.H2)
                    .displayName("H2 (embedded file)")
                    .description("File-based H2 with Spring Data JPA. Local dev and multi-pod smoke; data lives in ./data/gitutility.")
                    .fileBacked(true)
                    .externalStore(false)
                    .requiresConnection(false)
                    .supportsConsole(true)
                    .build();
            case MONGO -> PersistenceDescriptor.builder()
                    .provider(PersistenceProvider.MONGO)
                    .displayName("MongoDB")
                    .description("External MongoDB via Spring Data MongoDB. Enterprise scale-out; must be reachable at startup — no fallback.")
                    .fileBacked(false)
                    .externalStore(true)
                    .requiresConnection(true)
                    .supportsConsole(false)
                    .build();
        };
    }
}
