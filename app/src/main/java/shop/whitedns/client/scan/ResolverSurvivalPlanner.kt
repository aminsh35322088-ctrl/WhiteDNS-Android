package shop.whitedns.client.scan

import shop.whitedns.client.model.DnsClientEngine

object ResolverSurvivalPlanner {
    const val DefaultHotSetSize = 20
    private const val RepeatedFailureDemotionThreshold = 2

    fun plan(
        engine: String,
        currentNetworkFingerprint: String?,
        evidence: List<ResolverEvidence>,
        seeds: List<String> = emptyList(),
        userResolvers: List<String> = emptyList(),
        fullPool: List<String> = emptyList(),
        nowMillis: Long = System.currentTimeMillis(),
        hotSetSize: Int = DefaultHotSetSize,
    ): ResolverSurvivalPlan {
        val normalizedEngine = DnsClientEngine.normalize(engine)
        val candidates = ArrayList<ResolverCandidate>()
        val seen = linkedSetOf<String>()

        fun add(endpoint: String, source: ResolverCandidateSource, score: Int = 0) {
            val normalizedEndpoint = shop.whitedns.client.model.validateResolverText(endpoint.trim())
                .normalizedResolvers.singleOrNull() ?: return
            if (normalizedEndpoint.isEmpty() || !seen.add(normalizedEndpoint)) return
            candidates += ResolverCandidate(normalizedEndpoint, source, score)
        }

        fun provenFor(fingerprint: String?): List<ResolverEvidence> = evidence
            .asSequence()
            .filter { DnsClientEngine.normalize(it.engine) == normalizedEngine }
            .filter { it.successfulValidations > 0 }
            .filter { it.consecutiveFailures < RepeatedFailureDemotionThreshold }
            .filter { it.networkFingerprint == fingerprint }
            .sortedWith(
                compareByDescending<ResolverEvidence> {
                    ResolverSurvivalScoring.score(it, currentNetworkFingerprint, nowMillis)
                }.thenByDescending { it.lastSuccessAtMillis ?: Long.MIN_VALUE }
                    .thenBy { it.endpoint },
            )
            .toList()

        if (currentNetworkFingerprint != null) {
            provenFor(currentNetworkFingerprint).forEach {
                add(
                    endpoint = it.endpoint,
                    source = ResolverCandidateSource.NETWORK_PROVEN,
                    score = ResolverSurvivalScoring.score(it, currentNetworkFingerprint, nowMillis),
                )
            }
        }

        val failedOnCurrentNetwork = if (currentNetworkFingerprint == null) emptySet() else evidence
            .asSequence()
            .filter { DnsClientEngine.normalize(it.engine) == normalizedEngine }
            .filter { it.networkFingerprint == currentNetworkFingerprint }
            .filter { it.consecutiveFailures >= RepeatedFailureDemotionThreshold }
            .map { shop.whitedns.client.model.validateResolverText(it.endpoint).normalizedResolvers.singleOrNull() }
            .filterNotNull()
            .toSet()

        provenFor(null).filterNot { evidence ->
            shop.whitedns.client.model.validateResolverText(evidence.endpoint).normalizedResolvers.singleOrNull() in failedOnCurrentNetwork
        }.forEach {
            add(
                endpoint = it.endpoint,
                source = ResolverCandidateSource.GLOBAL_PROVEN,
                score = ResolverSurvivalScoring.score(it, currentNetworkFingerprint, nowMillis),
            )
        }

        // A user-supplied non-default port denotes a direct resolver/server endpoint,
        // such as BotKeep's allocated UDP ingress. Trying ordinary recursive seeds
        // before it can only add delay because those seeds cannot reach that custom
        // port. Keep these explicit direct endpoints ahead of bootstrap seeds.
        userResolvers.filter(::hasExplicitCustomPort).forEach { add(it, ResolverCandidateSource.USER) }
        seeds.forEach { add(it, ResolverCandidateSource.SEED) }
        userResolvers.filterNot(::hasExplicitCustomPort).forEach { add(it, ResolverCandidateSource.USER) }
        fullPool.forEach { add(it, ResolverCandidateSource.FULL_POOL) }

        val boundedHotSetSize = hotSetSize.coerceAtLeast(0)
        return ResolverSurvivalPlan(
            hotCandidates = candidates.take(boundedHotSetSize),
            remainingCandidates = candidates.drop(boundedHotSetSize),
        )
    }
    private fun hasExplicitCustomPort(endpoint: String): Boolean {
        val value = shop.whitedns.client.model.validateResolverText(endpoint.trim()).normalizedResolvers.singleOrNull()
            ?: return false
        if (value.startsWith("[")) {
            val close = value.lastIndexOf(']')
            return close > 0 && close + 2 < value.length && value[close + 1] == ':' &&
                value.substring(close + 2).all(Char::isDigit)
        }
        if (value.count { it == ':' } != 1) return false
        val port = value.substringAfterLast(':')
        return port.isNotEmpty() && port.all(Char::isDigit)
    }

}
