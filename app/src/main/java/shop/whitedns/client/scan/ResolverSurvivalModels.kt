package shop.whitedns.client.scan

data class ResolverEvidence(
    val endpoint: String,
    val engine: String,
    val networkFingerprint: String? = null,
    val successfulValidations: Int = 0,
    val failedValidations: Int = 0,
    val invalidReplyFailures: Int = 0,
    val consecutiveFailures: Int = 0,
    val lastSuccessAtMillis: Long? = null,
    val lastFailureAtMillis: Long? = null,
    val latencyMillis: Long? = null,
    val lossRate: Double? = null,
    val survivalObserved: Boolean = false,
    val scoreVersion: Int = ResolverSurvivalScoring.ScoreVersion,
)

enum class ResolverOutcomeType {
    SUCCESS,
    FAILURE,
    INVALID_REPLY,
}

data class ResolverOutcome(
    val endpoint: String,
    val engine: String,
    val networkFingerprint: String? = null,
    val type: ResolverOutcomeType,
    val observedAtMillis: Long,
    val latencyMillis: Long? = null,
    val lossRate: Double? = null,
    val survivalObserved: Boolean = false,
)

enum class ResolverCandidateSource {
    NETWORK_PROVEN,
    GLOBAL_PROVEN,
    SEED,
    USER,
    FULL_POOL,
}

data class ResolverCandidate(
    val endpoint: String,
    val source: ResolverCandidateSource,
    val score: Int,
)

data class ResolverSurvivalPlan(
    val hotCandidates: List<ResolverCandidate>,
    val remainingCandidates: List<ResolverCandidate>,
) {
    val allCandidates: List<ResolverCandidate>
        get() = hotCandidates + remainingCandidates

    fun candidatesForValidation(
        validResolverCount: Int,
        minimumValidResolvers: Int,
    ): List<ResolverCandidate> {
        return if (validResolverCount >= minimumValidResolvers.coerceAtLeast(1)) {
            hotCandidates
        } else {
            allCandidates
        }
    }
}

object ResolverSurvivalScoring {
    const val ScoreVersion = 1
    const val SuccessfulValidation = 50
    const val CurrentNetwork = 25
    const val SurvivalObserved = 20
    const val LowLoss = 10
    const val GoodLatency = 10
    const val RecentFailure = -20
    const val InvalidReply = -15
    const val RepeatedFailures = -25

    const val LowLossThreshold = 0.10
    const val GoodLatencyMillis = 500L
    private const val DecayStepDays = 7L
    private const val DecayPointsPerStep = 5
    private const val MaxDecayPoints = 30
    private const val DayMillis = 24L * 60 * 60 * 1000

    fun score(
        evidence: ResolverEvidence,
        currentNetworkFingerprint: String?,
        nowMillis: Long,
    ): Int {
        var score = 0
        if (evidence.successfulValidations > 0) score += SuccessfulValidation
        if (
            currentNetworkFingerprint != null &&
            evidence.networkFingerprint != null &&
            evidence.networkFingerprint == currentNetworkFingerprint
        ) {
            score += CurrentNetwork
        }
        if (evidence.survivalObserved) score += SurvivalObserved
        if (evidence.lossRate != null && evidence.lossRate <= LowLossThreshold) score += LowLoss
        if (evidence.latencyMillis != null && evidence.latencyMillis <= GoodLatencyMillis) score += GoodLatency

        if (evidence.consecutiveFailures > 0) {
            score += RecentFailure
            if (evidence.invalidReplyFailures > 0) score += InvalidReply
            if (evidence.consecutiveFailures >= 2) score += RepeatedFailures
        }

        val lastSuccess = evidence.lastSuccessAtMillis
        if (lastSuccess != null && nowMillis > lastSuccess) {
            val ageDays = (nowMillis - lastSuccess) / DayMillis
            val decay = ((ageDays / DecayStepDays) * DecayPointsPerStep)
                .coerceAtMost(MaxDecayPoints.toLong())
                .toInt()
            score -= decay
        }
        return score
    }
}

data class ResolverSurvivalSnapshot(
    val evidence: List<ResolverEvidence>,
    val usable: Boolean,
)
