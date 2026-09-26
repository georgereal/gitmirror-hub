@mirror-app
Feature: Mirror App actor
  A pull request event sent by the mirror GitHub App is the App's own write.
  It is discarded and not synced again.

  Scenario: A pull request opened by the mirror App is discarded
    Given the inbound credential bot login is "gitmirror[bot]"
    When a pull request opened event arrives from sender "gitmirror[bot]"
    Then the event is discarded as "MIRROR_APP_PUSH"
    And the pull request is not synced

  Scenario: A pull request opened by a person is not an App echo
    Given the inbound credential bot login is "gitmirror[bot]"
    When a pull request opened event arrives from sender "georgereal"
    Then the event is not discarded as "MIRROR_APP_PUSH"
