package top.apricityx.workshop.workshop

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/**
 * One unique chunk fetch and every output location that should receive its payload.
 *
 * [estimatedBytes] is the in-flight memory reservation held from immediately before [fetch]
 * starts until [write] returns. A chunk larger than the configured budget is allowed to run,
 * but it reserves the entire budget and therefore runs alone.
 */
internal data class PlannedChunk<Chunk, Destination>(
    val chunk: Chunk,
    val destinations: List<Destination>,
    val estimatedBytes: Long,
    val progressBytes: Long = estimatedBytes,
) {
    init {
        require(destinations.isNotEmpty()) { "A planned chunk must have at least one destination" }
        require(estimatedBytes >= 0L) { "Estimated chunk bytes must not be negative" }
        require(progressBytes >= 0L) { "Chunk progress bytes must not be negative" }
    }
}

internal class BoundedChunkPipelineOptions(
    val workerCount: Int,
    val resultBufferCapacity: Int = 0,
    val maxEstimatedBytesInFlight: Long,
    val progressMinIntervalMillis: Long = DEFAULT_PROGRESS_MIN_INTERVAL_MILLIS,
    internal val monotonicNanos: () -> Long = System::nanoTime,
) {
    init {
        require(workerCount > 0) { "Chunk worker count must be positive" }
        require(resultBufferCapacity in MIN_RESULT_BUFFER_CAPACITY..MAX_RESULT_BUFFER_CAPACITY) {
            "Chunk result buffer capacity must be between $MIN_RESULT_BUFFER_CAPACITY and " +
                "$MAX_RESULT_BUFFER_CAPACITY"
        }
        require(maxEstimatedBytesInFlight > 0L) { "Chunk byte budget must be positive" }
        require(progressMinIntervalMillis >= 0L) { "Progress interval must not be negative" }
    }

    internal companion object {
        const val MIN_RESULT_BUFFER_CAPACITY = 0
        const val MAX_RESULT_BUFFER_CAPACITY = 2
        const val DEFAULT_PROGRESS_MIN_INTERVAL_MILLIS = 100L
    }
}

internal data class ChunkPipelineProgress(
    val completedChunks: Int,
    val totalChunks: Int,
    val completedBytes: Long,
    val totalBytes: Long,
) {
    val isComplete: Boolean
        get() = completedChunks == totalChunks
}

internal data class ChunkPipelineResult(
    val completedChunks: Int,
    val completedBytes: Long,
)

/**
 * Fetches chunks with a fixed worker pool and funnels all output through one writer coroutine.
 *
 * Workers claim plans through an [AtomicInteger]. Fetched payloads cross a result channel whose
 * capacity is deliberately restricted to 0..2. The single [write] callback may therefore safely
 * write the same random-access file without adding its own serialization lock.
 *
 * Fetch, writer, progress, and caller cancellation failures are never converted to retries here:
 * the original failure cancels the remaining pipeline and is propagated to the caller.
 */
internal suspend fun <Chunk, Destination, Payload> runBoundedChunkPipeline(
    plannedChunks: List<PlannedChunk<Chunk, Destination>>,
    options: BoundedChunkPipelineOptions,
    fetch: suspend (PlannedChunk<Chunk, Destination>) -> Payload,
    write: suspend (PlannedChunk<Chunk, Destination>, Payload) -> Unit,
    onProgress: suspend (ChunkPipelineProgress) -> Unit = {},
): ChunkPipelineResult {
    val plans = plannedChunks.toList()
    val totalProgressBytes = plans.fold(0L) { total, plan ->
        saturatingAdd(total, plan.progressBytes)
    }
    val progress = CoalescedChunkProgressEmitter(
        totalChunks = plans.size,
        totalBytes = totalProgressBytes,
        minIntervalNanos = millisToNanosSaturated(options.progressMinIntervalMillis),
        monotonicNanos = options.monotonicNanos,
        sink = onProgress,
    )

    if (plans.isEmpty()) {
        progress.emit(completedChunks = 0, completedBytes = 0L, force = true)
        return ChunkPipelineResult(completedChunks = 0, completedBytes = 0L)
    }

    return supervisorScope {
        val nextPlanIndex = AtomicInteger(0)
        val byteBudget = EstimatedByteBudget(options.maxEstimatedBytesInFlight)
        val results = Channel<FetchedChunk<Chunk, Destination, Payload>>(
            capacity = options.resultBufferCapacity,
            onUndeliveredElement = { fetched -> fetched.reservation.release() },
        )
        val producer = async {
            try {
                coroutineScope {
                    repeat(options.workerCount) {
                        launch {
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val planIndex = nextPlanIndex.getAndIncrement()
                                if (planIndex >= plans.size) {
                                    break
                                }
                                val plan = plans[planIndex]
                                val reservation = byteBudget.acquire(plan.estimatedBytes)
                                var handedToChannel = false
                                try {
                                    val payload = fetch(plan)
                                    currentCoroutineContext().ensureActive()
                                    results.send(FetchedChunk(plan, payload, reservation))
                                    handedToChannel = true
                                } finally {
                                    if (!handedToChannel) {
                                        reservation.release()
                                    }
                                }
                            }
                        }
                    }
                }
                results.close()
            } catch (error: Throwable) {
                results.close(error)
                throw error
            }
        }

        var completedChunks = 0
        var completedBytes = 0L
        try {
            for (fetched in results) {
                try {
                    write(fetched.plan, fetched.payload)
                } finally {
                    fetched.reservation.release()
                }
                completedChunks += 1
                completedBytes = saturatingAdd(completedBytes, fetched.plan.progressBytes)
                progress.emit(
                    completedChunks = completedChunks,
                    completedBytes = completedBytes,
                    force = completedChunks == plans.size,
                )
            }
            producer.await()
            ChunkPipelineResult(
                completedChunks = completedChunks,
                completedBytes = completedBytes,
            )
        } finally {
            results.cancel()
            producer.cancel()
        }
    }
}

