package dev.composemc.sync;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/** Encoded body bytes: sustained refill, saved burst credit, and a per-tick emission ceiling. */
public record TransferBudget(int bytesPerTick, long burstBytes, int maxBytesPerTick) {
    public TransferBudget {
        if (bytesPerTick < 1 || burstBytes < 1 || maxBytesPerTick < 1)
            throw new IllegalArgumentException("Transfer budgets must be positive");
    }
    public static TransferBudget steady(int bytesPerTick) {
        return new TransferBudget(bytesPerTick, bytesPerTick, bytesPerTick);
    }
    public void write(DataOutput out) throws IOException {
        out.writeInt(bytesPerTick); out.writeLong(burstBytes); out.writeInt(maxBytesPerTick);
    }
    public static TransferBudget read(DataInput in) throws IOException {
        return new TransferBudget(in.readInt(), in.readLong(), in.readInt());
    }
}
