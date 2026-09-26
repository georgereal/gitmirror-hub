@sim-probe
Feature: Simulation probes
  A probe is a GitHub event for one side of a pair. The clone URL is that side,
  so the mirror treats the event as a change on that repository.

  Scenario: A source branch push is a push on repository A
    Given a pair from "https://github.com/acme/origin" to "https://github.com/acme/mirror"
    When a "branch" "push" probe is sent from the source for ref "feature/probe"
    Then the probe event is "push"
    And the payload repository is "https://github.com/acme/origin"
    And the payload ref is "refs/heads/feature/probe"
    And the payload is not a delete

  Scenario: A destination branch delete is a delete on repository B
    Given a pair from "https://github.com/acme/origin" to "https://github.com/acme/mirror"
    When a "branch" "delete" probe is sent from the destination for ref "feature/probe"
    Then the probe event is "delete"
    And the payload repository is "https://github.com/acme/mirror"
    And the payload ref is "refs/heads/feature/probe"
    And the payload after sha is the zero sha

  Scenario Outline: A ref probe uses the ref namespace for its kind
    Given a pair from "https://github.com/acme/origin" to "https://github.com/acme/mirror"
    When a "<kind>" "push" probe is sent from the source for ref "sim-probe"
    Then the payload ref is "<ref>"

    Examples:
      | kind   | ref                    |
      | branch | refs/heads/sim-probe   |
      | tag    | refs/tags/sim-probe    |
      | note   | refs/notes/sim-probe   |

  Scenario Outline: A pull request probe uses the GitHub action for that operation
    Given a pair from "https://github.com/acme/origin" to "https://github.com/acme/mirror"
    When a "pull_request" "<operation>" probe is sent from the destination for ref "feature/probe"
    Then the probe event is "pull_request"
    And the pull request action is "<action>"
    And the pull request merged flag is "<merged>"

    Examples:
      | operation | action | merged |
      | open      | opened | false  |
      | edit      | edited | false  |
      | close     | closed | false  |
      | merge     | closed | true   |

  Scenario Outline: A release probe uses the GitHub action for that operation
    Given a pair from "https://github.com/acme/origin" to "https://github.com/acme/mirror"
    When a "release" "<operation>" probe is sent from the source for ref "sim-release-v1"
    Then the probe event is "release"
    And the release action is "<action>"

    Examples:
      | operation  | action      |
      | publish    | published   |
      | unpublish  | unpublished |
      | delete     | deleted     |

  Scenario Outline: A commit status probe reports that state
    Given a pair from "https://github.com/acme/origin" to "https://github.com/acme/mirror"
    When a "status" "<operation>" probe is sent from the source for ref "main"
    Then the probe event is "status"
    And the status state is "<state>"

    Examples:
      | operation | state   |
      | success   | success |
      | failure   | failure |
      | pending   | pending |

  Scenario Outline: A check run probe reports that conclusion
    Given a pair from "https://github.com/acme/origin" to "https://github.com/acme/mirror"
    When a "check_run" "<operation>" probe is sent from the destination for ref "main"
    Then the probe event is "check_run"
    And the check run conclusion is "<conclusion>"

    Examples:
      | operation | conclusion |
      | success   | success    |
      | failure   | failure    |

  Scenario: An unknown probe kind is rejected
    Given a pair from "https://github.com/acme/origin" to "https://github.com/acme/mirror"
    When a "wiki" "push" probe is sent from the source for ref "home"
    Then the probe is rejected

  Scenario: An unknown side is rejected
    Given a pair from "https://github.com/acme/origin" to "https://github.com/acme/mirror"
    When a "branch" "push" probe is sent from "both" for ref "main"
    Then the probe is rejected
