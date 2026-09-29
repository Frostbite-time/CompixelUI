package dev.compixel.sync.transport;

import java.util.Objects;

/** Owner-thread bandwidth account. Reuse across menu sessions on the same connection. */
public final class TokenBucket {
    private TransferBudget budget;
    private long credit, tick;
    private int spent;

    public TokenBucket(TransferBudget budget, long tick) {
        if (tick < 0) throw new IllegalArgumentException("Negative transfer clock");
        this.budget = Objects.requireNonNull(budget);
        this.credit = budget.burstBytes();
        this.tick = tick;
    }
    /** A policy change clamps existing credit; it never grants a fresh burst. */
    public void configure(TransferBudget next, long tick) {
        advance(tick);
        budget = Objects.requireNonNull(next);
        credit = Math.min(credit, next.burstBytes());
    }

    public int available(long tick) {
        advance(tick);
        return (int) Math.min(credit, Math.max(0L, (long) budget.maxBytesPerTick() - spent));
    }

    public void consume(int bytes, long tick) {
        if (bytes < 0 || bytes > available(tick)) throw new IllegalArgumentException("Insufficient transfer credit");
        credit -= bytes;
        spent += bytes;
    }

    public long credit(long tick) {
        advance(tick);
        return credit;
    }

    private void advance(long now) {
        if (now < tick) throw new IllegalArgumentException("Non-monotonic transfer clock");
        if (now == tick) return;
        long elapsed = now - tick, missing = budget.burstBytes() - credit;
        // Compare before multiplying so long idle periods cannot overflow.
        if (missing > 0)
            credit = elapsed >= 1 + (missing - 1) / budget.bytesPerTick()
                    ? budget.burstBytes()
                    : credit + elapsed * budget.bytesPerTick();
        tick = now;
        spent = 0;
    }
}
