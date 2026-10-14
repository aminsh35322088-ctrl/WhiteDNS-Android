package shop.whitedns.client.scan

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import shop.whitedns.client.model.DnsClientEngine
import shop.whitedns.client.model.ResolverRuntimeState

class ResolverSurvivalAuditTest {
    private val engine = DnsClientEngine.CottenDns
    private fun file() = File.createTempFile("survival-audit", ".json").apply { delete(); deleteOnExit() }

    @Test fun independentWritersPreserveEveryEndpoint() {
        val file = file()
        val pool = Executors.newFixedThreadPool(16)
        val gate = CountDownLatch(1)
        try {
            val writes = (1..16).map { i -> pool.submit {
                gate.await()
                ResolverSurvivalStore(file).record(listOf(ResolverOutcome("10.0.0.$i", engine, type = ResolverOutcomeType.SUCCESS, observedAtMillis = 1000)))
            } }
            gate.countDown()
            writes.forEach { it.get(20, TimeUnit.SECONDS) }
            assertEquals((1..16).map { "10.0.0.$it" }.toSet(), ResolverSurvivalStore(file).read().map { it.endpoint }.toSet())
        } finally { pool.shutdownNow() }
    }

    @Test fun unsupportedSchemaIsNotDestroyedByLearning() {
        val file = file().apply { writeText("""{"schemaVersion":999,"records":[{"future":"evidence"}]}""") }
        val original = file.readText()
        assertThrows(IllegalStateException::class.java) { ResolverSurvivalStore(file).record(listOf(ResolverOutcome("1.1.1.1", engine, type = ResolverOutcomeType.SUCCESS, observedAtMillis = 1000))) }
        assertEquals(original, file.readText())
    }

    @Test fun duplicateEventDoesNotInflateEvidence() {
        val store = ResolverSurvivalStore(file())
        val event = ResolverOutcome("1.1.1.1", engine, type = ResolverOutcomeType.SUCCESS, observedAtMillis = 1000)
        store.record(listOf(event, event))
        store.record(listOf(event))
        assertEquals(1, store.read().single().successfulValidations)
    }

    @Test fun endpointIdentityUsesCanonicalDefaultPort() {
        val store = ResolverSurvivalStore(file())
        store.record(listOf(
            ResolverOutcome("1.1.1.1:53", engine, type = ResolverOutcomeType.SUCCESS, observedAtMillis = 1000),
            ResolverOutcome("1.1.1.1", engine, type = ResolverOutcomeType.SUCCESS, observedAtMillis = 1001),
        ))
        assertEquals(1, store.read().size)
        assertEquals("1.1.1.1", store.read().single().endpoint)
    }

    @Test fun countersSaturateRatherThanOverflow() {
        val store = ResolverSurvivalStore(file())
        store.replaceForTest(listOf(ResolverEvidence("1.1.1.1", engine, successfulValidations = Int.MAX_VALUE, lastSuccessAtMillis = 1000)))
        store.record(listOf(ResolverOutcome("1.1.1.1", engine, type = ResolverOutcomeType.SUCCESS, observedAtMillis = 1001)))
        assertEquals(Int.MAX_VALUE, store.read().single().successfulValidations)
    }

    @Test fun plannerCanonicalizesAndRejectsMalformedCandidates() {
        val plan = ResolverSurvivalPlanner.plan(engine, null, emptyList(), seeds = listOf("8.8.8.8"), userResolvers = listOf("1.1.1.1:53", "1.1.1.1", "1.1.1.1:65536", "not-an-endpoint"))
        assertEquals(listOf("8.8.8.8", "1.1.1.1"), plan.allCandidates.map { it.endpoint })
    }

    @Test fun networkFailuresCannotBeBypassedByGlobalHistory() {
        val evidence = listOf(
            ResolverEvidence("1.1.1.1", engine, "wifi:v4", successfulValidations = 1, consecutiveFailures = 2),
            ResolverEvidence("1.1.1.1", engine, successfulValidations = 10),
        )
        val plan = ResolverSurvivalPlanner.plan(engine, "wifi:v4", evidence, seeds = listOf("8.8.8.8"), userResolvers = listOf("1.1.1.1"))
        assertEquals(listOf("8.8.8.8", "1.1.1.1"), plan.allCandidates.map { it.endpoint })
    }

