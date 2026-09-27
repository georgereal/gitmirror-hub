package com.gitutility.service;

import com.gitutility.model.dto.PeerStatusResponse;
import com.gitutility.model.dto.WriteAuthorityRequest;
import com.gitutility.model.dto.WriteAuthorityView;
import com.gitutility.model.entity.PairFailoverState;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.ScmCredential;
import com.gitutility.model.enums.PairSide;
import com.gitutility.provider.ScmProviderFacade;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.WriteAuthorityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WriteAuthorityAndDrLaneTest {

    @Mock RepoMappingRepository mappingRepository;
    @Mock WriteAuthorityRepository writeAuthorityRepository;
    @Mock ScmCredentialService scmCredentialService;
    @Mock ScmProviderFacade scmProviderFacade;
    @Mock FailoverService failoverService;
    @Mock WriteAuthorityService writeAuthorityService;

    private RepoMapping mapping;

    @BeforeEach
    void setUp() {
        mapping = RepoMapping.builder()
                .id("lane")
                .name("lane")
                .repoAUrl("https://github.com/acme/origin.git")
                .repoBUrl("https://ghes.example/acme/mirror.git")
                .sourceProvider("GITHUB")
                .targetProvider("GHES")
                .sourceCredentialId("cred-a")
                .targetCredentialId("cred-b")
                .primarySide(PairSide.A)
                .build();
    }

    @Test
    void linkedPairRejectsBothSidesReadOnly() {
        WriteAuthorityService service = new WriteAuthorityService(
                writeAuthorityRepository, mappingRepository, scmCredentialService, scmProviderFacade);
        when(mappingRepository.findById("lane")).thenReturn(Optional.of(mapping));
        WriteAuthorityRequest request = new WriteAuthorityRequest();
        request.setPairId("lane");
        request.setLink("linked");
        request.setSides(List.of(side("A", "readonly"), side("B", "readonly")));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.apply(request));
        assertEquals("Unlink the pair to make both sides read-only.", error.getMessage());
    }

    @Test
    void laneLocksEnterpriseBeforeOrganizationBeforeRepository() {
        DrLaneService lane = lane();
        List<String> locks = new ArrayList<>();
        when(writeAuthorityService.apply(any())).thenAnswer(invocation -> {
            WriteAuthorityRequest request = invocation.getArgument(0);
            if (request.getEnterprise() != null && "readonly".equals(request.getEnterprise().getAccess())) {
                locks.add("ENTERPRISE");
                throw new IllegalArgumentException("enterprise rulesets are not enabled");
            }
            if (request.getOrg() != null && "readonly".equals(request.getOrg().getAccess())) {
                locks.add("ORG");
                throw new IllegalArgumentException("organization rulesets are not enabled");
            }
            return WriteAuthorityView.builder().build();
        });

        lane.activate(DrLaneService.laneKey(mapping));

        assertEquals(List.of("ENTERPRISE", "ORG"), locks);
        ArgumentCaptor<String> scope = ArgumentCaptor.forClass(String.class);
        verify(failoverService).activateDr(eq("lane"), scope.capture(), any(), any(), eq(false));
        assertEquals("REPO", scope.getValue());
    }

    @Test
    void laneStopsAtEnterpriseWhenThatLockApplies() {
        DrLaneService lane = lane();
        List<String> locks = new ArrayList<>();
        when(writeAuthorityService.apply(any())).thenAnswer(invocation -> {
            WriteAuthorityRequest request = invocation.getArgument(0);
            if (request.getEnterprise() != null && "readonly".equals(request.getEnterprise().getAccess())) {
                locks.add("ENTERPRISE");
            }
            if (request.getOrg() != null && "readonly".equals(request.getOrg().getAccess())) {
                locks.add("ORG");
            }
            return WriteAuthorityView.builder().build();
        });

        lane.activate(DrLaneService.laneKey(mapping));

        assertEquals(List.of("ENTERPRISE"), locks);
        verify(failoverService).activateDr(eq("lane"), eq("ENTERPRISE"), any(), any(), eq(true));
    }

    private DrLaneService lane() {
        when(mappingRepository.findAll()).thenReturn(List.of(mapping));
        when(writeAuthorityRepository.findAll()).thenReturn(List.of());
        when(scmCredentialService.require(anyString())).thenReturn(ScmCredential.builder()
                .id("cred-a")
                .enterpriseSlug("acme-ent")
                .build());
        when(scmProviderFacade.parseRepoFullName(org.mockito.ArgumentMatchers.contains("origin"))).thenReturn("acme/origin");
        when(scmProviderFacade.parseRepoFullName(org.mockito.ArgumentMatchers.contains("mirror"))).thenReturn("acme/mirror");
        PairFailoverState state = PairFailoverState.builder().mappingId("lane").phase("STEADY").build();
        when(failoverService.getOrCreate("lane")).thenReturn(state);
        when(failoverService.status("lane")).thenReturn(PeerStatusResponse.builder()
                .phase("STEADY")
                .processing("LIVE")
                .link("ok")
                .build());
        when(failoverService.activateDr(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(PeerStatusResponse.builder().phase("FAILOVER").build());
        return new DrLaneService(
                mappingRepository,
                failoverService,
                writeAuthorityService,
                scmCredentialService,
                scmProviderFacade,
                writeAuthorityRepository);
    }

    private static WriteAuthorityRequest.Side side(String id, String access) {
        WriteAuthorityRequest.Side side = new WriteAuthorityRequest.Side();
        side.setSide(id);
        side.setAccess(access);
        side.setScope("repo");
        side.setTarget("this_repo");
        return side;
    }
}
