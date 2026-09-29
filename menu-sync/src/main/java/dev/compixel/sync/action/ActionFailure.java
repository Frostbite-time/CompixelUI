package dev.compixel.sync.action;

/** Stable diagnostics for local submissions and remote action results. */
public enum ActionFailure {
    NONE,
    NOT_READY,
    CLOSED,
    UNKNOWN_ACTION,
    TRANSPORT_UNAVAILABLE,
    QUEUE_BYTES,
    PENDING_ACTIONS,
    BODY_BYTES,
    CODEC,
    THROTTLED,
    REJECTED,
    MALFORMED,
    QUEUE_TIMEOUT,
    SEND_TIMEOUT,
    REPLY_TIMEOUT,
    ASSEMBLY_TIMEOUT
}
