package dev.compixel.sync.action;

/** Immediate request outcome. A queued submission is not yet a successful server operation. */
public record ActionSubmission(long sequence, ActionFailure failure, long actual, long limit) {
    public boolean queued() {
        return sequence > 0 && failure == ActionFailure.NONE;
    }

    public static ActionSubmission rejected(ActionFailure failure, long actual, long limit) {
        if (failure == ActionFailure.NONE) throw new IllegalArgumentException("Missing rejection reason");
        return new ActionSubmission(0, failure, actual, limit);
    }
}
