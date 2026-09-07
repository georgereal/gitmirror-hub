package com.gitutility.service;

import com.gitutility.model.dto.StorageStatusResponse;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.enums.StorageTier;
import com.gitutility.repository.RepoMappingRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class StorageTieringService {

    private final RepoMappingRepository repoMappingRepository;

    @Value("${git-utility.storage.local-dir:${git-utility.workspace-dir:/tmp/git-utility-mirrors}}")
    private String localDir;

    @Value("${git-utility.storage.nas-dir:/tmp/git-utility-nas-mirrors}")
    private String nasDir;

    @Value("${git-utility.storage.max-disk-quota-mb:51200}")
    private long maxDiskQuotaMb;

    @Value("${git-utility.storage.max-cached-repos:1000}")
    private int maxCachedRepos;

    @Value("${git-utility.storage.retention-hours:72}")
    private int retentionHours;

    private final Map<Long, Instant> lastAccessTimes = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(Paths.get(localDir));
            Files.createDirectories(Paths.get(nasDir));
            log.info("StorageTieringService initialized. Local Storage: '{}', NAS Storage: '{}'", localDir, nasDir);
        } catch (IOException e) {
            log.warn("Notice initializing storage directories: {}", e.getMessage());
        }
    }

    public synchronized void updateStorageParameters(String localDir, String nasDir, long maxQuotaMb, int maxCached, int retentionHrs) {
        if (localDir != null && !localDir.isBlank()) {
            this.localDir = localDir.trim();
        }
        if (nasDir != null && !nasDir.isBlank()) {
            this.nasDir = nasDir.trim();
        }
        if (maxQuotaMb > 0) {
            this.maxDiskQuotaMb = maxQuotaMb;
        }
        if (maxCached > 0) {
            this.maxCachedRepos = maxCached;
        }
        if (retentionHrs > 0) {
            this.retentionHours = retentionHrs;
        }

        try {
            Files.createDirectories(Paths.get(this.localDir));
            Files.createDirectories(Paths.get(this.nasDir));
        } catch (Exception ignored) {}

        log.info("Updated StorageTiering parameters: localDir={}, nasDir={}, maxQuotaMb={}, maxCached={}, retentionHours={}",
                this.localDir, this.nasDir, this.maxDiskQuotaMb, this.maxCachedRepos, this.retentionHours);
    }

    /**
     * Resolves the bare repository path on disk based on the configured StorageTier.
     */
    public File resolveRepoDirectory(Long mappingId, StorageTier tier) {
        if (tier == null) {
            tier = StorageTier.AUTO_LRU;
        }

        lastAccessTimes.put(mappingId, Instant.now());

        switch (tier) {
            case NAS_MOUNT:
                File nasBase = new File(nasDir);
                if (!nasBase.exists()) nasBase.mkdirs();
                return new File(nasBase, "pair-" + mappingId + ".git");

            case EPHEMERAL_STREAM:
                try {
                    Path tempPath = Files.createTempDirectory("git-ephemeral-pair-" + mappingId + "-");
                    return tempPath.toFile();
                } catch (IOException e) {
                    log.warn("Failed to create temp directory for ephemeral tier, falling back to local: {}", e.getMessage());
                    return new File(new File(localDir), "pair-" + mappingId + ".git");
                }

            case HOT_PERSISTENT:
            case AUTO_LRU:
            default:
                File localBase = new File(localDir);
                if (!localBase.exists()) localBase.mkdirs();
                return new File(localBase, "pair-" + mappingId + ".git");
        }
    }

    /**
     * Cleans up an ephemeral repository after synchronization completes.
     */
    public void cleanupEphemeralRepo(File repoDir) {
        if (repoDir == null || !repoDir.exists()) return;
        if (!repoDir.getName().startsWith("git-ephemeral-pair-")) {
            return; // Safety guard: only delete temp ephemeral folders
        }

        try {
            deleteDirectoryRecursively(repoDir.toPath());
            log.info("Ephemeral bare repository pruned successfully: {}", repoDir.getAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to prune ephemeral repository {}: {}", repoDir.getAbsolutePath(), e.getMessage());
        }
    }

    /**
     * Periodic and quota-based LRU eviction for AUTO_LRU repositories.
     */
    @Scheduled(cron = "0 0 * * * *") // Every hour
    public void checkAndEvictLruCache() {
        try {
            long totalBytes = calculateDirectorySize(new File(localDir));
            long maxBytes = maxDiskQuotaMb * 1024 * 1024;

            List<RepoMapping> allMappings = repoMappingRepository.findAll();
            Map<Long, RepoMapping> mappingMap = new HashMap<>();
            for (RepoMapping m : allMappings) {
                mappingMap.put(m.getId(), m);
            }

            File localBase = new File(localDir);
            File[] files = localBase.listFiles((dir, name) -> name.startsWith("pair-") && name.endsWith(".git"));
            if (files == null) return;

            // Sort files by last access time ascending (oldest first)
            List<File> candidates = new ArrayList<>(Arrays.asList(files));
            candidates.sort(Comparator.comparingLong(f -> {
                Long mId = parseMappingIdFromFilename(f.getName());
                Instant lastAccess = lastAccessTimes.get(mId);
                return lastAccess != null ? lastAccess.toEpochMilli() : f.lastModified();
            }));

            // Evict if over quota or over max repos count
            for (File file : candidates) {
                if (totalBytes <= maxBytes && files.length <= maxCachedRepos) {
                    break;
                }

                Long mId = parseMappingIdFromFilename(file.getName());
                RepoMapping mapping = mappingMap.get(mId);

                // HOT_PERSISTENT repos are never evicted
                if (mapping != null && mapping.getStorageTier() == StorageTier.HOT_PERSISTENT) {
                    continue;
                }

                long size = calculateDirectorySize(file);
                deleteDirectoryRecursively(file.toPath());
                totalBytes -= size;
                lastAccessTimes.remove(mId);
                log.info("LRU Evicted cold repository pair #{}: {} (freed {})", mId, file.getName(), formatBytes(size));
            }
        } catch (Exception e) {
            log.warn("Error running LRU cache eviction: {}", e.getMessage());
        }
    }

    public StorageStatusResponse getStorageStatus() {
        long localBytes = calculateDirectorySize(new File(localDir));
        long nasBytes = calculateDirectorySize(new File(nasDir));
        long maxBytes = maxDiskQuotaMb * 1024 * 1024;

        List<RepoMapping> mappings = repoMappingRepository.findAll();
        int hot = 0;
        int autoLru = 0;
        int ephemeral = 0;
        int nas = 0;

        for (RepoMapping m : mappings) {
            StorageTier tier = m.getStorageTier() != null ? m.getStorageTier() : StorageTier.AUTO_LRU;
            switch (tier) {
                case HOT_PERSISTENT -> hot++;
                case AUTO_LRU -> autoLru++;
                case EPHEMERAL_STREAM -> ephemeral++;
                case NAS_MOUNT -> nas++;
            }
        }

        File localBase = new File(localDir);
        File[] cachedFiles = localBase.listFiles((dir, name) -> name.startsWith("pair-") && name.endsWith(".git"));
        int cachedCount = cachedFiles != null ? cachedFiles.length : 0;

        double percent = maxBytes > 0 ? ((double) localBytes / maxBytes) * 100.0 : 0.0;

        return StorageStatusResponse.builder()
                .localDirectory(localDir)
                .localUsedBytes(localBytes)
                .localUsedFormatted(formatBytes(localBytes))
                .nasDirectory(nasDir)
                .nasUsedBytes(nasBytes)
                .nasUsedFormatted(formatBytes(nasBytes))
                .totalCachedRepos(cachedCount)
                .hotReposCount(hot)
                .autoLruReposCount(autoLru)
                .ephemeralReposCount(ephemeral)
                .nasReposCount(nas)
                .maxDiskQuotaBytes(maxBytes)
                .maxDiskQuotaFormatted(formatBytes(maxBytes))
                .quotaUsedPercent(Math.round(percent * 10.0) / 10.0)
                .build();
    }

    private Long parseMappingIdFromFilename(String name) {
        try {
            String num = name.replace("pair-", "").replace(".git", "");
            return Long.parseLong(num);
        } catch (Exception e) {
            return -1L;
        }
    }

    private long calculateDirectorySize(File dir) {
        if (dir == null || !dir.exists()) return 0L;
        try {
            final long[] size = {0};
            Files.walkFileTree(dir.toPath(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    size[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }
            });
            return size[0];
        } catch (Exception e) {
            return 0L;
        }
    }

    private void deleteDirectoryRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}
