@review
Feature: Disaster recovery lane
  Activate DR is one control for every pair that shares a source host and a destination host.
  A dark host parks incremental events. It does not dead-letter them.

  Scenario: Activate DR unlocks the live replica and points incrementals at the demoted source
    Given a lane from host "github.com" to host "ghes.example"
    And the source host is down
    When the operator activates disaster recovery on that lane
    Then the live replica is writable
    And incremental sync points at the demoted source
    And the read-only lock on the demoted host stays pending until that host answers

  Scenario: A connection failure while the lane is in disaster recovery is parked
    Given disaster recovery is active on the lane
    And the demoted host refuses the connection
    When an incremental event arrives
    Then the event is parked
    And the event is not dead-lettered

  Scenario: Fail back stays disabled until both providers are up
    Given disaster recovery is active on the lane
    And the demoted host is still down
    Then fail back is disabled

  Scenario: Fail back restores the original direction and drains parked events
    Given disaster recovery is active on the lane
    And both providers are up
    And the read-only lock has been applied
    When the operator fails the lane back
    Then the original source is writable
    And the side that was writable during disaster recovery is locked
    And parked events are drained
