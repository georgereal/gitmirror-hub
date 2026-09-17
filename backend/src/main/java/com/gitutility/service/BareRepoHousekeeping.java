package com.gitutility.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Removes macOS AppleDouble ({@code ._filename}) artifacts that exFAT/APFS copies
 * leave beside real git pack files and confuse JGit's pack scanner.
 */
@Slf4j
public final class BareRepoHousekeeping {

    private BareRepoHousekeeping() {
    }

    public static void prepareRepoDirectory(File gitDir) {
        prepareRepoDirectory(gitDir, false);
    }

    /**
     * Call after fetch/push pack I/O on external volumes (exFAT/APFS) where macOS may write
     * new {@code ._pack-*} sidecars beside real pack files mid-operation.
     */
    public static void prepareRepoDirectoryAfterPackIo(File gitDir) {
        prepareRepoDirectory(gitDir, true);
    }

    private static void prepareRepoDirectory(File gitDir, boolean afterPackIo) {
        if (gitDir == null || !gitDir.isDirectory()) {
            return;
        }
        try {
            int removed = purgeAppleDoubleArtifacts(gitDir.toPath());
            if (removed > 0) {
                if (afterPackIo) {
                    log.debug("Removed {} macOS AppleDouble artifact(s) after pack I/O in {}",
                            removed, gitDir.getAbsolutePath());
                } else {
                    log.info("Removed {} macOS AppleDouble artifact(s) from {}", removed, gitDir.getAbsolutePath());
                }
            }
        } catch (IOException e) {
            log.warn("Could not purge AppleDouble artifacts in {}: {}", gitDir.getAbsolutePath(), e.getMessage());
        }
    }

