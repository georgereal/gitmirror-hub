package com.gitutility.repository.mongo;

import com.gitutility.model.entity.MetadataSyncSettings;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface MetadataSyncSettingsMongoRepository extends MongoRepository<MetadataSyncSettings, String> {

    Optional<MetadataSyncSettings> findTopByOrderByIdAsc();
}
