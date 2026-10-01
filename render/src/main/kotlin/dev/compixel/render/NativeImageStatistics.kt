package dev.compixel.render

/** Owner-thread snapshot of one native image scheduler, available without depending on Compose internals. */
data class NativeImageStatistics(
    val activeVariants: Int = 0,
    val cachedImages: Int = 0,
    val pendingImages: Int = 0,
    val preparedImages: Long = 0,
    val retiredImages: Long = 0,
    val dynamicVariants: Int = 0,
    val animationRefreshes: Long = 0,
    /** Allocation pages currently holding content. */
    val pages: Int = 0,
    /** Individual images drawn into their allocation cells. */
    val drawnImages: Long = 0,
)
