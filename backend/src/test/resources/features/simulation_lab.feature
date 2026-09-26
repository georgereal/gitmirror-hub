@simulation
Feature: Simulation lab
  Operators can pause consumers and inject repository faults without a live outage.

  Scenario: In-memory messaging follows the pause flag
    Given in-memory messaging
    Then the sync listeners report running
    When the operator pauses consumers
    Then consumers are paused
    And the sync listeners report stopped
    When the operator resumes consumers
    Then consumers are running
    And the sync listeners report running

  Scenario: Rabbit with no listener containers reports the worker stopped
    Given rabbit messaging with no listener containers
    Then the sync listeners report stopped

  Scenario: Pausing consumers stops both sync listeners
    Given rabbit messaging with full and incremental listeners running
    When the operator pauses consumers
    Then the full listener is stopped
    And the incremental listener is stopped
    And consumers are paused
    When the operator resumes consumers
    Then the full listener is started
    And the incremental listener is started

  Scenario: A simulated destination outage fails the sync
    Given the destination repository is simulated down
    When a sync checks injected faults
    Then the sync fails because the destination is unreachable

  Scenario: A simulated origin outage fails the sync
    Given the origin repository is simulated down
    When a sync checks injected faults
    Then the sync fails because the origin is down

  Scenario: A synthetic push is queued for the pair
    Given a mirror pair "test-pair" from "https://github.com/a/repo-a.git" to "https://github.com/b/repo-b.git"
    When a synthetic push is emitted on branch "feature/test-queue" with sha "abcdef123"
    Then the synthetic job is queued
    And the sync queue receives that push
