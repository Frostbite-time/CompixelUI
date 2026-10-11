package dev.compixel.sync.action;

/** Stable diagnostics for local submissions and remote action results. The constant order is part of the wire format. */
public enum ActionFailure {
    NONE,
    NOT_READY,
    CLOSED,
    UNKNOWN_ACTION,
    TRANSPORT_UNAVAILABLE,
    BODY_BYTES,
    CODEC,
    THROTTLED,
    REJECTED,
    MALFORMED
}
