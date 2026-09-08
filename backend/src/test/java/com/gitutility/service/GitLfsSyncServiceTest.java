package com.gitutility.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class GitLfsSyncServiceTest {

  private final ObjectMapper objectMapper = JsonMapper.builder().build();

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

  @Test
  void discoveryChunkSizeCapsAtConfiguredThreads() {
    assertEquals(1, GitLfsSyncService.discoveryChunkSize(4, 1));
    assertEquals(4, GitLfsSyncService.discoveryChunkSize(4, 400));
    assertEquals(1, GitLfsSyncService.discoveryChunkSize(0, 10));
  }

  @Test
  void discoverLfsPointersWaitsOnTinyPoolInsteadOfRejecting(@TempDir Path tempDir) throws Exception {
    StringJoiner packed = new StringJoiner("\n", "# pack\n", "\n");
    for (int i = 1; i <= 12; i++) {
      packed.add(String.format("%040x refs/heads/b%d", i, i));
    }
    Files.writeString(tempDir.resolve("packed-refs"), packed.toString());

    ThreadPoolExecutor discovery = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
            new ThreadPoolExecutor.AbortPolicy());
    ExecutorService transfer = Executors.newSingleThreadExecutor();
    GitLfsSyncService service = new GitLfsSyncService(
            new RestTemplate(),
            mock(GitHubAuthService.class),
            mock(SyncCheckpointService.class),
            null,
            discovery,
            transfer);
    ReflectionTestUtils.setField(service, "lfsDiscoveryThreads", 1);

    try (var repo = new FileRepository(tempDir.toFile())) {
      assertDoesNotThrow(() -> service.discoverLfsPointers(repo));
    } finally {
      discovery.shutdownNow();
      transfer.shutdownNow();
    }
  }
}
