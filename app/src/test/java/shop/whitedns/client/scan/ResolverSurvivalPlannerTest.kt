package shop.whitedns.client.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import shop.whitedns.client.model.DnsClientEngine

class ResolverSurvivalPlannerTest {
    private val now = 1_800_000_000_000L

    @Test
    fun planOrdersNetworkThenGlobalThenSeedThenUserThenFullPool() {
        val evidence = listOf(
            ResolverEvidence(endpoint = "1.1.1.1", engine = DnsClientEngine.CottenDns, networkFingerprint = "cell:43211:v4", successfulValidations = 1, lastSuccessAtMillis = now - 1_000),
            ResolverEvidence(endpoint = "8.8.8.8", engine = DnsClientEngine.CottenDns, successfulValidations = 1, lastSuccessAtMillis = now - 500),
        )

        val plan = ResolverSurvivalPlanner.plan(
            engine = DnsClientEngine.CottenDns,
            currentNetworkFingerprint = "cell:43211:v4",
            evidence = evidence,
            seeds = listOf("9.9.9.9"),
            userResolvers = listOf("4.4.4.4"),
            fullPool = listOf("5.5.5.5"),
            nowMillis = now,
        )

        assertEquals(
            listOf(
                ResolverCandidateSource.NETWORK_PROVEN,
                ResolverCandidateSource.GLOBAL_PROVEN,
                ResolverCandidateSource.SEED,
                ResolverCandidateSource.USER,
                ResolverCandidateSource.FULL_POOL,
            ),
            plan.allCandidates.map { it.source },
        )
    }

    @Test
    fun recentEvidenceOutranksStaleEvidenceWithinSameTier() {
        val recent = ResolverEvidence("1.1.1.1", DnsClientEngine.CottenDns, "wifi:v4", successfulValidations = 1, lastSuccessAtMillis = now - 60_000)
        val stale = ResolverEvidence("8.8.8.8", DnsClientEngine.CottenDns, "wifi:v4", successfulValidations = 1, lastSuccessAtMillis = now - 90L * 24 * 60 * 60 * 1000)

        val plan = ResolverSurvivalPlanner.plan(
            engine = DnsClientEngine.CottenDns,
            currentNetworkFingerprint = "wifi:v4",
            evidence = listOf(stale, recent),
            nowMillis = now,
        )

        assertEquals(listOf("1.1.1.1", "8.8.8.8"), plan.hotCandidates.map { it.endpoint })
        assertTrue(plan.hotCandidates[0].score > plan.hotCandidates[1].score)
    }

    @Test
    fun repeatedFailuresDemoteAndLaterSuccessRecoversRanking() {
        val healthy = ResolverEvidence("1.1.1.1", DnsClientEngine.CottenDns, "cell", successfulValidations = 1, lastSuccessAtMillis = now - 10_000)
        val failing = ResolverEvidence("8.8.8.8", DnsClientEngine.CottenDns, "cell", successfulValidations = 1, failedValidations = 3, consecutiveFailures = 3, lastSuccessAtMillis = now - 1_000)
        val recovered = failing.copy(failedValidations = 3, consecutiveFailures = 0, lastSuccessAtMillis = now, successfulValidations = 2)

        val failedPlan = ResolverSurvivalPlanner.plan(DnsClientEngine.CottenDns, "cell", listOf(failing, healthy), nowMillis = now)
        val recoveredPlan = ResolverSurvivalPlanner.plan(DnsClientEngine.CottenDns, "cell", listOf(recovered, healthy), nowMillis = now)

        assertEquals("1.1.1.1", failedPlan.hotCandidates.first().endpoint)
        assertEquals("8.8.8.8", recoveredPlan.hotCandidates.first().endpoint)
    }

    @Test
    fun dedupeKeepsNonDefaultPortAsDistinctEndpointAndFirstSourceWins() {
        val plan = ResolverSurvivalPlanner.plan(
            engine = DnsClientEngine.CottenDns,
            currentNetworkFingerprint = null,
            evidence = emptyList(),
            seeds = listOf("1.1.1.1", "1.1.1.1:5353"),
            userResolvers = listOf("1.1.1.1", "8.8.8.8"),
            fullPool = listOf("1.1.1.1:5353", "9.9.9.9"),
            nowMillis = now,
        )

        assertEquals(listOf("1.1.1.1", "1.1.1.1:5353", "8.8.8.8", "9.9.9.9"), plan.allCandidates.map { it.endpoint })
        assertEquals(ResolverCandidateSource.SEED, plan.allCandidates.first().source)
    }

    @Test
    fun unknownNetworkUsesOnlyGlobalProvenEvidence() {
        val evidence = listOf(
            ResolverEvidence("1.1.1.1", DnsClientEngine.CottenDns, "cell", successfulValidations = 1, lastSuccessAtMillis = now),
            ResolverEvidence("8.8.8.8", DnsClientEngine.CottenDns, null, successfulValidations = 1, lastSuccessAtMillis = now),
        )

        val plan = ResolverSurvivalPlanner.plan(DnsClientEngine.CottenDns, null, evidence, nowMillis = now)

        assertEquals(listOf("8.8.8.8"), plan.allCandidates.map { it.endpoint })
        assertEquals(ResolverCandidateSource.GLOBAL_PROVEN, plan.allCandidates.single().source)
    }

    @Test
    fun deterministicTiesUseEndpointOrdering() {
        val evidence = listOf(
            ResolverEvidence("9.9.9.9", DnsClientEngine.CottenDns, null, successfulValidations = 1, lastSuccessAtMillis = now),
            ResolverEvidence("1.1.1.1", DnsClientEngine.CottenDns, null, successfulValidations = 1, lastSuccessAtMillis = now),
        )

        val plan = ResolverSurvivalPlanner.plan(DnsClientEngine.CottenDns, null, evidence, nowMillis = now)
        assertEquals(listOf("1.1.1.1", "9.9.9.9"), plan.hotCandidates.map { it.endpoint })
    }

    @Test
    fun defaultHotSetIsTwentyAndRemainingContainsTheRest() {
        val full = (1..25).map { "10.0.0.$it" }
        val plan = ResolverSurvivalPlanner.plan(
            engine = DnsClientEngine.CottenDns,
            currentNetworkFingerprint = null,
            evidence = emptyList(),
            fullPool = full,
            nowMillis = now,
        )

        assertEquals(20, plan.hotCandidates.size)
        assertEquals(5, plan.remainingCandidates.size)
        assertEquals(full, plan.allCandidates.map { it.endpoint })
    }
    @Test
    fun insufficientHotSetWidensToRemainingCandidatesInStableOrder() {
        val full = (1..25).map { "10.0.1.$it" }
        val plan = ResolverSurvivalPlanner.plan(
            engine = DnsClientEngine.CottenDns,
            currentNetworkFingerprint = null,
            evidence = emptyList(),
            fullPool = full,
            nowMillis = now,
        )

        assertEquals(full.take(20), plan.candidatesForValidation(validResolverCount = 1, minimumValidResolvers = 1).map { it.endpoint })
        assertEquals(full, plan.candidatesForValidation(validResolverCount = 0, minimumValidResolvers = 1).map { it.endpoint })
    }

}
