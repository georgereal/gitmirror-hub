package com.gitutility.service;

import com.gitutility.model.entity.RepoMapping;
import com.gitutility.repository.RepoMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepoMappingCollisionTest {

    @Mock
    private RepoMappingRepository mappingRepository;

    @InjectMocks
    private RepoMappingService repoMappingService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void normalizeRepoKeyHandlesVariousUrlFormats() {
        assertEquals("github.com/acme/mirror-dest",
                RepoMappingService.normalizeRepoKey("https://github.com/acme/mirror-dest"));
        assertEquals("github.com/acme/mirror-dest",
                RepoMappingService.normalizeRepoKey("https://github.com/acme/mirror-dest.git"));
        assertEquals("github.com/acme/mirror-dest",
                RepoMappingService.normalizeRepoKey("https://github.com/acme/mirror-dest/"));
        assertEquals("github.com/acme/mirror-dest",
                RepoMappingService.normalizeRepoKey("git@github.com:acme/mirror-dest.git"));
        assertEquals("github.com/acme/mirror-dest",
                RepoMappingService.normalizeRepoKey("https://x-access-token:ghp_12345@github.com/acme/mirror-dest.git"));
        assertTrue(RepoMappingService.sameRepo(
                "https://github.com/acme/mirror-dest.git",
                "https://github.com/acme/mirror-dest"));
        assertFalse(RepoMappingService.sameRepo(
                "https://github.com/THU-MAIC/OpenMAIC",
                "https://github.com/acme/mirror-dest"));
    }

    @Test
    void validateNoRepositoryCollisionsThrowsWhenDestinationIsSharedWithActivePair() {
        RepoMapping existingPair = RepoMapping.builder()
                .id(1L)
                .name("OpenMAIC")
                .repoAUrl("https://github.com/THU-MAIC/OpenMAIC")
                .repoBUrl("https://github.com/acme/mirror-dest.git")
                .active(true)
                .build();

        when(mappingRepository.findAll()).thenReturn(List.of(existingPair));

        RepoMapping newPair = RepoMapping.builder()
                .id(2L)
                .name("vscode")
                .repoAUrl("https://github.com/microsoft/vscode")
                .repoBUrl("https://github.com/acme/mirror-dest")
                .active(true)
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                repoMappingService.validateNoRepositoryCollisions(null, newPair));

        assertTrue(ex.getMessage().contains("already participating in active mirror pair 'OpenMAIC'"));
    }

    @Test
    void validateNoRepositoryCollisionsPermitsUpdateOnSameMapping() {
        RepoMapping existingPair = RepoMapping.builder()
                .id(1L)
                .name("vscode")
                .repoAUrl("https://github.com/microsoft/vscode")
                .repoBUrl("https://github.com/acme/mirror-dest")
                .active(true)
                .build();

        when(mappingRepository.findAll()).thenReturn(List.of(existingPair));

        // Updating mapping 1 with same URLs should succeed
        assertDoesNotThrow(() -> repoMappingService.validateNoRepositoryCollisions(1L, existingPair));
    }

    @Test
    void validateNoRepositoryCollisionsAllowsInactivePairSharingUrl() {
        RepoMapping inactivePair = RepoMapping.builder()
                .id(1L)
                .name("Old Pair")
                .repoAUrl("https://github.com/THU-MAIC/OpenMAIC")
                .repoBUrl("https://github.com/acme/mirror-dest.git")
                .active(false)
                .build();

        when(mappingRepository.findAll()).thenReturn(List.of(inactivePair));

        RepoMapping newPair = RepoMapping.builder()
                .id(2L)
                .name("vscode")
                .repoAUrl("https://github.com/microsoft/vscode")
                .repoBUrl("https://github.com/acme/mirror-dest")
                .active(true)
                .build();

        assertDoesNotThrow(() -> repoMappingService.validateNoRepositoryCollisions(null, newPair));
    }
}
