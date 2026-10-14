package shop.whitedns.client.scan

import shop.whitedns.client.model.DnsClientEngine

object ResolverSurvivalPlanner {
    const val DefaultHotSetSize = 20

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
            val normalizedEndpoint = endpoint.trim()
            if (normalizedEndpoint.isEmpty() || !seen.add(normalizedEndpoint)) return
            candidates += ResolverCandidate(normalizedEndpoint, source, score)
        }

        fun provenFor(fingerprint: String?): List<ResolverEvidence> = evidence
            .asSequence()
            .filter { DnsClientEngine.normalize(it.engine) == normalizedEngine }
            .filter { it.successfulValidations > 0 }
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

        provenFor(null).forEach {
            add(
                endpoint = it.endpoint,
                source = ResolverCandidateSource.GLOBAL_PROVEN,
                score = ResolverSurvivalScoring.score(it, currentNetworkFingerprint, nowMillis),
            )
        }
        seeds.forEach { add(it, ResolverCandidateSource.SEED) }
        userResolvers.forEach { add(it, ResolverCandidateSource.USER) }
        fullPool.forEach { add(it, ResolverCandidateSource.FULL_POOL) }

        val boundedHotSetSize = hotSetSize.coerceAtLeast(0)
        return ResolverSurvivalPlan(
            hotCandidates = candidates.take(boundedHotSetSize),
            remainingCandidates = candidates.drop(boundedHotSetSize),
        )
    }
}
