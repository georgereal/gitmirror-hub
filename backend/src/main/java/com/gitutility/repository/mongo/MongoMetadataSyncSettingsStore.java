package com.gitutility.repository.mongo;

import com.gitutility.model.entity.MetadataSyncSettings;
import com.gitutility.persistence.PersistenceConditions.OnMongo;
import com.gitutility.repository.MetadataSyncSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@OnMongo
@RequiredArgsConstructor
public class MongoMetadataSyncSettingsStore implements MetadataSyncSettingsRepository {

    private final MetadataSyncSettingsMongoRepository repo;

    @Override
    public Optional<MetadataSyncSettings> findTopByOrderByIdAsc() {
        return repo.findTopByOrderByIdAsc();
    }

    @Override
    public MetadataSyncSettings save(MetadataSyncSettings entity) {
        return repo.save(entity);
    }

    @Override
    public List<MetadataSyncSettings> saveAll(Iterable<MetadataSyncSettings> entities) {
        return repo.saveAll(entities);
    }

    @Override
    public Optional<MetadataSyncSettings> findById(String id) {
        return repo.findById(id);
    }

    @Override
    public boolean existsById(String id) {
        return repo.existsById(id);
    }

    @Override
    public List<MetadataSyncSettings> findAll() {
        return repo.findAll();
    }

    @Override
    public long count() {
        return repo.count();
    }

    @Override
    public void delete(MetadataSyncSettings entity) {
        repo.delete(entity);
    }

    @Override
    public void deleteById(String id) {
        repo.deleteById(id);
    }

    @Override
    public void deleteAll() {
        repo.deleteAll();
    }
}
