@release
Feature: Release and status echo
  Metadata the hub already wrote on the other repository is not written back.

  Scenario: A release delete is an echo when the peer tag is already gone
    Given the other repository was looked up
    And the tag is already gone on the peer
    Then the release delete is an echo

  Scenario: A release delete is not an echo when the peer still has the tag
    Given the other repository was looked up
    And the tag is still present on the peer
    Then the release delete is not an echo

  Scenario: A release delete is not an echo when the peer lookup failed
    Given the other repository lookup failed
    Then the release delete is not an echo

  Scenario: Unpublish is an echo when the peer release is already a draft
    Given the other repository was looked up
    And the peer release is present and a draft
    Then the release unpublish is an echo

  Scenario: Unpublish is not an echo when the peer release is still published
    Given the other repository was looked up
    And the peer release is present and published
    Then the release unpublish is not an echo

  Scenario: Repeating the recorded status is an echo
    Given the hub knows the peer status
    When status "success" is written and the peer status is "SUCCESS"
    Then the status write is an echo

  Scenario: A changed status is not an echo
    Given the hub knows the peer status
    When status "pending" is written and the peer status is "success"
    Then the status write is not an echo

  Scenario: An unknown status is not an echo
    Given the hub does not know the peer status
    When status "success" is written and the peer status is "success"
    Then the status write is not an echo

  Scenario: One published release is kept when drafts share the tag
    Given a draft release and a published release share tag "meta-sync-v1"
    Then the kept release is the published one
    And the distinct release tag count is 1
