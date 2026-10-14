package shop.whitedns.client.scan

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Coalesces runtime resolver telemetry into bounded AtomicFile write batches. */
internal class ResolverSurvivalEventWriter(
    private val scope: CoroutineScope,
    private val batchSize: Int = DefaultBatchSize,
    private val flushWindowMillis: Long = DefaultFlushWindowMillis,
    private val persist: suspend (List<ResolverOutcome>) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val pending = ConcurrentLinkedQueue<ResolverOutcome>()
    private val workerScheduled = AtomicBoolean(false)

    init {
        require(batchSize > 0)
        require(flushWindowMillis >= 0)
    }

    fun enqueue(outcomes: Iterable<ResolverOutcome>) {
        var enqueued = false
        outcomes.forEach {
            pending.add(it)
            enqueued = true
        }
        if (enqueued) scheduleWorker()
    }

    private fun scheduleWorker() {
        if (!workerScheduled.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            delay(flushWindowMillis)
            while (isActive) {
                val batch = ArrayList<ResolverOutcome>(batchSize)
                while (batch.size < batchSize) {
                    batch += pending.poll() ?: break
                }
                if (batch.isEmpty()) {
                    workerScheduled.set(false)
                    if (pending.isEmpty() || !workerScheduled.compareAndSet(false, true)) return@launch
                    continue
                }
                runCatching { persist(batch) }.onFailure { error ->
                    runCatching { onFailure(error) }
                }
            }
        }
    }

    companion object {
        const val DefaultBatchSize = 64
        const val DefaultFlushWindowMillis = 50L
    }
}
