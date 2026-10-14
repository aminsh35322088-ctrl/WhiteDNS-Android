package shop.whitedns.client.scan

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import shop.whitedns.client.model.DnsClientEngine

class ResolverSurvivalStoreTest {
    private fun tempStore(maxRecords: Int = 512): Pair<File, ResolverSurvivalStore> {
        val file = File.createTempFile("resolver-survival", ".json").apply {
            delete()
            deleteOnExit()
        }
        return file to ResolverSurvivalStore(file, maxRecords)
    }

    @Test
    fun jsonRoundTripPreservesEvidenceIncludingCustomPort() {
        val (_, store) = tempStore()
        val records = listOf(
            ResolverEvidence(
                endpoint = "5.196.80.66:43629",
                engine = DnsClientEngine.CottenDns,
                networkFingerprint = "cellular:43211:v4",
                successfulValidations = 3,
                failedValidations = 1,
                lastSuccessAtMillis = 1234L,
                latencyMillis = 187L,
                lossRate = 0.02,
                survivalObserved = true,
            ),
        )

        store.replaceForTest(records)

        assertEquals(records, store.read())
    }

    @Test
    fun corruptJsonFailsOpenToEmptyEvidence() {
        val (file, store) = tempStore()
        file.writeText("{ definitely not json")
        assertEquals(emptyList<ResolverEvidence>(), store.read())
    }

    @Test
    fun unknownSchemaFailsOpenToEmptyEvidence() {
        val (file, store) = tempStore()
        file.writeText("""{"schemaVersion":999,"records":[]}""")
        assertEquals(emptyList<ResolverEvidence>(), store.read())
    }

    @Test
    fun recordCreatesNetworkAndGlobalAggregates() {
        val (_, store) = tempStore()
        store.record(
            listOf(
                ResolverOutcome(
                    endpoint = "1.1.1.1",
                    engine = DnsClientEngine.CottenDns,
                    networkFingerprint = "cellular:43211:v4",
                    type = ResolverOutcomeType.SUCCESS,
                    observedAtMillis = 1000L,
                    latencyMillis = 90L,
                    lossRate = 0.0,
                    survivalObserved = true,
                ),
            ),
        )

        val records = store.read()
        assertEquals(2, records.size)
        assertTrue(records.any { it.networkFingerprint == "cellular:43211:v4" && it.successfulValidations == 1 })
        assertTrue(records.any { it.networkFingerprint == null && it.successfulValidations == 1 })
    }

    @Test
    fun laterSuccessResetsConsecutiveFailuresButKeepsHistory() {
        val (_, store) = tempStore()
        store.record(
            listOf(
                ResolverOutcome("1.1.1.1", DnsClientEngine.CottenDns, null, ResolverOutcomeType.FAILURE, 1000L),
                ResolverOutcome("1.1.1.1", DnsClientEngine.CottenDns, null, ResolverOutcomeType.INVALID_REPLY, 2000L),
                ResolverOutcome("1.1.1.1", DnsClientEngine.CottenDns, null, ResolverOutcomeType.SUCCESS, 3000L),
            ),
        )

        val record = store.read().single()
        assertEquals(1, record.successfulValidations)
        assertEquals(2, record.failedValidations)
        assertEquals(1, record.invalidReplyFailures)
        assertEquals(0, record.consecutiveFailures)
        assertEquals(3000L, record.lastSuccessAtMillis)
    }

    @Test
    fun boundedStoreEvictsLowestScoredOldestRecords() {
        val (_, store) = tempStore(maxRecords = 2)
        store.replaceForTest(
            listOf(
                ResolverEvidence("1.1.1.1", DnsClientEngine.CottenDns, successfulValidations = 1, lastSuccessAtMillis = 3000L),
                ResolverEvidence("8.8.8.8", DnsClientEngine.CottenDns, successfulValidations = 1, lastSuccessAtMillis = 2000L),
                ResolverEvidence("9.9.9.9", DnsClientEngine.CottenDns, failedValidations = 3, consecutiveFailures = 3, lastFailureAtMillis = 4000L),
            ),
        )

        assertEquals(listOf("1.1.1.1", "8.8.8.8"), store.read().map { it.endpoint }.sorted())
    }

    @Test
    fun customPortIsPartOfEvidenceIdentity() {
        val (_, store) = tempStore()
        store.record(
            listOf(
                ResolverOutcome("1.1.1.1", DnsClientEngine.CottenDns, null, ResolverOutcomeType.SUCCESS, 1000L),
                ResolverOutcome("1.1.1.1:5353", DnsClientEngine.CottenDns, null, ResolverOutcomeType.SUCCESS, 1001L),
            ),
        )
        assertEquals(setOf("1.1.1.1", "1.1.1.1:5353"), store.read().map { it.endpoint }.toSet())
    }
}
