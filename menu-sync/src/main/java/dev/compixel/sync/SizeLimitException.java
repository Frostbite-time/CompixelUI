package dev.compixel.sync;

import java.io.IOException;

/** Attempted encoded size is a lower bound when encoding stops before completion. */
public final class SizeLimitException extends IOException {
    private final long actual, limit;

    public SizeLimitException(long actual, long limit) {
        super("Encoded data exceeds byte budget: attempted=" + actual + ", limit=" + limit);
        this.actual = actual;
        this.limit = limit;
    }

    public long actual() {
        return actual;
    }

    public long limit() {
        return limit;
    }
}
