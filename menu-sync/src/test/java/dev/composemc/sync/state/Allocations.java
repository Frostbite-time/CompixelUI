package dev.composemc.sync.state;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.ManagementFactory;

/** Heap bytes the current thread allocates, including arrays too large for a TLAB. Skips where the JVM cannot count them. */
final class Allocations {
    @FunctionalInterface
    interface Action {
        void run() throws Exception;
    }

    static long measure(Action action) throws Exception {
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());
        long before = threads.getCurrentThreadAllocatedBytes();
        action.run();
        return threads.getCurrentThreadAllocatedBytes() - before;
    }

    private Allocations() {}
}
