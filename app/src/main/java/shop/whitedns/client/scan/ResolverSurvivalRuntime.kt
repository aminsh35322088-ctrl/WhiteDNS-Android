package shop.whitedns.client.scan

import shop.whitedns.client.model.DnsClientEngine
import shop.whitedns.client.model.WhiteDnsSettings
import shop.whitedns.client.model.selectedConnectionProfile
import shop.whitedns.client.model.syncSelectedConnectionProfileFields

data class ResolverSurvivalRuntimeSelection(
    val applied: Boolean,
    val initialResolvers: List<String>,
    val widenedResolvers: List<String>,
    val orderedResolvers: List<String>,
    val currentNetworkProvenCount: Int,
    val primarySource: ResolverCandidateSource?,
)

object ResolverSurvivalRuntime {
    fun prepare(
        engine: String,
        originalResolvers: List<String>,
        fullPool: List<String> = emptyList(),
        seeds: List<String> = emptyList(),
        currentNetworkFingerprint: String?,
        store: ResolverSurvivalStore,
        nowMillis: Long = System.currentTimeMillis(),
        hotSetSize: Int = ResolverSurvivalPlanner.DefaultHotSetSize,
    ): ResolverSurvivalRuntimeSelection {
        val normalizedEngine = DnsClientEngine.normalize(engine)
        if (normalizedEngine != DnsClientEngine.CottenDns) {
            return originalSelection(originalResolvers, hotSetSize)
        }
        val snapshot = store.readSnapshot()
        if (!snapshot.usable) {
            return originalSelection(originalResolvers, hotSetSize)
        }
        val plan = ResolverSurvivalPlanner.plan(
            engine = normalizedEngine,
            currentNetworkFingerprint = currentNetworkFingerprint,
            evidence = snapshot.evidence,
            seeds = seeds,
            userResolvers = originalResolvers,
            fullPool = fullPool,
            nowMillis = nowMillis,
            hotSetSize = hotSetSize,
        )
        val ordered = plan.allCandidates.map { it.endpoint }
        return ResolverSurvivalRuntimeSelection(
            applied = true,
            initialResolvers = plan.hotCandidates.map { it.endpoint },
            widenedResolvers = ordered,
            orderedResolvers = ordered,
            currentNetworkProvenCount = plan.allCandidates.count { it.source == ResolverCandidateSource.NETWORK_PROVEN },
            primarySource = plan.allCandidates.firstOrNull()?.source,
        )
    }

    fun applyResolverOrder(settings: WhiteDnsSettings, resolvers: List<String>): WhiteDnsSettings {
        if (resolvers.isEmpty()) return settings
        val normalizedSettings = settings.syncSelectedConnectionProfileFields()
        val resolverText = resolvers.joinToString(separator = "\n")
        val selectedConnection = normalizedSettings.selectedConnectionProfile()
        val selectedResolverId = selectedConnection.resolverProfileId
            .ifBlank { normalizedSettings.selectedResolverProfileId }
        val updatedProfiles = normalizedSettings.resolverProfiles.map { profile ->
            if (selectedResolverId.isNotBlank() && profile.id == selectedResolverId) {
                profile.copy(resolverText = resolverText)
            } else {
                profile
            }
        }
        return normalizedSettings.copy(
            resolverText = resolverText,
            resolverProfiles = updatedProfiles,
        )
    }

    private fun originalSelection(
        originalResolvers: List<String>,
        hotSetSize: Int,
    ): ResolverSurvivalRuntimeSelection {
        val ordered = originalResolvers.map(String::trim).filter(String::isNotEmpty).distinct()
        return ResolverSurvivalRuntimeSelection(
            applied = false,
            initialResolvers = ordered.take(hotSetSize.coerceAtLeast(0)),
            widenedResolvers = ordered,
            orderedResolvers = ordered,
            currentNetworkProvenCount = 0,
            primarySource = if (ordered.isEmpty()) null else ResolverCandidateSource.USER,
        )
    }
}
