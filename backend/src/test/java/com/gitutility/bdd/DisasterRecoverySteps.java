package com.gitutility.bdd;

import com.gitutility.messaging.webhook.WebhookIncrementalService;
import com.gitutility.model.dto.IncrementalGitEvent;
import com.gitutility.model.entity.FailoverParkedEvent;
import com.gitutility.model.entity.PairFailoverState;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.enums.PairSide;
import com.gitutility.model.enums.SyncDirection;
import com.gitutility.repository.FailoverParkedEventRepository;
import com.gitutility.repository.PairFailoverStateRepository;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.WriteAuthorityRepository;
import com.gitutility.service.FailoverService;
import com.gitutility.service.ReplicaRulesetService;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DisasterRecoverySteps {

    private static final String LANE = "lane";
    private static final String ORIGIN = "https://github.com/acme/origin.git";
    private static final String REPLICA = "https://ghes.example/acme/mirror.git";

    private RepoMappingRepository mappings;
    private PairFailoverStateRepository states;
    private FailoverParkedEventRepository parkedEvents;
    private ReplicaRulesetService rules;
    private WebhookIncrementalService incremental;
    private FailoverService failover;
    private RepoMapping mapping;
    private final List<PairFailoverState> savedStates = new ArrayList<>();
    private final List<FailoverParkedEvent> parked = new ArrayList<>();
    private final List<String> locks = new ArrayList<>();
    private long lockResult;

    @Before("@disaster-recovery")
    public void reset() throws Exception {
        mappings = mock(RepoMappingRepository.class);
        states = mock(PairFailoverStateRepository.class);
        parkedEvents = mock(FailoverParkedEventRepository.class);
        rules = mock(ReplicaRulesetService.class);
        incremental = mock(WebhookIncrementalService.class);
        savedStates.clear();
        parked.clear();
        locks.clear();
        lockResult = 0L;
        mapping = RepoMapping.builder()
                .id(LANE)
                .name("origin-to-ghes")
                .repoAUrl(ORIGIN)
                .repoBUrl(REPLICA)
                .primarySide(PairSide.A)
                .syncDirection(SyncDirection.UNIDIRECTIONAL_A_TO_B)
                .active(true)
                .build();
        when(mappings.findById(LANE)).thenReturn(Optional.of(mapping));
        when(mappings.save(any(RepoMapping.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(states.findByMappingId(any())).thenAnswer(invocation -> savedStates.stream()
                .filter(state -> invocation.getArgument(0).equals(state.getMappingId()))
                .reduce((first, second) -> second));
        when(states.save(any(PairFailoverState.class))).thenAnswer(invocation -> {
            PairFailoverState state = invocation.getArgument(0);
            savedStates.add(state);
            return state;
        });
        when(parkedEvents.findByMappingIdAndDeliveryId(any(), any())).thenReturn(Optional.empty());
        when(parkedEvents.save(any(FailoverParkedEvent.class))).thenAnswer(invocation -> {
            FailoverParkedEvent row = invocation.getArgument(0);
            parked.add(row);
            return row;
        });
        when(parkedEvents.findByMappingIdAndStatusOrderByReceivedAtAsc(any(), any())).thenAnswer(invocation ->
                parked.stream().filter(row -> invocation.getArgument(1).equals(row.getStatus())).toList());
        when(parkedEvents.countByMappingIdAndStatus(any(), any())).thenAnswer(invocation ->
                parked.stream().filter(row -> invocation.getArgument(1).equals(row.getStatus())).count());
        when(rules.tryEnforce(any(), any(), any())).thenAnswer(invocation -> {
            locks.add(invocation.getArgument(1) + ":" + invocation.getArgument(2));
            return lockResult;
        });
        WriteAuthorityRepository authorities = mock(WriteAuthorityRepository.class);
        when(authorities.findByMappingId(any())).thenReturn(List.of());
        failover = new FailoverService(states, parkedEvents, mappings, authorities, rules);
        failover.setWebhookIncrementalService(incremental);
    }

    @Given("a lane from host {string} to host {string}")
    public void lane(String sourceHost, String replicaHost) {
        assertEquals(sourceHost, FailoverService.hostOf(mapping.getRepoAUrl()));
        assertEquals(replicaHost, FailoverService.hostOf(mapping.getRepoBUrl()));
    }

    @Given("the source host is down")
    public void sourceDown() {
        remember(state().sideA("DOWN").sideB("UP").build());
    }

    @Given("disaster recovery is active on the lane")
    public void disasterRecoveryActive() {
        mapping.setPrimarySide(PairSide.B);
        mapping.setSyncDirection(SyncDirection.UNIDIRECTIONAL_B_TO_A);
        remember(state()
                .phase("FAILOVER")
                .activePrimary("B")
                .peerLock("PENDING")
                .priorDirection(SyncDirection.UNIDIRECTIONAL_A_TO_B.name())
                .sideA("DOWN")
                .sideB("UP")
                .build());
    }

    @Given("the demoted host refuses the connection")
    public void demotedRefuses() {
        latest().setSideA("DOWN");
    }

    @Given("the demoted host is still down")
    public void demotedStillDown() {
        latest().setSideA("DOWN");
        latest().setSideB("UP");
    }

    @Given("both providers are up")
    public void bothUp() throws Exception {
        lockResult = 1L;
        latest().setSideA("UP");
        latest().setSideB("UP");
        seedProbe(ORIGIN, true);
        seedProbe(REPLICA, true);
    }

    @Given("the read-only lock has been applied")
    public void lockApplied() {
        latest().setPeerLock("APPLIED");
        parked.add(FailoverParkedEvent.builder()
                .mappingId(LANE)
                .deliveryId("parked-before")
                .eventType("push")
                .repoUrl(ORIGIN)
                .ref("refs/heads/main")
                .status("PARKED")
                .build());
    }

    @When("the operator activates disaster recovery on that lane")
    public void activate() {
        failover.activateDr(LANE);
    }

    @When("an incremental event arrives")
    public void incrementalArrives() {
        IncrementalGitEvent event = IncrementalGitEvent.builder()
                .provider("github")
                .repoUrl(ORIGIN)
                .ref("refs/heads/main")
                .beforeSha("aaa")
                .afterSha("bbb")
                .deliveryId("delivery-1")
                .eventType("push")
                .build();
        assertTrue(failover.shouldPark(mapping));
        failover.park(mapping, event, "PEER_UNREACHABLE");
    }

    @When("the operator fails the lane back")
    public void failBack() throws Exception {
        failover.failBack(LANE);
    }

    @Then("the live replica is writable")
    public void liveReplicaWritable() {
        assertEquals(PairSide.B, mapping.getPrimarySide());
    }

    @Then("incremental sync points at the demoted source")
    public void incrementalsPointAtDemotedSource() {
        assertEquals(SyncDirection.UNIDIRECTIONAL_B_TO_A, mapping.getSyncDirection());
    }

    @Then("the read-only lock on the demoted host stays pending until that host answers")
    public void lockStaysPending() {
        assertEquals("PENDING", latest().getPeerLock());
    }

    @Then("the event is parked")
    public void eventParked() {
        assertFalse(parked.isEmpty());
        assertEquals("PARKED", parked.get(parked.size() - 1).getStatus());
    }

    @Then("the event is not dead-lettered")
    public void notDeadLettered() {
        assertTrue(parked.stream().noneMatch(row -> "DEAD_LETTERED".equals(row.getStatus())));
        assertEquals("PARKED", parked.get(parked.size() - 1).getStatus());
    }

    @Then("fail back is disabled")
    public void failBackDisabled() {
        assertFalse(failover.processingAllowed(latest()));
    }

    @Then("the original source is writable")
    public void originalWritable() {
        assertEquals(PairSide.A, mapping.getPrimarySide());
        assertEquals(SyncDirection.UNIDIRECTIONAL_A_TO_B, mapping.getSyncDirection());
    }

    @Then("the side that was writable during disaster recovery is locked")
    public void drSideLocked() {
        assertTrue(locks.contains("B:active"));
        assertEquals("active", mapping.getReplicaRulesetEnforcement());
    }

    @Then("parked events are drained")
    public void parkedDrained() {
        assertFalse(parked.isEmpty());
        assertTrue(parked.stream().allMatch(row -> "APPLIED".equals(row.getStatus())));
        verify(incremental).handle(any());
    }

    private PairFailoverState.PairFailoverStateBuilder state() {
        return PairFailoverState.builder().mappingId(LANE).phase("STEADY").peerLock("NONE");
    }

    private void remember(PairFailoverState state) {
        savedStates.add(state);
    }

    private PairFailoverState latest() {
        return savedStates.get(savedStates.size() - 1);
    }

    private void seedProbe(String repoUrl, boolean up) throws Exception {
        Class<?> probeType = Class.forName("com.gitutility.service.FailoverService$Probe");
        Constructor<?> constructor = probeType.getDeclaredConstructor(boolean.class, Instant.class, String.class);
        constructor.setAccessible(true);
        Object probe = constructor.newInstance(up, Instant.now(), up ? null : "down");
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Object> cache = (ConcurrentHashMap<String, Object>)
                ReflectionTestUtils.getField(failover, "probeCache");
        cache.put(FailoverService.hostOf(repoUrl) + "|", probe);
    }
}
