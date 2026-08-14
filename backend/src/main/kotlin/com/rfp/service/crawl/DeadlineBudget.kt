package com.rfp.service.crawl

import java.time.Duration

fun interface NanoTimeSource {
    fun nanoTime(): Long
}

object SystemNanoTimeSource : NanoTimeSource {
    override fun nanoTime(): Long = System.nanoTime()
}

/** A monotonic absolute deadline shared by every stage of one fetch or render operation. */
class DeadlineBudget private constructor(
    private val deadlineNanos: Long,
    private val nanoTimeSource: NanoTimeSource,
) {
    fun remainingNanos(): Long = (deadlineNanos - nanoTimeSource.nanoTime()).coerceAtLeast(0)

    fun remaining(): Duration = Duration.ofNanos(remainingNanos())

    fun isExpired(): Boolean = remainingNanos() == 0L

    fun cap(duration: Duration): Duration {
        val remaining = remaining()
        return if (duration <= remaining) duration else remaining
    }

    companion object {
        fun start(maximumDuration: Duration, nanoTimeSource: NanoTimeSource = SystemNanoTimeSource): DeadlineBudget {
            require(!maximumDuration.isNegative && !maximumDuration.isZero) {
                "maximumDuration must be positive"
            }
            val durationNanos = runCatching { maximumDuration.toNanos() }.getOrElse { Long.MAX_VALUE }
            return DeadlineBudget(nanoTimeSource.nanoTime() + durationNanos, nanoTimeSource)
        }
    }
}
