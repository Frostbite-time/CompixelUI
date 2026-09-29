package dev.compixel.sync.action;

import dev.compixel.sync.transport.TransferBudget;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Objects;

/** C2S queue, bandwidth, admission and separate waiting/progress/reply deadlines. */
public record ActionLimits(
        int fragmentBytes,
        TransferBudget bandwidth,
        long maxQueuedBytes,
        int maxPendingActions,
        int actionsPerTick,
        long queueTimeoutTicks,
        long progressTimeoutTicks,
        long replyTimeoutTicks) {
    public static final ActionLimits DEFAULT = new ActionLimits(
            16 * 1024,
            new TransferBudget(32 * 1024, 4L * 1024 * 1024, 256 * 1024),
            4L * 1024 * 1024,
            32,
            16,
            200,
            200,
            200);

    public ActionLimits {
        Objects.requireNonNull(bandwidth);
        if (fragmentBytes < 1
                || maxQueuedBytes < 1
                || maxPendingActions < 1
                || actionsPerTick < 1
                || queueTimeoutTicks < 1
                || progressTimeoutTicks < 1
                || replyTimeoutTicks < 1)
            throw new IllegalArgumentException("Action budgets and deadlines must be positive");
    }

    public void write(DataOutput out) throws IOException {
        out.writeInt(fragmentBytes);
        bandwidth.write(out);
        out.writeLong(maxQueuedBytes);
        out.writeInt(maxPendingActions);
        out.writeInt(actionsPerTick);
        out.writeLong(queueTimeoutTicks);
        out.writeLong(progressTimeoutTicks);
        out.writeLong(replyTimeoutTicks);
    }
}
