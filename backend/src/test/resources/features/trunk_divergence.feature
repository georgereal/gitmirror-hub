@trunk
Feature: Trunk divergence
  A diverged branch is isolated unless the pair adopts the side that is ahead,
  the operator overwrites from the origin, or the pair is unidirectional.

  Scenario: A diverged bidirectional trunk is isolated
    Given a bidirectional pair
    And the destination tip is not a fast-forward of the origin
    And the operator has not requested an overwrite
    And the trunk conflict policy is "isolate"
    When the trunk push is decided
    Then the trunk action is "isolate"

  Scenario: Origin-wins force-pushes a diverged trunk
    Given a bidirectional pair
    And the destination tip is not a fast-forward of the origin
    And the operator has not requested an overwrite
    And the trunk conflict policy is "origin-wins"
    When the trunk push is decided
    Then the trunk action is "force"

  Scenario: Fail-job skips a diverged trunk
    Given a bidirectional pair
    And the destination tip is not a fast-forward of the origin
    And the operator has not requested an overwrite
    And the trunk conflict policy is "fail-job"
    When the trunk push is decided
    Then the trunk action is "skip"

  Scenario: An operator overwrite force-pushes a diverged trunk
    Given a bidirectional pair
    And the destination tip is not a fast-forward of the origin
    And the operator has requested an overwrite
    And the trunk conflict policy is "isolate"
    When the trunk push is decided
    Then the trunk action is "force"

  Scenario: A fast-forward trunk is pushed
    Given a bidirectional pair
    And the destination tip is a fast-forward of the origin
    And the trunk conflict policy is "isolate"
    When the trunk push is decided
    Then the trunk action is "push"

  Scenario: A bidirectional destination that is ahead adopts that tip
    Given a bidirectional pair
    And the destination contains the origin tip
    And the operator has not requested an overwrite
    And the trunk conflict policy is "isolate"
    When the trunk push is decided
    Then the trunk action is "adopt-dest"

  Scenario: A unidirectional pair overwrites a diverged trunk
    Given a unidirectional pair
    And the destination contains the origin tip
    And the trunk conflict policy is "isolate"
    When the trunk push is decided
    Then the trunk action is "force"

  Scenario: Identical tips do not push
    Given a bidirectional pair
    And both sides are on the same tip
    When the ancestry update is decided
    Then the trunk action is "no-push"

  Scenario: A matching before-sha still isolates when the histories have diverged
    Given a bidirectional pair
    And the webhook before-sha matches the current tip
    When the ancestry update is decided
    Then the trunk action is "isolate"

  Scenario: A zero after-sha is a branch delete
    Given an incremental event for branch "test/sync-probe-2" with after sha "0000000000000000000000000000000000000000"
    Then the event is a source branch delete

  Scenario: A real after-sha is not a branch delete
    Given an incremental event for branch "test/sync-probe-2" with after sha "c66a7d0aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    Then the event is not a source branch delete

  Scenario Outline: A conflict pull request opens on the repository whose tip was kept
    When the kept tip is on the "<side>"
    Then the conflict pull request opens on "<repo>"

    Examples:
      | side   | repo                         |
      | mirror | https://mirror.example/repo |
      | origin | https://source.example/repo |
