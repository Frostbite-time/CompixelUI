package dev.compixel.sync;

/** Invalid or incomplete protocol data, or a value that cannot be encoded. */
public final class SyncException extends RuntimeException {
    public SyncException(String message) {
        super(message);
    }

    public SyncException(String message, Throwable cause) {
        super(message, cause);
    }
}
