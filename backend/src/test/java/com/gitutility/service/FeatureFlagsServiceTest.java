package com.gitutility.service;

import com.gitutility.model.entity.FeatureFlagsConfig;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.enums.RepoVisibility;
import com.gitutility.repository.FeatureFlagsConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeatureFlagsServiceTest {

    @Mock
    private FeatureFlagsConfigRepository repository;

    private FeatureFlagsService service;

    @BeforeEach
    void setUp() {
        service = new FeatureFlagsService(repository);
        ReflectionTestUtils.setField(service, "defaultPublicReposEnabled", true);
        ReflectionTestUtils.setField(service, "defaultProviderGitlabEnabled", false);
        ReflectionTestUtils.setField(service, "defaultProviderBitbucketEnabled", false);
        ReflectionTestUtils.setField(service, "defaultProviderOriginEnabled", false);
        ReflectionTestUtils.setField(service, "defaultProviderGenericEnabled", false);
    }

    @Test
    void blocksPublicMappingWhenDisabled() {
        FeatureFlagsConfig flags = FeatureFlagsConfig.builder()
                .publicReposEnabled(false)
                .build();
        when(repository.findTopByOrderByIdAsc()).thenReturn(Optional.of(flags));

        RepoMapping mapping = new RepoMapping();
        mapping.setRepoAUrl("https://github.com/acme/public.git");
        mapping.setRepoBUrl("https://github.com/acme/mirror.git");
        mapping.setSourceVisibility(RepoVisibility.PUBLIC);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.assertMappingAllowed(mapping));
        assertTrue(ex.getMessage().toLowerCase().contains("public"));
    }

    @Test
    void blocksGitlabUrlWhenProviderDisabled() {
        FeatureFlagsConfig flags = FeatureFlagsConfig.builder()
                .publicReposEnabled(true)
                .providerGitlabEnabled(false)
                .build();
        when(repository.findTopByOrderByIdAsc()).thenReturn(Optional.of(flags));

        RepoMapping mapping = new RepoMapping();
        mapping.setRepoAUrl("https://gitlab.com/acme/src.git");
        mapping.setRepoBUrl("https://github.com/acme/mirror.git");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.assertMappingAllowed(mapping));
        assertTrue(ex.getMessage().toLowerCase().contains("gitlab"));
    }

    @Test
    void githubAlwaysEnabled() {
        FeatureFlagsConfig flags = FeatureFlagsConfig.builder().build();
        when(repository.findTopByOrderByIdAsc()).thenReturn(Optional.of(flags));
        assertTrue(service.isProviderEnabled("GITHUB"));
        assertTrue(service.isProviderEnabled("GHES"));
        assertFalse(service.isProviderEnabled("GITLAB"));
    }

    @Test
    void seedsFromDefaultsWhenEmpty() {
        when(repository.findTopByOrderByIdAsc()).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        FeatureFlagsConfig created = service.getOrCreate();
        assertTrue(created.isPublicReposEnabled());
        assertFalse(created.isProviderGitlabEnabled());
        verify(repository).save(any());
    }
}
