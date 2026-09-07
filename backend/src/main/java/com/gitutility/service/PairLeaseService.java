package com.gitutility.service;

import com.gitutility.model.entity.PairLease;
import com.gitutility.repository.PairLeaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PairLeaseService {

    private final PairLeaseRepository pairLeaseRepository;
    private final InstanceIdentity instanceIdentity;

    @Value("${git-utility.cluster.lease-ttl-seconds:90}")
    private int leaseTtlSeconds;

    @Transactional
    public void acquire(Long mappingId, Long jobId) {
        if (mappingId == null) {
            return;
        }
        Instant now = Instant.now();
        Instant expires = now.plus(Math.max(15, leaseTtlSeconds), ChronoUnit.SECONDS);
        String owner = instanceIdentity.getInstanceId();

        Optional<PairLease> existing = pairLeaseRepository.findById(mappingId);
        if (existing.isPresent()) {
            PairLease lease = existing.get();
            if (lease.getExpiresAt().isAfter(now) && !owner.equals(lease.getOwnerInstance())) {
                throw new PairLeaseBusyException(
                        "Pair " + mappingId + " leased by " + lease.getOwnerInstance()
                                + " until " + lease.getExpiresAt());
            }
            lease.setOwnerInstance(owner);
            lease.setJobId(jobId);
            lease.setExpiresAt(expires);
            lease.setUpdatedAt(now);
            pairLeaseRepository.save(lease);
            return;
        }

        try {
            pairLeaseRepository.save(PairLease.builder()
                    .mappingId(mappingId)
                    .ownerInstance(owner)
                    .jobId(jobId)
                    .expiresAt(expires)
                    .updatedAt(now)
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new PairLeaseBusyException("Pair " + mappingId + " lease race lost");
        }
    }

    @Transactional
    public void renew(Long mappingId, Long jobId) {
        if (mappingId == null) {
            return;
        }
        Instant now = Instant.now();
        Instant expires = now.plus(Math.max(15, leaseTtlSeconds), ChronoUnit.SECONDS);
        int updated = pairLeaseRepository.renewOwned(
                mappingId, instanceIdentity.getInstanceId(), jobId, expires, now);
        if (updated == 0) {
            acquire(mappingId, jobId);
        }
    }

    @Transactional
    public void release(Long mappingId) {
        if (mappingId == null) {
            return;
        }
        pairLeaseRepository.deleteOwned(mappingId, instanceIdentity.getInstanceId());
    }
}
