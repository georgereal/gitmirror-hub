package com.gitutility.persistence.contract;

import com.gitutility.model.entity.RepoMapping;
import com.gitutility.repository.RepoMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.annotation.Transactional;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Store contract for the RepoMapping facade — must hold for the H2 store and the
 * MongoDB store alike (incl. the URL/contains matching port reimplemented with Mongo regex).
 */
@Transactional
public abstract class RepoMappingStoreContract {

    protected abstract RepoMappingRepository repo();

    @BeforeEach
    void clean() {
        repo().deleteAll();
    }

    private RepoMapping mapping(String name, String aUrl, String bUrl, boolean active, String credential) {
        return RepoMapping.builder()
                .name(name)
                .repoAUrl(aUrl)
                .repoBUrl(bUrl)
                .active(active)
                .sourceCredentialId(credential)
                .build();
    }

    @Test
    void saveAssignsStringIdAndRoundTrips() {
        RepoMapping saved = repo().save(mapping("pair-1",
                "https://github.com/acme/src.git", "https://github.com/acme/dst.git", true, null));

        assertNotNull(saved.getId());
        assertFalse(saved.getId().isBlank());
        assertTrue(repo().findById(saved.getId()).isPresent());
        assertEquals("pair-1", repo().findById(saved.getId()).orElseThrow().getName());
    }

    @Test
    void findByIdMissingReturnsEmpty() {
        assertTrue(repo().findById("does-not-exist").isEmpty());
    }

    @Test
    void findByNameFindsExactName() {
        repo().save(mapping("pair-a", "https://a/1.git", "https://b/1.git", true, null));
        repo().save(mapping("pair-b", "https://a/2.git", "https://b/2.git", true, null));

        assertEquals("pair-a", repo().findByName("pair-a").orElseThrow().getName());
        assertTrue(repo().findByName("missing").isEmpty());
    }

    @Test
    void deleteByIdAndExistsAndCount() {
        RepoMapping a = repo().save(mapping("pair-a", "https://a/1.git", "https://b/1.git", true, null));
        repo().save(mapping("pair-b", "https://a/2.git", "https://b/2.git", true, null));
        assertEquals(2, repo().count());
        assertTrue(repo().existsById(a.getId()));

        repo().deleteById(a.getId());
        assertEquals(1, repo().count());
        assertFalse(repo().existsById(a.getId()));
    }

    @Test
    void deleteEntityRemovesRow() {
        RepoMapping saved = repo().save(mapping("pair-a", "https://a/1.git", "https://b/1.git", true, null));
        repo().delete(saved);
        assertTrue(repo().findById(saved.getId()).isEmpty());
    }

    @Test
    void saveAllPersistsBatch() {
        repo().saveAll(List.of(
                mapping("pair-a", "https://a/1.git", "https://b/1.git", true, null),
                mapping("pair-b", "https://a/2.git", "https://b/2.git", true, null)));
        assertEquals(2, repo().findAll().size());
    }

    @Test
    void findActiveMatchingRepoMatchesExactUrlOnEitherSide() {
        repo().save(mapping("pair-a", "https://github.com/acme/src.git", "https://github.com/acme/dst.git", true, null));
        repo().save(mapping("pair-b", "https://github.com/other/one.git", "https://github.com/other/two.git", true, null));

        List<RepoMapping> viaA = repo().findActiveMatchingRepo("https://github.com/acme/src.git", "nomatch-token");
        List<RepoMapping> viaB = repo().findActiveMatchingRepo("https://github.com/other/two.git", "nomatch-token");
        assertEquals(1, viaA.size());
        assertEquals("pair-a", viaA.get(0).getName());
        assertEquals(1, viaB.size());
        assertEquals("pair-b", viaB.get(0).getName());
    }

    @Test
    void findActiveMatchingRepoMatchesUrlContainingPath() {
        repo().save(mapping("pair-a", "https://github.com/acme/src.git", "https://github.com/acme/dst.git", true, null));

        List<RepoMapping> hit = repo().findActiveMatchingRepo("https://nomatch.example/x.git", "acme/src");
        assertEquals(1, hit.size());
        assertEquals("pair-a", hit.get(0).getName());
    }

    @Test
    void findActiveMatchingRepoIgnoresInactivePairs() {
        repo().save(mapping("pair-a", "https://github.com/acme/src.git", "https://github.com/acme/dst.git", false, null));
        assertTrue(repo().findActiveMatchingRepo("https://github.com/acme/src.git", "nomatch-token").isEmpty());
    }

    @Test
    void countBySourceCredentialIdOrTargetCredentialId() {
        repo().save(RepoMapping.builder().name("pair-a")
                .repoAUrl("https://a/1.git").repoBUrl("https://b/1.git")
                .sourceCredentialId("cred-1").build());
        repo().save(RepoMapping.builder().name("pair-b")
                .repoAUrl("https://a/2.git").repoBUrl("https://b/2.git")
                .targetCredentialId("cred-1").build());
        repo().save(RepoMapping.builder().name("pair-c")
                .repoAUrl("https://a/3.git").repoBUrl("https://b/3.git")
                .build());

        assertEquals(2, repo().countBySourceCredentialIdOrTargetCredentialId("cred-1", "cred-1"));
        assertEquals(0, repo().countBySourceCredentialIdOrTargetCredentialId("cred-x", "cred-x"));
    }

    @Test
    void findByBulkSubmissionId() {
        RepoMapping a = repo().save(mapping("pair-a", "https://a/1.git", "https://b/1.git", true, null));
        a.setBulkSubmissionId("sub-1");
        repo().save(a);
        repo().save(mapping("pair-b", "https://a/2.git", "https://b/2.git", true, null));

        assertEquals(1, repo().findByBulkSubmissionId("sub-1").size());
        assertEquals(0, repo().findByBulkSubmissionId("sub-missing").size());
    }
}
