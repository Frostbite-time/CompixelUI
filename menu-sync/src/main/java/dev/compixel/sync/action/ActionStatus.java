package dev.compixel.sync.action;

/** How an action ended. The constant order is part of the wire format. */
public enum ActionStatus {
    APPLIED,
    REJECTED,
    INVALID,
    THROTTLED,
    EXPIRED
}
