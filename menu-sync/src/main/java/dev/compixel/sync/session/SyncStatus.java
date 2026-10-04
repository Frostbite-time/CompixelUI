package dev.compixel.sync.session;

/** Where a menu's synchronization stands. */
public enum SyncStatus {
    /** Bound, but the session has not started yet. */
    WAITING,
    /** A snapshot or an update is on its way. */
    SYNCING,
    /** The latest state is complete. */
    READY,
    /** The session stopped after an error; reopening the menu starts a new one. */
    FAILED,
    /** The menu closed. */
    CLOSED
}
