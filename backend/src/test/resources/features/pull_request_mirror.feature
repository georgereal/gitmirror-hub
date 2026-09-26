@pull-request
Feature: Pull request mirror rules
  Fork heads and trunk names stay off the destination default branch,
  and closed pull requests stay out of the open mirror.

  Scenario: A fork pull request named main is stored on a synthetic branch
    When a replica head is chosen for pull request 65 with head "main" from a fork
    Then the replica head branch is "fork-pr-65"

  Scenario: A same-repository feature branch keeps its name
    When a replica head is chosen for pull request 12 with head "feature-x" from the same repository
    Then the replica head branch is "feature-x"

  Scenario: A same-repository pull request named main does not reuse the trunk
    When a replica head is chosen for pull request 12 with head "main" from the same repository
    Then the replica head branch is "fork-pr-12"

  Scenario: Opening a pull request is not a close
    Then "opened" is not a pull request close action
    And "opened" is a pull request open action

  Scenario Outline: Close actions are recognized
    Then "<action>" is a pull request close action

    Examples:
      | action   |
      | closed   |
      | merged   |
      | declined |

  Scenario Outline: Closed pull requests are not the open mirror
    Then pull request state "<state>" is <openness>

    Examples:
      | state  | openness |
      | closed | closed   |
      | merged | closed   |
      | open   | open     |
