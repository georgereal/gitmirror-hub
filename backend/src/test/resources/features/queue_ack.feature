@queue
Feature: Queue acknowledgement
  Jobs that must not run are skipped so the broker can acknowledge them.

  Scenario: A cancelled job is acknowledged without running git
    Given job "20" for pair "vscode" is cancelled
    When the consumer receives that job
    Then git sync does not run
    And the incremental lane has no unacked work

  Scenario: A missing job is acknowledged without running git
    Given job "99" does not exist
    When the consumer receives that job
    Then git sync does not run
    And the incremental lane has no unacked work