    /**
     * Fast existence probe for any {@code refs/heads/*} without loading thousands of {@link org.eclipse.jgit.lib.Ref}
     * objects (critical for large mirrors like microsoft/vscode on external disks).
     */
    public static boolean hasAnyHeadRefs(File gitDir) throws IOException {
        if (gitDir == null || !gitDir.isDirectory()) {
            return false;
        }
        Path packed = gitDir.toPath().resolve("packed-refs");
        if (Files.isRegularFile(packed)) {
            try (BufferedReader reader = Files.newBufferedReader(packed)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(" refs/heads/")) {
                        return true;
                    }
                }
            }
        }
        Path headsDir = gitDir.toPath().resolve("refs/heads");
        if (Files.isDirectory(headsDir)) {
            try (Stream<Path> entries = Files.list(headsDir)) {
                return entries.anyMatch(p -> {
                    String name = p.getFileName().toString();
                    return !name.startsWith(".");
                });
            }
        }
        return false;
    }

    public static boolean hasAnyHeadRefs(Repository repository) throws IOException {
        if (repository == null) {
            return false;
        }
        return hasAnyHeadRefs(repository.getDirectory());
    }

    public static Set<String> listHeadBranchNames(Repository repository) throws IOException {
        Set<String> names = new TreeSet<>();
        if (repository == null) {
            return names;
        }
        int packedHeadCount = 0;
        File packed = new File(repository.getDirectory(), "packed-refs");
        if (packed.isFile()) {
            try (BufferedReader reader = Files.newBufferedReader(packed.toPath())) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || line.startsWith("#") || line.startsWith("^")) {
                        continue;
                    }
                    int space = line.indexOf(' ');
                    if (space <= 0) {
                        continue;
                    }
                    String ref = line.substring(space + 1).trim();
                    if (ref.startsWith("refs/heads/")) {
                        names.add(ref.substring("refs/heads/".length()));
                        packedHeadCount++;
                    } else if (ref.startsWith("refs/remotes/source/")) {
                        names.add(ref.substring("refs/remotes/source/".length()));
                    } else if (ref.startsWith("refs/remotes/target/")) {
                        names.add(ref.substring("refs/remotes/target/".length()));
                    }
                }
            }
        }
        if (packedHeadCount == 0) {
            Path headsDir = repository.getDirectory().toPath().resolve("refs/heads");
            if (Files.isDirectory(headsDir)) {
                try (Stream<Path> entries = Files.list(headsDir)) {
                    entries.map(p -> p.getFileName().toString())
                            .filter(n -> !n.startsWith("."))
                            .forEach(names::add);
                }
            }
        }
        return names;
    }

    /**
     * Source heads ({@code refs/heads/*} + {@code refs/remotes/source/*}), dest heads
     * ({@code refs/remotes/target/*}), source tags, and dest tags from the last inspect fetch.
     */
    public record PairRefNames(
            Set<String> sourceBranches,
            Set<String> destBranches,
            Set<String> sourceTags,
            Set<String> destTags) {
        public int sourceBranchCount() {
            return sourceBranches != null ? sourceBranches.size() : 0;
        }

        public int destBranchCount() {
            return destBranches != null ? destBranches.size() : 0;
        }

        public int destOnlyBranchCount() {
            if (destBranches == null || destBranches.isEmpty()) {
                return 0;
            }
            if (sourceBranches == null || sourceBranches.isEmpty()) {
                return destBranches.size();
            }
            int count = 0;
            for (String name : destBranches) {
                if (!sourceBranches.contains(name)) {
                    count++;
                }
            }
            return count;
        }
    }

    public static PairRefNames listPairRefNames(Repository repository) throws IOException {
        Set<String> source = new TreeSet<>();
        source.addAll(listRefSuffixesWithPrefix(repository, "refs/heads/"));
        source.addAll(listRefSuffixesWithPrefix(repository, "refs/remotes/source/"));
        Set<String> dest = listRefSuffixesWithPrefix(repository, "refs/remotes/target/");
        Set<String> sourceTags = listRefSuffixesWithPrefix(repository, "refs/tags/");
        sourceTags.addAll(listRefSuffixesWithPrefix(repository, "refs/notes/"));
        Set<String> destTags = listRefSuffixesWithPrefix(repository, "refs/remotes/target-tags/");
        return new PairRefNames(source, dest, sourceTags, destTags);
    }

    /**
     * Lists ref name suffixes under {@code prefix} via packed-refs scan (e.g. {@code refs/remotes/target/} → branch names).
     */
    public static Set<String> listRefSuffixesWithPrefix(Repository repository, String prefix) throws IOException {
        Set<String> names = new TreeSet<>();
        if (repository == null || prefix == null || prefix.isBlank()) {
            return names;
        }
        File packed = new File(repository.getDirectory(), "packed-refs");
        if (packed.isFile()) {
            try (BufferedReader reader = Files.newBufferedReader(packed.toPath())) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || line.startsWith("#") || line.startsWith("^")) {
                        continue;
                    }
                    int space = line.indexOf(' ');
                    if (space <= 0) {
                        continue;
                    }
                    String ref = line.substring(space + 1).trim();
                    if (ref.startsWith(prefix)) {
                        names.add(ref.substring(prefix.length()));
                    }
                }
            }
        }
        Path looseDir = repository.getDirectory().toPath().resolve(prefix);
        try {
            if (Files.isDirectory(looseDir)) {
                collectLooseRefSuffixes(looseDir, "", names);
            }
        } catch (Exception e) {
            // Packed-refs already counted; loose walks on large mirrors (esp. external disks) can fail.
            log.debug("Loose ref scan incomplete for {}: {}", prefix, e.getMessage());
        }
        return names;
    }

    private static void collectLooseRefSuffixes(Path dir, String relative, Set<String> names) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                String path = relative.isEmpty() ? name : relative + "/" + name;
                if (Files.isDirectory(entry)) {
                    collectLooseRefSuffixes(entry, path, names);
                } else {
                    names.add(path);
                }
            }
        }
    }

    public static int countPackedRefsWithPrefix(Repository repository, String prefix) throws IOException {
        if (repository == null || prefix == null) {
            return 0;
        }
        File packed = new File(repository.getDirectory(), "packed-refs");
        if (!packed.isFile()) {
            return 0;
        }
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(packed.toPath())) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("^")) {
                    continue;
                }
                int space = line.indexOf(' ');
                if (space <= 0) {
                    continue;
                }
                String ref = line.substring(space + 1).trim();
                if (ref.startsWith(prefix)) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Unique tip commit IDs for local heads and {@code refs/remotes/source/*} without loading thousands of
     * {@link org.eclipse.jgit.lib.Ref} objects (used by parallel LFS pointer discovery).
     */
    public static Set<ObjectId> uniqueBranchTipObjectIds(Repository repository) throws IOException {
        Set<ObjectId> tips = new LinkedHashSet<>();
        if (repository == null) {
            return tips;
        }
        int packedHeadCount = collectTipObjectIdsFromPackedRefs(repository.getDirectory(), tips);
        if (packedHeadCount == 0) {
            collectLooseHeadTipObjectIds(repository.getDirectory().toPath().resolve("refs/heads"), tips);
        }
        return tips;
    }

    private static int collectTipObjectIdsFromPackedRefs(File gitDir, Set<ObjectId> tips) throws IOException {
        if (gitDir == null || tips == null) {
            return 0;
        }
        File packed = new File(gitDir, "packed-refs");
        if (!packed.isFile()) {
            return 0;
        }
        int headCount = 0;
        try (BufferedReader reader = Files.newBufferedReader(packed.toPath())) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("^")) {
                    continue;
                }
                int space = line.indexOf(' ');
                if (space <= 0) {
                    continue;
                }
                String sha = line.substring(0, space).trim();
                String ref = line.substring(space + 1).trim();
                if (!ref.startsWith("refs/heads/") && !ref.startsWith("refs/remotes/source/")) {
                    continue;
                }
                try {
                    tips.add(ObjectId.fromString(sha));
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (ref.startsWith("refs/heads/")) {
                    headCount++;
                }
            }
        }
        return headCount;
    }

    private static void collectLooseHeadTipObjectIds(Path headsDir, Set<ObjectId> tips) throws IOException {
        if (headsDir == null || !Files.isDirectory(headsDir) || tips == null) {
            return;
        }
        try (Stream<Path> entries = Files.list(headsDir)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                String sha = Files.readString(entry).trim();
                if (sha.length() >= 40) {
                    try {
                        tips.add(ObjectId.fromString(sha.substring(0, 40)));
                    } catch (IllegalArgumentException ignored) {
                        // symbolic ref or invalid — skip
                    }
                }
            }
        }
    }

    static int purgeAppleDoubleArtifacts(Path gitDir) throws IOException {
        int removed = 0;
        if (Files.isDirectory(gitDir)) {
            removed += deleteAppleDoubleFiles(gitDir);
        }
        Path packDir = gitDir.resolve("objects/pack");
        if (Files.isDirectory(packDir)) {
            removed += deleteAppleDoubleFiles(packDir);
        }
        Path objectsDir = gitDir.resolve("objects");
        if (Files.isDirectory(objectsDir)) {
            try (Stream<Path> children = Files.list(objectsDir)) {
                for (Path child : children.filter(Files::isDirectory).toList()) {
                    removed += deleteAppleDoubleFiles(child);
                }
            }
        }
        return removed;
    }

    private static int deleteAppleDoubleFiles(Path directory) throws IOException {
        int removed = 0;
        try (Stream<Path> entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (name.startsWith("._")) {
                    Files.deleteIfExists(entry);
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * Success values of {@link org.eclipse.jgit.lib.RefUpdate.Result} after a fetch —
     * everything else (LOCK_FAILURE, IO_FAILURE, REJECTED, NOT_ATTEMPTED, AWAITING_REPORT)
     * means the local tracking ref was NOT updated and still points at the stale tip.
     */
    static boolean isTrackingRefSuccess(org.eclipse.jgit.lib.RefUpdate.Result result) {
        return result == org.eclipse.jgit.lib.RefUpdate.Result.NEW
                || result == org.eclipse.jgit.lib.RefUpdate.Result.FAST_FORWARD
                || result == org.eclipse.jgit.lib.RefUpdate.Result.FORCED
                || result == org.eclipse.jgit.lib.RefUpdate.Result.NO_CHANGE;
    }

    /**
     * JGit's {@code git.fetch().call()} returns normally even when individual per-ref
     * writes fail ({@code .lock} contention, I/O, rejection); a successful call()
     * therefore hides silent per-ref failures and downstream readers keep consuming
     * stale local refs indefinitely.
     *
     * Classifies each tracking ref update, logs a WARN per failed ref (ref name, old/new
     * object ids, result code) through the caller-supplied logger (or SLF4J when null),
     * and returns the list of failed ref names.
     */
    public static List<String> logFailedTrackingRefUpdates(
            org.eclipse.jgit.transport.FetchResult fetchResult,
            String contextLabel,
            java.util.function.Consumer<String> auditLogger) {
        List<String> failedRefs = new ArrayList<>();
        if (fetchResult == null) {
            return failedRefs;
        }
        for (org.eclipse.jgit.transport.TrackingRefUpdate update : fetchResult.getTrackingRefUpdates()) {
            org.eclipse.jgit.lib.RefUpdate.Result result = update.getResult();
            if (isTrackingRefSuccess(result)) {
                continue;
            }
            String refName = update.getLocalName() != null && !update.getLocalName().isBlank()
                    ? update.getLocalName()
                    : update.getRemoteName();
            failedRefs.add(refName);
            String message = contextLabel + ": local ref update failed for '" + refName
                    + "' (" + result + "), old=" + update.getOldObjectId()
                    + " new=" + update.getNewObjectId()
                    + " — bare-repo tracking ref left at the stale tip; subsequent diffs will read it until the next successful write.";
            if (auditLogger != null) {
                auditLogger.accept(message);
            } else {
                log.warn(message);
            }
        }
        if (!failedRefs.isEmpty()) {
            log.warn("{}: {} tracking ref update(s) failed silently — refs {}",
                    contextLabel, failedRefs.size(), failedRefs);
        }
        return failedRefs;
    }
}