    @Test fun retainedValidListCannotUndoExplicitRejection() {
        val session = ResolverSurvivalLearningSession(engine, "wifi:v4", false)
        session.observeResolverState(ResolverRuntimeState(validResolvers = listOf("1.1.1.1")), 1000)
        session.observeScanTelemetry(StormDnsScanTelemetry.Rejected("1.1.1.1"), 2000)
        assertTrue(session.observeResolverState(ResolverRuntimeState(validResolvers = listOf("1.1.1.1")), 3000).isEmpty())
    }

    @Test fun cidrIsNotAnIndividualLearnedEndpoint() {
        val session = ResolverSurvivalLearningSession(engine, null, false)
        assertTrue(session.observeScanTelemetry(StormDnsScanTelemetry.Valid("1.1.1.0/24"), 1000).isEmpty())
    }

    @Test fun scanTelemetryPreservesCustomPortAndRejectsMalformedValues() {
        assertEquals("5.196.80.66:43629", WhiteDnsScannerResultStore.normalizeResolverEntry(" 5.196.80.66:43629 ", engine))
        assertNull(WhiteDnsScannerResultStore.normalizeResolverEntry("1.1.1.1:70000", engine))
        assertEquals("1.1.1.1", WhiteDnsScannerResultStore.normalizeResolverEntry("1.1.1.1:53", engine))
    }

    @Test fun networkFingerprintCannotPersistArbitraryTransportOrWifiCarrier() {
        assertNull(NetworkFingerprint.fromParts("secret-ssid", "43211", true, false))
        assertEquals("wifi:v4", NetworkFingerprint.fromParts("wifi", "43211", true, false)?.value)
    }

    @Test fun invalidLatencyAndLossCannotIncreaseScore() {
        val baseline = ResolverEvidence("1.1.1.1", engine, successfulValidations = 1)
        val malformedMetrics = baseline.copy(latencyMillis = -1, lossRate = -0.5)
        assertEquals(
            ResolverSurvivalScoring.score(baseline, null, 1000),
            ResolverSurvivalScoring.score(malformedMetrics, null, 1000),
        )
    }

    @Test fun extremeOldTimestampCapsDecayWithoutOverflowingScore() {
        val evidence = ResolverEvidence("1.1.1.1", engine, successfulValidations = 1, lastSuccessAtMillis = Long.MIN_VALUE)
        assertEquals(20, ResolverSurvivalScoring.score(evidence, null, Long.MAX_VALUE))
    }

    @Test fun negativeObservationTimeIsIgnored() {
        val store = ResolverSurvivalStore(file())
        store.record(listOf(ResolverOutcome("1.1.1.1", engine, type = ResolverOutcomeType.SUCCESS, observedAtMillis = -1)))
        assertTrue(store.read().isEmpty())
    }

    @Test fun runtimeWriterBatchesBurstWithoutDroppingOutcomes() {
        val writes = CopyOnWriteArrayList<List<ResolverOutcome>>()
        val finished = CountDownLatch(4)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val writer = ResolverSurvivalEventWriter(
                scope = scope,
                batchSize = 32,
                flushWindowMillis = 200,
                persist = { batch ->
                    writes += batch
                    finished.countDown()
                },
            )
            (1..128).forEach { index ->
                writer.enqueue(listOf(ResolverOutcome("10.1.0.$index", engine, type = ResolverOutcomeType.SUCCESS, observedAtMillis = index.toLong())))
            }
            assertTrue("batched writes did not drain", finished.await(5, TimeUnit.SECONDS))
            assertEquals(4, writes.size)
            assertEquals((1..128).map { "10.1.0.$it" }.toSet(), writes.flatten().map { it.endpoint }.toSet())
        } finally {
            scope.cancel()
        }
    }
}
