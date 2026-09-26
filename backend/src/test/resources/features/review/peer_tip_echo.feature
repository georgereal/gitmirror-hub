@review
Feature: Peer tip echo
  An inbound push or delete is an echo only when the other repository already has that result.
  A ledger row is not this check. An unknown peer is not an echo.

  Scenario: A push is an echo when the peer already advertises that tip
    Given the peer lookup succeeded
    And the peer advertises sha "abc123"
    When a push arrives at sha "ABC123"
    Then the push is an echo

  Scenario: A push is not an echo when the peer tip is different
    Given the peer lookup succeeded
    And the peer advertises sha "abc123"
    When a push arrives at sha "def456"
    Then the push is not an echo

  Scenario: A push is not an echo when the peer could not be read
    Given the peer lookup failed
    When a push arrives at sha "abc123"
    Then the push is not an echo

  Scenario: A delete sha is not a push echo
    Given the peer lookup succeeded
    And the peer advertises sha "abc123"
    When a push arrives at the zero sha
    Then the push is not an echo

  Scenario: A delete is an echo when the peer ref is already gone
    Given the peer lookup succeeded
    And the peer does not advertise the ref
    Then the delete is an echo

  Scenario: A delete is not an echo when the peer still has the ref
    Given the peer lookup succeeded
    And the peer still advertises the ref
    Then the delete is not an echo

  Scenario: A delete is not an echo when the peer could not be read
    Given the peer lookup failed
    Then the delete is not an echo
