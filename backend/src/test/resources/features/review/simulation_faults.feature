@simulation
Feature: Simulation faults beyond an outage
  The lab can also inject a rate limit and a delay. Origin and destination outages are already covered.

  Scenario: A simulated rate limit fails the sync
    Given the provider rate limit is simulated
    When a sync checks injected faults
    Then the sync fails because the provider rate limit was exceeded

  Scenario: A configured delay is waited before the fault check returns
    Given an artificial delay of 50 milliseconds
    And no outage is simulated
    When a sync checks injected faults
    Then the fault check returns without error
    And at least 50 milliseconds elapsed

  Scenario: Clearing the rate limit lets the next check pass
    Given the provider rate limit is simulated
    And the provider rate limit simulation is turned off
    When a sync checks injected faults
    Then the fault check returns without error
