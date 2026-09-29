package dev.compixel.render

/** Nanoseconds over the most recent bounded sample window, not whole-game FPS. */
data class TimingSummary(val samples: Int, val p50Nanos: Long, val p95Nanos: Long, val p99Nanos: Long)

class FrameTimings(capacity: Int = 512) {
    private val samples = LongArray(capacity.also { require(it > 0) })
    private var count = 0
    private var cursor = 0

    fun record(nanos: Long) {
        require(nanos >= 0)
        samples[cursor] = nanos
        cursor = (cursor + 1) % samples.size
        count = minOf(count + 1, samples.size)
    }

    fun summary(): TimingSummary {
        if (count == 0) return TimingSummary(0, 0, 0, 0)
        val sorted = samples.copyOf(count).sortedArray()
        fun percentile(percent: Int) = sorted[((count * percent + 99) / 100 - 1).coerceAtLeast(0)]
        return TimingSummary(count, percentile(50), percentile(95), percentile(99))
    }
}
