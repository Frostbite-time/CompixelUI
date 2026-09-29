package dev.compixel.sync;

import dev.compixel.sync.action.ActionLimits;
import dev.compixel.sync.state.SyncLimits;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Objects;

/** Immutable consumer configuration. Wire policy must match; logging policy is local. */
public record MenuSyncOptions(SyncLimits state, ActionLimits actions, long rejectionLogIntervalTicks) {
    public static final MenuSyncOptions DEFAULT = new MenuSyncOptions(SyncLimits.DEFAULT, ActionLimits.DEFAULT, 100);

    public MenuSyncOptions {
        Objects.requireNonNull(state);
        Objects.requireNonNull(actions);
        if (rejectionLogIntervalTicks < -1)
            throw new IllegalArgumentException("Log interval: -1 disables, 0 logs every rejection");
    }

    public MenuSyncOptions withState(SyncLimits state) {
        return new MenuSyncOptions(state, actions, rejectionLogIntervalTicks);
    }

    public MenuSyncOptions withActions(ActionLimits actions) {
        return new MenuSyncOptions(state, actions, rejectionLogIntervalTicks);
    }

    public MenuSyncOptions withRejectionLogInterval(long ticks) {
        return new MenuSyncOptions(state, actions, ticks);
    }

    public void writePolicy(DataOutput out) throws IOException {
        state.write(out);
        actions.write(out);
    }
}
