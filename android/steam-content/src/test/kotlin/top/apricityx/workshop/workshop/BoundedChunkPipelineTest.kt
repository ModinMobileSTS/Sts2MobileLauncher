package top.apricityx.workshop.workshop

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BoundedChunkPipelineTest {
    @Test
    fun fixedFetchWorkersFeedOneWriterAndPreserveAllDestinations() = runBlocking {
        val plans = (0 until 8).map { chunkId ->
            PlannedChunk(
                chunk = chunkId,
                destinations = listOf(chunkId * 100L, chunkId * 100L + 50L),
                estimatedBytes = 16L,
            )
        }
        val activeFetches = AtomicInteger(0)
        val maximumFetches = AtomicInteger(0)
        val activeWriters = AtomicInteger(0)
        val maximumWriters = AtomicInteger(0)
        val writes = Collections.synchronizedList(
            mutableListOf<Pair<Int, List<Long>>>(),
        )

        val result = withTimeout(TEST_TIMEOUT_MILLIS) {
            runBoundedChunkPipeline(
                plannedChunks = plans,
                options = options(
                    workerCount = 3,
                    resultBufferCapacity = 2,
                    maxEstimatedBytesInFlight = 1_024L,
                    progressMinIntervalMillis = 0L,
                ),
                fetch = { plan ->
                    val active = activeFetches.incrementAndGet()
                    maximumFetches.updateAndGet { previous -> maxOf(previous, active) }
                    try {
                        delay(20L)
                        byteArrayOf(plan.chunk.toByte())
                    } finally {
                        activeFetches.decrementAndGet()
                    }
                },
                write = { plan, payload ->
                    val active = activeWriters.incrementAndGet()
                    maximumWriters.updateAndGet { previous -> maxOf(previous, active) }
                    try {
                        delay(5L)
                        assertEquals(plan.chunk.toByte(), payload.single())
                        writes += plan.chunk to plan.destinations
                    } finally {
                        activeWriters.decrementAndGet()
                    }
                },
            )
        }

        assertEquals(3, maximumFetches.get())
        assertEquals(1, maximumWriters.get())
        assertEquals(plans.size, result.completedChunks)
        assertEquals(plans.sumOf { it.progressBytes }, result.completedBytes)
        assertEquals(
            plans.associate { it.chunk to it.destinations },
            writes.toMap(),
        )
    }

    @Test
    fun estimatedByteBudgetIsHeldUntilWriterFinishes() = runBlocking {
        val plans = (0 until 3).map { chunkId ->
            PlannedChunk(
                chunk = chunkId,
                destinations = listOf(chunkId.toLong()),
                estimatedBytes = 6L,
            )
        }
        val fetchesStarted = AtomicInteger(0)
        val firstWriteStarted = CompletableDeferred<Unit>()
        val allowWrites = CompletableDeferred<Unit>()

        val pipeline = async {
            runBoundedChunkPipeline(
                plannedChunks = plans,
                options = options(
                    workerCount = 3,
                    resultBufferCapacity = 2,
                    maxEstimatedBytesInFlight = 10L,
                ),
                fetch = {
                    fetchesStarted.incrementAndGet()
                    ByteArray(6)
                },
                write = { _, _ ->
                    firstWriteStarted.complete(Unit)
                    allowWrites.await()
                },
            )
        }

        withTimeout(TEST_TIMEOUT_MILLIS) { firstWriteStarted.await() }
        delay(100L)
        assertEquals(1, fetchesStarted.get())
        allowWrites.complete(Unit)
        assertEquals(3, withTimeout(TEST_TIMEOUT_MILLIS) { pipeline.await() }.completedChunks)
    }

    @Test
    fun chunkLargerThanBudgetRunsAloneInsteadOfDeadlocking() = runBlocking {
        val result = withTimeout(TEST_TIMEOUT_MILLIS) {
            runBoundedChunkPipeline(
                plannedChunks = listOf(
                    PlannedChunk(
                        chunk = "oversized",
                        destinations = listOf(0L),
                        estimatedBytes = 20L,
                    ),
                ),
                options = options(
                    workerCount = 2,
                    resultBufferCapacity = 0,
                    maxEstimatedBytesInFlight = 10L,
                ),
                fetch = { ByteArray(20) },
                write = { _, payload -> assertEquals(20, payload.size) },
            )
        }

        assertEquals(1, result.completedChunks)
    }

    @Test
    fun fetchFailureCancelsSiblingAndPropagatesOriginalError() = runBlocking {
        val slowFetchStarted = CompletableDeferred<Unit>()
        val slowFetchCancelled = CompletableDeferred<Unit>()
        val failure = PipelineFailure("fetch failed")
        val plans = listOf(
            PlannedChunk("slow", listOf(0L), estimatedBytes = 1L),
            PlannedChunk("failure", listOf(1L), estimatedBytes = 1L),
        )

        val thrown = assertFailsWith<PipelineFailure> {
            withTimeout(TEST_TIMEOUT_MILLIS) {
                runBoundedChunkPipeline<String, Long, ByteArray>(
                    plannedChunks = plans,
                    options = options(workerCount = 2),
                    fetch = { plan ->
                        when (plan.chunk) {
                            "slow" -> {
                                slowFetchStarted.complete(Unit)
                                try {
                                    awaitCancellation()
                                } finally {
                                    slowFetchCancelled.complete(Unit)
                                }
                            }

                            else -> {
                                slowFetchStarted.await()
                                throw failure
                            }
                        }
                    },
                    write = { _, _ -> error("No payload should reach the writer") },
                )
            }
        }

        assertSame(failure, thrown)
        withTimeout(TEST_TIMEOUT_MILLIS) { slowFetchCancelled.await() }
    }

    @Test
    fun writerFailureCancelsFetchersAndPropagatesOriginalError() = runBlocking {
        val slowFetchStarted = CompletableDeferred<Unit>()
        val slowFetchCancelled = CompletableDeferred<Unit>()
        val failure = PipelineFailure("write failed")
        val plans = listOf(
            PlannedChunk("ready", listOf(0L), estimatedBytes = 1L),
            PlannedChunk("slow", listOf(1L), estimatedBytes = 1L),
        )

        val thrown = assertFailsWith<PipelineFailure> {
            withTimeout(TEST_TIMEOUT_MILLIS) {
                runBoundedChunkPipeline<String, Long, ByteArray>(
                    plannedChunks = plans,
                    options = options(workerCount = 2),
                    fetch = { plan ->
                        if (plan.chunk == "slow") {
                            slowFetchStarted.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                slowFetchCancelled.complete(Unit)
                            }
                        } else {
                            slowFetchStarted.await()
                            byteArrayOf(1)
                        }
                    },
                    write = { _, _ -> throw failure },
                )
            }
        }

        assertSame(failure, thrown)
        withTimeout(TEST_TIMEOUT_MILLIS) { slowFetchCancelled.await() }
    }

    @Test
    fun callerCancellationReachesActiveFetch() = runBlocking {
        val fetchStarted = CompletableDeferred<Unit>()
        val fetchCancelled = CompletableDeferred<Unit>()
        val pipeline = launch {
            runBoundedChunkPipeline<String, Long, ByteArray>(
                plannedChunks = listOf(
                    PlannedChunk("slow", listOf(0L), estimatedBytes = 1L),
                ),
                options = options(workerCount = 1),
                fetch = {
                    fetchStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        fetchCancelled.complete(Unit)
                    }
                },
                write = { _, _ -> error("No payload should reach the writer") },
            )
        }

        withTimeout(TEST_TIMEOUT_MILLIS) { fetchStarted.await() }
        pipeline.cancelAndJoin()

        assertTrue(pipeline.isCancelled)
        withTimeout(TEST_TIMEOUT_MILLIS) { fetchCancelled.await() }
    }

    @Test
    fun progressEventsAreCoalescedButCompletionIsAlwaysEmitted() = runBlocking {
        val progressEvents = mutableListOf<ChunkPipelineProgress>()
        val plans = (0 until 4).map { chunkId ->
            PlannedChunk(
                chunk = chunkId,
                destinations = listOf(chunkId.toLong()),
                estimatedBytes = 2L,
                progressBytes = 5L,
            )
        }

        runBoundedChunkPipeline(
            plannedChunks = plans,
            options = options(
                workerCount = 1,
                progressMinIntervalMillis = 100L,
                monotonicNanos = { 0L },
            ),
            fetch = { byteArrayOf(it.chunk.toByte()) },
            write = { _, _ -> Unit },
            onProgress = { progressEvents += it },
        )

        assertEquals(listOf(1, 4), progressEvents.map { it.completedChunks })
        assertEquals(20L, progressEvents.last().completedBytes)
        assertEquals(20L, progressEvents.last().totalBytes)
        assertTrue(progressEvents.last().isComplete)
    }

    @Test
    fun emptyPlanStillEmitsOneCompleteProgressEvent() = runBlocking {
        val progressEvents = mutableListOf<ChunkPipelineProgress>()

        val result = runBoundedChunkPipeline<String, Long, ByteArray>(
            plannedChunks = emptyList(),
            options = options(workerCount = 2),
            fetch = { error("No fetch expected") },
            write = { _, _ -> error("No write expected") },
            onProgress = { progressEvents += it },
        )

        assertEquals(ChunkPipelineResult(0, 0L), result)
        assertEquals(1, progressEvents.size)
        assertTrue(progressEvents.single().isComplete)
    }

    @Test
    fun optionsRejectInvalidWorkerBufferBudgetAndProgressValues() {
        assertFailsWith<IllegalArgumentException> {
            options(workerCount = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            options(resultBufferCapacity = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            options(resultBufferCapacity = 3)
        }
        assertFailsWith<IllegalArgumentException> {
            options(maxEstimatedBytesInFlight = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            options(progressMinIntervalMillis = -1L)
        }
    }

    private fun options(
        workerCount: Int = 2,
        resultBufferCapacity: Int = 0,
        maxEstimatedBytesInFlight: Long = 64L,
        progressMinIntervalMillis: Long = 0L,
        monotonicNanos: () -> Long = System::nanoTime,
    ) = BoundedChunkPipelineOptions(
        workerCount = workerCount,
        resultBufferCapacity = resultBufferCapacity,
        maxEstimatedBytesInFlight = maxEstimatedBytesInFlight,
        progressMinIntervalMillis = progressMinIntervalMillis,
        monotonicNanos = monotonicNanos,
    )

    private class PipelineFailure(message: String) : RuntimeException(message)

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 5_000L
    }
}
