package com.gitutility.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitLfsSyncServiceTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void extractOidsPresentOnTarget_countsObjectsWithoutUploadAction() throws Exception {
    String json = """
        {
          "objects": [
            {"oid": "aaa", "actions": {}},
            {"oid": "bbb", "actions": {"upload": {"href": "https://upload"}}},
            {"oid": "ccc", "actions": {"upload": {}}}
          ]
        }
        """;
    Set<String> present = GitLfsSyncService.extractOidsPresentOnTarget(objectMapper.readTree(json));
    assertEquals(2, present.size());
    assertTrue(present.contains("aaa"));
    assertTrue(present.contains("ccc"));
  }

  @Test
  void lfsSyncStatsCountsAlreadyPresentAsSynced() {
    GitLfsSyncService.LfsSyncStats stats = new GitLfsSyncService.LfsSyncStats(98, 0, 0, 98);
    assertEquals(98, stats.count());
    assertEquals(98, stats.alreadyPresent());
    assertEquals(0, stats.failed());
  }

  @Test
  void partition_splitsEvenly() {
    var batches = GitLfsSyncService.partition(java.util.List.of(1, 2, 3, 4, 5), 2);
    assertEquals(3, batches.size());
    assertEquals(2, batches.get(0).size());
    assertEquals(2, batches.get(1).size());
    assertEquals(1, batches.get(2).size());
  }
}
