package dev.compixel.sync.session;

/** A binding's progress: revision, state batches sent or received, and why it failed if it did. */
public record SyncStatistics(
        SyncStatus status,
        long revision,
        long batches,
        long bytes,
        int largestBatch,
        double progress,
        String failure) {}
