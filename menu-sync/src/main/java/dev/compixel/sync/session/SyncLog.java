package dev.compixel.sync.session;

/** Warnings from menu synchronization; hosts route them to their logger. */
@FunctionalInterface
public interface SyncLog {
    SyncLog NONE = (message, error) -> {};

    /** {@code error} is null when there is no exception to report. */
    void warn(String message, Throwable error);
}
