package shop.whitedns.client.scan

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import shop.whitedns.client.model.ConnectionProfile
import shop.whitedns.client.model.DnsClientEngine
import shop.whitedns.client.model.ResolverProfile
import shop.whitedns.client.model.WhiteDnsSettings
import shop.whitedns.client.model.resolve
import shop.whitedns.client.model.runtimeConnectionSettings

class ResolverSurvivalIntegrationTest {
    private val now = 1_800_000_000_000L

    private fun tempFile(): File = File.createTempFile("survival-integration", ".json").apply {
        delete()
        deleteOnExit()
    }

    @Test
    fun currentNetworkEvidenceIsEmittedBeforeUnprovenResolversAndSurvivesSettingsSync() {
        val file = tempFile()
        val store = ResolverSurvivalStore(file)
        store.record(
            listOf(
                ResolverOutcome("1.1.1.1", DnsClientEngine.CottenDns, "cellular:43211:v4", ResolverOutcomeType.SUCCESS, now - 1000),
                ResolverOutcome("8.8.8.8", DnsClientEngine.CottenDns, null, ResolverOutcomeType.SUCCESS, now - 500),
            ),
        )
        val selection = ResolverSurvivalRuntime.prepare(
            engine = DnsClientEngine.CottenDns,
            originalResolvers = listOf("9.9.9.9"),
            fullPool = listOf("4.4.4.4"),
            seeds = listOf("2.2.2.2"),
            currentNetworkFingerprint = "cellular:43211:v4",
            store = store,
            nowMillis = now,
        )
        assertEquals(
            listOf("1.1.1.1", "8.8.8.8", "2.2.2.2", "9.9.9.9", "4.4.4.4"),
            selection.orderedResolvers,
        )

        val profile = ResolverProfile(id = "r1", name = "manual", resolverText = "9.9.9.9")
        val connection = ConnectionProfile(id = "c1", name = "c", resolverProfileId = "r1", engine = DnsClientEngine.CottenDns)
        val settings = WhiteDnsSettings(
            selectedConnectionProfileId = "c1",
            connectionProfiles = listOf(connection),
            selectedResolverProfileId = "r1",
            resolverProfiles = listOf(profile),
            resolverText = profile.resolverText,
        )
        val runtime = ResolverSurvivalRuntime.applyResolverOrder(settings, selection.orderedResolvers)
            .runtimeConnectionSettings()
            .resolve()
        assertEquals(selection.orderedResolvers, runtime.resolverEntries)
    }

    @Test
    fun failedHotAttemptWidensToEveryLaterCandidate() {
        val store = ResolverSurvivalStore(tempFile())
        val selection = ResolverSurvivalRuntime.prepare(
            engine = DnsClientEngine.CottenDns,
            originalResolvers = listOf("9.9.9.9", "4.4.4.4"),
            fullPool = listOf("5.5.5.5"),
            seeds = listOf("2.2.2.2"),
            currentNetworkFingerprint = null,
            store = store,
            nowMillis = now,
            hotSetSize = 2,
        )

        assertEquals(2, selection.initialResolvers.size)
        assertEquals(listOf("2.2.2.2", "9.9.9.9", "4.4.4.4", "5.5.5.5"), selection.widenedResolvers)
    }

    @Test
    fun learnedEvidenceSurvivesStoreReloadAndChangesOrder() {
        val file = tempFile()
        ResolverSurvivalStore(file).record(
            listOf(ResolverOutcome("1.1.1.1", DnsClientEngine.CottenDns, null, ResolverOutcomeType.SUCCESS, now)),
        )
        val reloaded = ResolverSurvivalStore(file)
        val selection = ResolverSurvivalRuntime.prepare(
            engine = DnsClientEngine.CottenDns,
            originalResolvers = listOf("9.9.9.9"),
            currentNetworkFingerprint = null,
            store = reloaded,
            nowMillis = now,
        )
        assertEquals("1.1.1.1", selection.orderedResolvers.first())
        assertTrue(selection.applied)
    }

    @Test
    fun corruptStoreFailsOpenToOriginalResolverOrder() {
        val file = tempFile().apply { writeText("{broken") }
        val selection = ResolverSurvivalRuntime.prepare(
            engine = DnsClientEngine.CottenDns,
            originalResolvers = listOf("9.9.9.9", "4.4.4.4"),
            fullPool = listOf("5.5.5.5"),
            seeds = listOf("2.2.2.2"),
            currentNetworkFingerprint = "wifi:v4",
            store = ResolverSurvivalStore(file),
            nowMillis = now,
        )
        assertEquals(listOf("9.9.9.9", "4.4.4.4"), selection.orderedResolvers)
        assertFalse(selection.applied)
    }
}
