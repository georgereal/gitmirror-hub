package com.gitutility.service;

import com.gitutility.model.dto.StorageStatusResponse;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.enums.StorageTier;
import com.gitutility.repository.RepoMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StorageTieringTest {

    @Mock
    private RepoMappingRepository repoMappingRepository;

    @InjectMocks
    private StorageTieringService storageTieringService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(storageTieringService, "localDir", "/tmp/git-utility-mirrors-test");
        ReflectionTestUtils.setField(storageTieringService, "nasDir", "/tmp/git-utility-nas-test");
        ReflectionTestUtils.setField(storageTieringService, "maxDiskQuotaMb", 51200L);
        ReflectionTestUtils.setField(storageTieringService, "maxCachedRepos", 1000);
        ReflectionTestUtils.setField(storageTieringService, "retentionHours", 72);
        storageTieringService.init();
    }

    @Test
    void testResolveRepoDirectoryForTiers() {
        File hotDir = storageTieringService.resolveRepoDirectory(1L, StorageTier.HOT_PERSISTENT);
        assertTrue(hotDir.getAbsolutePath().contains("git-utility-mirrors-test"));
        assertTrue(hotDir.getName().equals("pair-1.git"));

        File nasDir = storageTieringService.resolveRepoDirectory(2L, StorageTier.NAS_MOUNT);
        assertTrue(nasDir.getAbsolutePath().contains("git-utility-nas-test"));
        assertTrue(nasDir.getName().equals("pair-2.git"));

        File ephemeralDir = storageTieringService.resolveRepoDirectory(3L, StorageTier.EPHEMERAL_STREAM);
        assertTrue(ephemeralDir.getName().startsWith("git-ephemeral-pair-3-"));

        // Cleanup ephemeral test dir
        storageTieringService.cleanupEphemeralRepo(ephemeralDir);
        assertFalse(ephemeralDir.exists());
    }

    @Test
    void testStorageStatusReporting() {
        RepoMapping m1 = RepoMapping.builder().id(1L).name("pair-1").storageTier(StorageTier.HOT_PERSISTENT).build();
        RepoMapping m2 = RepoMapping.builder().id(2L).name("pair-2").storageTier(StorageTier.NAS_MOUNT).build();
        RepoMapping m3 = RepoMapping.builder().id(3L).name("pair-3").storageTier(StorageTier.EPHEMERAL_STREAM).build();

        when(repoMappingRepository.findAll()).thenReturn(List.of(m1, m2, m3));

        StorageStatusResponse status = storageTieringService.getStorageStatus();
        assertNotNull(status);
        assertEquals(1, status.getHotReposCount());
        assertEquals(1, status.getNasReposCount());
        assertEquals(1, status.getEphemeralReposCount());
        assertNotNull(status.getLocalUsedFormatted());
    }
}
