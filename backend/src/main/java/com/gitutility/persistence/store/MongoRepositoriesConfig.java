package com.gitutility.persistence.store;

import com.gitutility.persistence.PersistenceConditions.OnMongo;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Scopes Spring Data MongoDB repository scanning to {@code repository.mongo} in mongo
 * mode. With this explicit registration present, Boot's Mongo autoconfiguration backs
 * off, so the {@code repository.h2} interfaces are never offered to the Mongo stack
 * (required because the shared domain model carries both {@code @Entity} and
 * {@code @Document} annotations).
 *
 * <p>Also registers a {@link MongoTransactionManager} so the {@code @Transactional}
 * service methods (pair leases, config seeding, heartbeats) run on both providers.
 * MongoDB transactions require a replica set — production (Atlas) and the
 * contract-test container qualify; a bare standalone {@code mongod} must be started
 * as a single-node replica set.</p>
 */
@Configuration
@OnMongo
@EnableMongoRepositories(basePackages = "com.gitutility.repository.mongo")
public class MongoRepositoriesConfig {

    @Bean
    public PlatformTransactionManager mongoTransactionManager(MongoDatabaseFactory dbFactory) {
        return new MongoTransactionManager(dbFactory);
    }
}
