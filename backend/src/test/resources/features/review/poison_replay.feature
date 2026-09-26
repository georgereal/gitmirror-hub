@poison-replay
Feature: Poison replay and webhook retention
  A replay runs the stored event on this process. The row becomes replayed only after that run finishes.
  An unreplayed poison row has no expiry.

  Scenario: A finished replay marks the row replayed and gives it an expiry
    Given a stored poison event for "https://github.com/acme/origin.git"
    And replaying that event succeeds
    When the operator replays stored poison
    Then the poison row is "KAFKA_POISON_REPLAYED"
    And the poison row expires 7 days after it was received
    And the stored event was processed on this process
    And the event was not published back to the bus

  Scenario: A failed replay stays poison and has no expiry
    Given a stored poison event for "https://github.com/acme/origin.git"
    And replaying that event fails
    When the operator replays stored poison
    Then the poison row is "KAFKA_POISON"
    And the poison row has no expiry
    And the event was not published back to the bus

  Scenario: Stamping a poison row clears any expiry
    Given an unreplayed poison row that already has an expiry
    When that poison row is stamped
    Then the poison row has no expiry

  Scenario: Startup backfill stamps discarded rows and leaves poison unset
    Given a discarded webhook row with no expiry
    And an unreplayed poison row
    When startup stamps missing webhook expiry
    Then the discarded row has an expiry
    And the poison row has no expiry
