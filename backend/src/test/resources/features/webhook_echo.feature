@webhook
Feature: Webhook echo
  Pushes the hub already wrote are not queued again, and new origin work is.

  Scenario: A mirror push of a hub-written tip is skipped
    Given a bidirectional pair "vscode" mirroring "https://github.com/microsoft/vscode" to "https://github.com/acme/mirror-dest"
    And the mirror tip was written by the hub
    When a push arrives from the mirror on branch "feat/rag-workflow" at sha "388dc77aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" with message "feat: rag workflow"
    Then the webhook is skipped as "LOOP_DETECTED_SYSTEM_ECHO"
    And no sync job is queued

  Scenario: A new origin commit is accepted and queued
    Given a bidirectional pair "vscode" mirroring "https://github.com/microsoft/vscode" to "https://github.com/acme/mirror-dest"
    When a push arrives from the origin on branch "main" at sha "sourcecommit111111111111111111111111111" with message "feat: real commit subject"
    Then the webhook is accepted and queued

  Scenario: An origin branch delete is queued
    Given a bidirectional pair "OpenMAIC" mirroring "https://github.com/THU-MAIC/OpenMAIC" to "https://github.com/acme/mirror-dest"
    When a branch delete arrives from the origin for "feature-x"
    Then the webhook is accepted and queued

  Scenario: A mirror branch delete the hub already wrote is skipped
    Given a bidirectional pair "OpenMAIC" mirroring "https://github.com/THU-MAIC/OpenMAIC" to "https://github.com/acme/mirror-dest"
    And the mirror delete was written by the hub
    When a branch delete arrives from the mirror for "feature-x"
    Then the webhook is skipped as "LOOP_DETECTED_SYSTEM_ECHO"
    And no sync job is queued

  Scenario: A dependabot branch on the mirror is ignored
    Given a bidirectional pair "vscode" mirroring "https://github.com/microsoft/vscode" to "https://github.com/acme/mirror-dest"
    And the pair is a public origin with a private mirror
    When a push arrives from the mirror on branch "dependabot/github_actions/actions/cache/save-6.1.0" at sha "9e42f862f4fbcfa92a3b700e71c7238544a6cdd5" with message "bump actions/cache"
    Then the webhook is skipped as "EPHEMERAL_REF_IGNORED"
    And no sync job is queued

  Scenario: A new commit on the mirror default branch is queued
    Given a bidirectional pair "vscode" mirroring "https://github.com/microsoft/vscode" to "https://github.com/acme/mirror-dest"
    When a push arrives from the mirror on branch "main" at sha "05b3b4d30c2a8164e70f4379d2be541f3a657e8c" with message "Merge pull request #1"
    Then the webhook is accepted and queued
