package com.gitutility.persistence;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes {@code GIT_PERSISTENCE_PROVIDER} to a canonical wire id ({@code h2}|{@code mongo})
 * and symmetrically excludes the inactive stack's autoconfiguration so exactly one store is
 * wired per process: no fallback, no dual connections.
 *
 * <p>Exclusion lists only name classes guaranteed on the classpath in the relevant mode —
 * Spring fails startup on unknown {@code spring.autoconfigure.exclude} entries.</p>
 *
 * <p>Implements the Boot 4.1 SPI interface {@code org.springframework.boot.EnvironmentPostProcessor}
 * (registered via {@code META-INF/spring.factories}).</p>
 */
public class PersistenceEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String DATASOURCE_AUTO_CONFIGURATION =
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration";
    public static final String DATASOURCE_HEALTH_AUTO_CONFIGURATION =
            "org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration";
    public static final String DATA_JPA_REPOSITORIES_AUTO_CONFIGURATION =
            "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration";
    public static final String HIBERNATE_JPA_AUTO_CONFIGURATION =
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration";

    public static final String MONGO_AUTO_CONFIGURATION =
            "org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration";
    public static final String MONGO_HEALTH_AUTO_CONFIGURATION =
            "org.springframework.boot.mongodb.autoconfigure.health.MongoHealthContributorAutoConfiguration";
    public static final String MONGO_METRICS_AUTO_CONFIGURATION =
            "org.springframework.boot.mongodb.autoconfigure.metrics.MongoMetricsAutoConfiguration";
    public static final String DATA_MONGO_AUTO_CONFIGURATION =
            "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration";
    public static final String DATA_MONGO_REPOSITORIES_AUTO_CONFIGURATION =
            "org.springframework.boot.data.mongodb.autoconfigure.DataMongoRepositoriesAutoConfiguration";
    public static final String MONGO_REACTIVE_AUTO_CONFIGURATION =
            "org.springframework.boot.mongodb.autoconfigure.MongoReactiveAutoConfiguration";
    public static final String DATA_MONGO_REACTIVE_AUTO_CONFIGURATION =
            "org.springframework.boot.data.mongodb.autoconfigure.DataMongoReactiveAutoConfiguration";
    public static final String DATA_MONGO_REACTIVE_REPOSITORIES_AUTO_CONFIGURATION =
            "org.springframework.boot.data.mongodb.autoconfigure.DataMongoReactiveRepositoriesAutoConfiguration";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = firstNonBlank(
                environment.getProperty("git-utility.persistence.provider"),
                environment.getProperty("GIT_PERSISTENCE_PROVIDER"),
                "h2"
        );
        PersistenceProvider provider = PersistenceProvider.from(raw);

        // Read the existing exclude list ONCE, accumulate in-memory, then write back a single
        // property — re-reading per append would clobber the accumulation (overrides are not
        // visible to environment.getProperty() until the property source is registered below).
        List<String> excludes = currentExcludes(environment);
        if (provider == PersistenceProvider.MONGO) {
            // Relational stack is off: no DataSource, no JPA/Hibernate, no H2 console wiring.
            addMissing(excludes,
                    DATASOURCE_AUTO_CONFIGURATION,
                    DATASOURCE_HEALTH_AUTO_CONFIGURATION,
                    DATA_JPA_REPOSITORIES_AUTO_CONFIGURATION,
                    HIBERNATE_JPA_AUTO_CONFIGURATION);
        } else {
            // Mongo stack is off: no client, no repositories, no health/metrics probes.
            addMissing(excludes,
                    MONGO_AUTO_CONFIGURATION,
                    MONGO_HEALTH_AUTO_CONFIGURATION,
                    MONGO_METRICS_AUTO_CONFIGURATION,
                    DATA_MONGO_AUTO_CONFIGURATION,
                    DATA_MONGO_REPOSITORIES_AUTO_CONFIGURATION,
                    MONGO_REACTIVE_AUTO_CONFIGURATION,
                    DATA_MONGO_REACTIVE_AUTO_CONFIGURATION,
                    DATA_MONGO_REACTIVE_REPOSITORIES_AUTO_CONFIGURATION);
        }

        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("git-utility.persistence.provider", provider.wireId());
        if (!excludes.isEmpty()) {
            overrides.put("spring.autoconfigure.exclude", String.join(",", excludes));
        }

        environment.getPropertySources().addFirst(new MapPropertySource("gitUtilityPersistence", overrides));
    }

    private static List<String> currentExcludes(ConfigurableEnvironment environment) {
        String existing = environment.getProperty("spring.autoconfigure.exclude", "");
        List<String> excludes = new ArrayList<>();
        if (existing != null && !existing.isBlank()) {
            Arrays.stream(existing.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(excludes::add);
        }
        return excludes;
    }

    private static void addMissing(List<String> excludes, String... classNames) {
        for (String className : classNames) {
            if (!excludes.contains(className)) {
                excludes.add(className);
            }
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
