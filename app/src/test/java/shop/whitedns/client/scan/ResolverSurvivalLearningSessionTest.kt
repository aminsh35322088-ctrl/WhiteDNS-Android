package shop.whitedns.client.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import shop.whitedns.client.model.DnsClientEngine
import shop.whitedns.client.model.ResolverRuntimeState

class ResolverSurvivalLearningSessionTest {
    @Test
    fun validRuntimeScanTelemetryProducesNormalizedSuccessOnce() {
        val session = ResolverSurvivalLearningSession(
            engine = DnsClientEngine.CottenDns,
            networkFingerprint = "cellular:43211:v4",
            survivalObserved = true,
        )
        val telemetry = StormDnsScanTelemetry.Valid("5.196.80.66:43629")

        val first = session.observeScanTelemetry(telemetry, 1000L)
        val duplicate = session.observeScanTelemetry(telemetry, 1001L)

        assertEquals(1, first.size)
        assertEquals("5.196.80.66:43629", first.single().endpoint)
        assertEquals(ResolverOutcomeType.SUCCESS, first.single().type)
        assertEquals("cellular:43211:v4", first.single().networkFingerprint)
        assertTrue(first.single().survivalObserved)
        assertEquals(emptyList<ResolverOutcome>(), duplicate)
    }

    @Test
    fun rejectedThenValidTelemetryAllowsRecoveryOutcome() {
        val session = ResolverSurvivalLearningSession(DnsClientEngine.CottenDns, null, false)

        val rejected = session.observeScanTelemetry(StormDnsScanTelemetry.Rejected("1.1.1.1:53"), 1000L)
        val valid = session.observeScanTelemetry(StormDnsScanTelemetry.Valid("1.1.1.1:53"), 2000L)

        assertEquals(ResolverOutcomeType.FAILURE, rejected.single().type)
        assertEquals("1.1.1.1", rejected.single().endpoint)
        assertEquals(ResolverOutcomeType.SUCCESS, valid.single().type)
        assertEquals("1.1.1.1", valid.single().endpoint)
    }

    @Test
    fun resolverRuntimeStatePromotesActiveAndValidResolversWithoutDuplicates() {
        val session = ResolverSurvivalLearningSession(DnsClientEngine.CottenDns, "wifi:v4", false)
        val state = ResolverRuntimeState(
            activeResolvers = listOf("1.1.1.1"),
            standbyResolvers = listOf("8.8.8.8"),
            validResolvers = listOf("1.1.1.1", "9.9.9.9"),
        )

        val outcomes = session.observeResolverState(state, 3000L)
        val repeated = session.observeResolverState(state, 3001L)

        assertEquals(setOf("1.1.1.1"), outcomes.map { it.endpoint }.toSet())
        assertTrue(outcomes.all { it.type == ResolverOutcomeType.SUCCESS })
        assertEquals(emptyList<ResolverOutcome>(), repeated)
    }

    @Test
    fun nonCottenDnsSessionDoesNotLearnSurvivalEvidence() {
        val session = ResolverSurvivalLearningSession(DnsClientEngine.StormDns, "wifi:v4", false)
        assertEquals(
            emptyList<ResolverOutcome>(),
            session.observeScanTelemetry(StormDnsScanTelemetry.Valid("1.1.1.1"), 1000L),
        )
    }
}
