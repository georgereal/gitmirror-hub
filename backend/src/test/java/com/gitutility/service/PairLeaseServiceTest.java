package com.gitutility.service;

import com.gitutility.model.entity.PairLease;
import com.gitutility.repository.PairLeaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PairLeaseServiceTest {

    private MemoryLeases leases;
    private PairLeaseService podA;
    private PairLeaseService podB;

    @BeforeEach
    void setUp() {
        leases = new MemoryLeases();
        podA = service("pod-a");
        podB = service("pod-b");
    }

    @Test
    void secondPodWaitsUntilReleaseOrExpiry() {
        podA.acquire("pair-1", "job-1");
        podA.acquire("pair-1", "job-1b");
        assertEquals("pod-a", leases.findById("pair-1").orElseThrow().getOwnerInstance());
        assertEquals("job-1b", leases.findById("pair-1").orElseThrow().getJobId());

        assertThrows(PairLeaseBusyException.class, () -> podB.acquire("pair-1", "job-2"));

        podA.release("pair-1");
        podB.acquire("pair-1", "job-2");
        assertEquals("pod-b", leases.findById("pair-1").orElseThrow().getOwnerInstance());

        leases.findById("pair-1").orElseThrow().setExpiresAt(Instant.EPOCH);
        podA.acquire("pair-1", "job-3");
        assertEquals("pod-a", leases.findById("pair-1").orElseThrow().getOwnerInstance());
        assertEquals("job-3", leases.findById("pair-1").orElseThrow().getJobId());
    }

    private PairLeaseService service(String instanceId) {
        InstanceIdentity identity = new InstanceIdentity();
        ReflectionTestUtils.setField(identity, "instanceId", instanceId);
        PairLeaseService service = new PairLeaseService(leases, identity);
        ReflectionTestUtils.setField(service, "leaseTtlSeconds", 90);
        return service;
    }

    private static final class MemoryLeases implements PairLeaseRepository {
        private final ConcurrentHashMap<String, PairLease> rows = new ConcurrentHashMap<>();

        @Override
        public int deleteOwned(String mappingId, String owner) {
            PairLease lease = rows.get(mappingId);
            if (lease == null || !owner.equals(lease.getOwnerInstance())) {
                return 0;
            }
            rows.remove(mappingId);
            return 1;
        }

        @Override
        public int renewOwned(String mappingId, String owner, String jobId, Instant expiresAt, Instant updatedAt) {
            PairLease lease = rows.get(mappingId);
            if (lease == null || !owner.equals(lease.getOwnerInstance())) {
                return 0;
            }
            lease.setJobId(jobId);
            lease.setExpiresAt(expiresAt);
            lease.setUpdatedAt(updatedAt);
            return 1;
        }

        @Override
        public PairLease save(PairLease entity) {
            rows.put(entity.getMappingId(), entity);
            return entity;
        }

        @Override
        public List<PairLease> saveAll(Iterable<PairLease> entities) {
            List<PairLease> saved = new ArrayList<>();
            entities.forEach(entity -> saved.add(save(entity)));
            return saved;
        }

        @Override
        public Optional<PairLease> findById(String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public boolean existsById(String id) {
            return rows.containsKey(id);
        }

        @Override
        public List<PairLease> findAll() {
            return List.copyOf(rows.values());
        }

        @Override
        public long count() {
            return rows.size();
        }

        @Override
        public void delete(PairLease entity) {
            rows.remove(entity.getMappingId());
        }

        @Override
        public void deleteById(String id) {
            rows.remove(id);
        }

        @Override
        public void deleteAll() {
            rows.clear();
        }
    }
}
