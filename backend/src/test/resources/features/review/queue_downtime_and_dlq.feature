@simulation @queue-dlq
Feature: Queue downtime and dead letter
  Paused consumers leave work buffered. A job that exhausts its attempts is dead-lettered.
  A dark disaster-recovery host parks the event instead. That park is a separate feature.

  Scenario: Work accepted while consumers are paused stays queued
    Given execution consumers are paused
    When a synthetic push is emitted on branch "feature/buffered" with sha "abc123"
    Then the synthetic job is queued
    And the deferred sync is not dispatched

  Scenario: Resuming consumers drains the queued job
    Given execution consumers are paused
    And a synthetic push is queued on branch "feature/buffered"
    When the operator resumes consumers
    Then consumers are running
    And the queued job is eligible to run

  Scenario: A destination outage exhausts attempts and dead-letters the job
    Given the job destination is simulated down
    And a sync job has used its last attempt
    When that job fails with the destination outage
    Then the job status is "DEAD_LETTERED"
    And the message is not requeued on the execution lane

  Scenario: Redrive puts a dead-lettered job back on its lane after the outage clears
    Given a job is "DEAD_LETTERED" for ref "refs/heads/main"
    And the destination outage simulation is turned off
    When the operator redrives the dead letter queue
    Then the job is queued on the incremental lane

  # Broker retry spacing in code is 3s, then 6s, then 12s, capped at 30s.
  # INSTRUCTIONS recipe 2 still says 1.5s, 3s, and 6s. Confirm which contract to lock.
