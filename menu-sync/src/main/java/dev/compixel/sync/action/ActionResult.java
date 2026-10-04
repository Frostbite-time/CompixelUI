package dev.compixel.sync.action;

/** One action's outcome. Sequence zero is a local refusal; actual and limit are -1 when unavailable. */
public record ActionResult(
        long sequence,
        String action,
        ActionStatus status,
        ActionFailure failure,
        long actual,
        long limit,
        String detail) {}
