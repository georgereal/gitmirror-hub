package com.gitutility.persistence.store;

import com.gitutility.persistence.PersistenceConditions.OnH2;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Scopes Spring Data JPA repository scanning to {@code repository.h2} in h2 mode.
 * With this explicit registration present, Boot's
 * {@code DataJpaRepositoriesAutoConfiguration} backs off, so the
 * {@code repository.mongo} interfaces are never offered to the JPA stack
 * (required because the shared domain model carries both {@code @Entity} and
 * {@code @Document} annotations).
 */
@Configuration
@OnH2
@EnableJpaRepositories(basePackages = "com.gitutility.repository.h2")
public class H2RepositoriesConfig {
}
