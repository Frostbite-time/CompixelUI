package dev.composemc.sync;

import java.util.Objects;
import java.util.UUID;

/** Server-issued session challenge plus ordered, bounded action admission. No inventory code runs here. */
public final class RequestWindow {
    public enum Admission { ACCEPT, STALE, THROTTLED }
    private final UUID token;
    private final int limit;
    private long sequence, tick = Long.MIN_VALUE;
    private int count;
    public RequestWindow(UUID token, int limit) {
        this.token = Objects.requireNonNull(token);
        if (limit < 1) throw new IllegalArgumentException("Invalid request budget");
        this.limit = limit;
    }
    public Admission admit(UUID token, long sequence, long tick) {
        if (!this.token.equals(token) || sequence <= 0 || sequence != this.sequence + 1) return Admission.STALE;
        this.sequence = sequence;
        if (this.tick != tick) { this.tick = tick; count = 0; }
        if (count >= limit) return Admission.THROTTLED;
        count++;
        return Admission.ACCEPT;
    }
}
