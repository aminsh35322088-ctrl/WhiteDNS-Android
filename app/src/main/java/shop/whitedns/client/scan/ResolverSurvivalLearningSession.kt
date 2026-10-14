package shop.whitedns.client.scan

import shop.whitedns.client.model.DnsClientEngine
import shop.whitedns.client.model.ResolverRuntimeState
import shop.whitedns.client.model.validateResolverText

class ResolverSurvivalLearningSession(
    engine: String,
    private val networkFingerprint: String?,
    private val survivalObserved: Boolean,
) {
    private val engine = DnsClientEngine.normalize(engine)
    private val latestOutcome = mutableMapOf<String, ResolverOutcomeType>()

    @Synchronized
    fun observeScanTelemetry(
        telemetry: StormDnsScanTelemetry,
        observedAtMillis: Long,
    ): List<ResolverOutcome> {
        if (engine != DnsClientEngine.CottenDns) return emptyList()
        return when (telemetry) {
            is StormDnsScanTelemetry.Valid -> outcomeFor(
                telemetry.resolver,
                ResolverOutcomeType.SUCCESS,
                observedAtMillis,
            )
            is StormDnsScanTelemetry.Rejected -> outcomeFor(
                telemetry.resolver,
                ResolverOutcomeType.FAILURE,
                observedAtMillis,
            )
            is StormDnsScanTelemetry.Complete -> emptyList()
        }
    }

    @Synchronized
    fun observeResolverState(
        state: ResolverRuntimeState,
        observedAtMillis: Long,
    ): List<ResolverOutcome> {
        if (engine != DnsClientEngine.CottenDns) return emptyList()
        // In the pinned CottenDNS client, `valid` means MTU-probe capable and can
        // remain populated after a resolver stops being operational. `active`
        // contains connections whose current IsValid state is true.
        return state.activeResolvers.distinct()
            .flatMap { endpoint ->
                outcomeFor(endpoint, ResolverOutcomeType.SUCCESS, observedAtMillis)
            }
    }

    private fun outcomeFor(
        rawEndpoint: String,
        type: ResolverOutcomeType,
        observedAtMillis: Long,
    ): List<ResolverOutcome> {
        val endpoint = normalizeEndpoint(rawEndpoint) ?: return emptyList()
        if (latestOutcome[endpoint] == type) return emptyList()
        latestOutcome[endpoint] = type
        return listOf(
            ResolverOutcome(
                endpoint = endpoint,
                engine = engine,
                networkFingerprint = networkFingerprint,
                type = type,
                observedAtMillis = observedAtMillis,
                survivalObserved = survivalObserved,
            ),
        )
    }

    private fun normalizeEndpoint(raw: String): String? {
        if (raw.length > 255 || raw.contains('/')) return null
        return validateResolverText(raw).normalizedResolvers.singleOrNull()
    }
}