private data class FetchedChunk<Chunk, Destination, Payload>(
    val plan: PlannedChunk<Chunk, Destination>,
    val payload: Payload,
    val reservation: EstimatedByteReservation,
)

/**
 * A weighted semaphore specialized for estimated byte reservations.
 *
 * StateFlow provides cancellation-aware change notification without holding a coroutine Mutex
 * from the channel's non-suspending onUndeliveredElement callback.
 */
private class EstimatedByteBudget(
    private val maxBytes: Long,
) {
    private val lock = Any()
    private val stateVersion = MutableStateFlow(0L)
    private var reservedBytes = 0L

    suspend fun acquire(estimatedBytes: Long): EstimatedByteReservation {
        val requestedBytes = estimatedBytes.coerceAtMost(maxBytes)
        if (requestedBytes == 0L) {
            return EstimatedByteReservation {}
        }

        while (true) {
            currentCoroutineContext().ensureActive()
            val observedVersion = stateVersion.value
            val acquired = synchronized(lock) {
                if (requestedBytes <= maxBytes - reservedBytes) {
                    reservedBytes += requestedBytes
                    true
                } else {
                    false
                }
            }
            if (acquired) {
                return EstimatedByteReservation { release(requestedBytes) }
            }
            stateVersion.first { version -> version != observedVersion }
        }
    }

    private fun release(bytes: Long) {
        synchronized(lock) {
            check(bytes <= reservedBytes) { "Released more chunk bytes than were reserved" }
            reservedBytes -= bytes
            stateVersion.value = stateVersion.value + 1L
        }
    }
}

private class EstimatedByteReservation(
    private val releaseAction: () -> Unit,
) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) {
            releaseAction()
        }
    }
}

private class CoalescedChunkProgressEmitter(
    private val totalChunks: Int,
    private val totalBytes: Long,
    private val minIntervalNanos: Long,
    private val monotonicNanos: () -> Long,
    private val sink: suspend (ChunkPipelineProgress) -> Unit,
) {
    private var lastEmissionNanos: Long? = null

    suspend fun emit(completedChunks: Int, completedBytes: Long, force: Boolean) {
        val now = monotonicNanos()
        val previous = lastEmissionNanos
        if (!force && previous != null && elapsedNanos(previous, now) < minIntervalNanos) {
            return
        }
        sink(
            ChunkPipelineProgress(
                completedChunks = completedChunks,
                totalChunks = totalChunks,
                completedBytes = completedBytes,
                totalBytes = totalBytes,
            ),
        )
        lastEmissionNanos = now
    }
}

private fun saturatingAdd(left: Long, right: Long): Long =
    if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

private fun millisToNanosSaturated(millis: Long): Long =
    if (millis > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
        Long.MAX_VALUE
    } else {
        millis * NANOS_PER_MILLISECOND
    }

private fun elapsedNanos(start: Long, end: Long): Long =
    if (end >= start) end - start else Long.MAX_VALUE

private const val NANOS_PER_MILLISECOND = 1_000_000L
