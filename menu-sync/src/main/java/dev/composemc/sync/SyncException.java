package dev.composemc.sync;

/** Invalid/incomplete protocol data or a configured resource limit being exceeded. */
public final class SyncException extends RuntimeException {
    public SyncException(String message) {
        super(message);
    }

    public SyncException(String message, Throwable cause) {
        super(message, cause);
    }
}
