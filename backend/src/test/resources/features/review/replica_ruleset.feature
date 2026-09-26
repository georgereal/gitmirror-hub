@replica-ruleset
Feature: Replica read-only ruleset
  The replica ruleset makes a GitHub or GHES side read-only for people.
  The mirror App remains the bypass actor. GitLab and Bitbucket have no ruleset API.

  Scenario: Lock replica creates the ruleset and enforces it
    Given a GitHub pair whose replica has no ruleset
    When the operator locks the replica
    Then a ruleset named "gitmirror-replica-readonly" exists on the replica
    And that ruleset enforcement is "active"
    And the mirror App is the bypass actor

  Scenario: Unlock replica disables enforcement and keeps the ruleset
    Given the replica ruleset enforcement is "active"
    When the operator unlocks the replica
    Then that ruleset enforcement is "disabled"
    And the ruleset still exists

  Scenario: Swap primary locks the old primary before it opens the old replica
    Given repository A is the writable primary and repository B is locked
    When the operator swaps the primary
    Then repository A is locked before repository B is unlocked

  Scenario: A linked pair cannot be read-only on both sides
    Given repository A is locked
    When the operator locks repository B
    Then repository B is locked
    And repository A is unlocked

  Scenario: GitLab and Bitbucket pairs do not get a ruleset
    Given a GitLab pair
    When the operator locks the replica
    Then no replica ruleset is written
