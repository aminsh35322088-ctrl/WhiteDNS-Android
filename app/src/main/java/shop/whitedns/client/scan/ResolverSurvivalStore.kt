package shop.whitedns.client.scan

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONArray
import org.json.JSONObject
import shop.whitedns.client.model.DnsClientEngine

class ResolverSurvivalStore internal constructor(
    private val file: File,
    private val maxRecords: Int = DefaultMaxRecords,
    private val atomicWrites: Boolean = false,
) {
    constructor(context: Context, maxRecords: Int = DefaultMaxRecords) : this(
        file = File(context.noBackupFilesDir, StoreRelativePath),
        maxRecords = maxRecords,
        atomicWrites = true,
    )

    fun read(): List<ResolverEvidence> = readSnapshot().evidence

    fun readSnapshot(): ResolverSurvivalSnapshot {
        return withStoreLock {
            if (!file.isFile) return@withStoreLock ResolverSurvivalSnapshot(emptyList(), usable = true)
            runCatching {
                val raw = if (atomicWrites) {
                    AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
                } else {
                    file.readText(Charsets.UTF_8)
                }
                ResolverSurvivalSnapshot(decode(raw), usable = true)
            }.getOrElse { ResolverSurvivalSnapshot(emptyList(), usable = false) }
        }
    }

    fun record(outcomes: Iterable<ResolverOutcome>) {
        withStoreLock {
            val snapshot = readSnapshotUnlocked()
            check(snapshot.usable) { "Resolver survival store is corrupt or uses an unsupported schema" }
            val records = snapshot.evidence.associateByTo(linkedMapOf(), ::recordKey)
            outcomes.forEach { outcome ->
                if (outcome.observedAtMillis < 0) return@forEach
                val endpoint = normalizeEvidenceEndpoint(outcome.endpoint) ?: return@forEach
                val engine = DnsClientEngine.normalize(outcome.engine)
                val fingerprintValue = outcome.networkFingerprint?.takeIf { it.length <= MaxFingerprintLength && it.isNotBlank() }
                val scopes = listOf(fingerprintValue, null).distinct()
                scopes.forEach { fingerprint ->
                    val key = recordKey(endpoint, engine, fingerprint)
                    val previous = records[key] ?: ResolverEvidence(
                        endpoint = endpoint,
                        engine = engine,
                        networkFingerprint = fingerprint,
                    )
                    records[key] = applyOutcome(previous, outcome.copy(endpoint = endpoint, engine = engine, networkFingerprint = fingerprint))
                }
            }
            writeBounded(records.values.toList())
        }
    }

    internal fun replaceForTest(records: List<ResolverEvidence>) = withStoreLock {
        writeBounded(records)
    }

    private fun readSnapshotUnlocked(): ResolverSurvivalSnapshot {
        if (!file.isFile) return ResolverSurvivalSnapshot(emptyList(), usable = true)
        return runCatching {
            val raw = if (atomicWrites) AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            else file.readText(Charsets.UTF_8)
            ResolverSurvivalSnapshot(decode(raw), usable = true)
        }.getOrElse { ResolverSurvivalSnapshot(emptyList(), usable = false) }
    }

    private fun <T> withStoreLock(block: () -> T): T {
        val absolutePath = file.absoluteFile.normalize().path
        val processLock = processLocks.computeIfAbsent(absolutePath) { ReentrantLock() }
        return processLock.withLock {
            file.parentFile?.mkdirs()
            val lockFile = File(file.parentFile ?: file.absoluteFile.parentFile, "${file.name}.lock")
            FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { block() }
            }
        }
    }

    private fun writeBounded(records: List<ResolverEvidence>) {
        val now = System.currentTimeMillis()
        val bounded = records
            .distinctBy(::recordKey)
            .sortedWith(
                compareByDescending<ResolverEvidence> { ResolverSurvivalScoring.score(it, null, now) }
                    .thenByDescending { it.lastSuccessAtMillis ?: it.lastFailureAtMillis ?: Long.MIN_VALUE }
                    .thenBy { recordKey(it) },
            )
            .take(maxRecords.coerceAtLeast(0))
        writeText(encode(bounded))
    }

    private fun writeText(raw: String) {
        file.parentFile?.mkdirs()
        if (!atomicWrites) {
            val temp = File(file.parentFile ?: file.absoluteFile.parentFile, "${file.name}.tmp")
            temp.writeText(raw, Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            return
        }

        val atomicFile = AtomicFile(file)
        var stream: FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(raw.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            stream?.let(atomicFile::failWrite)
            throw error
        }
    }

    private fun applyOutcome(previous: ResolverEvidence, outcome: ResolverOutcome): ResolverEvidence {
        val sameTypeTimestamp = when (outcome.type) {
            ResolverOutcomeType.SUCCESS -> previous.lastSuccessAtMillis
            ResolverOutcomeType.FAILURE, ResolverOutcomeType.INVALID_REPLY -> previous.lastFailureAtMillis
        }
        if (sameTypeTimestamp == outcome.observedAtMillis) return previous
        val latestObservedAt = maxOf(
            previous.lastSuccessAtMillis ?: Long.MIN_VALUE,
            previous.lastFailureAtMillis ?: Long.MIN_VALUE,
        )
        if (outcome.observedAtMillis < latestObservedAt) {
            return previous
        }
        if (
            outcome.observedAtMillis == latestObservedAt &&
            previous.lastSuccessAtMillis == latestObservedAt &&
            outcome.type != ResolverOutcomeType.SUCCESS
        ) {
            return previous
        }
        val survival = previous.survivalObserved || outcome.survivalObserved
        return when (outcome.type) {
            ResolverOutcomeType.SUCCESS -> previous.copy(
                successfulValidations = previous.successfulValidations.saturatingIncrement(),
                consecutiveFailures = 0,
                lastSuccessAtMillis = outcome.observedAtMillis,
                latencyMillis = outcome.latencyMillis ?: previous.latencyMillis,
                lossRate = outcome.lossRate ?: previous.lossRate,
                survivalObserved = survival,
                scoreVersion = ResolverSurvivalScoring.ScoreVersion,
            )
            ResolverOutcomeType.FAILURE -> previous.copy(
                failedValidations = previous.failedValidations.saturatingIncrement(),
                consecutiveFailures = previous.consecutiveFailures.saturatingIncrement(),
                lastFailureAtMillis = outcome.observedAtMillis,
                survivalObserved = survival,
                scoreVersion = ResolverSurvivalScoring.ScoreVersion,
            )
            ResolverOutcomeType.INVALID_REPLY -> previous.copy(
                failedValidations = previous.failedValidations.saturatingIncrement(),
                invalidReplyFailures = previous.invalidReplyFailures.saturatingIncrement(),
                consecutiveFailures = previous.consecutiveFailures.saturatingIncrement(),
                lastFailureAtMillis = outcome.observedAtMillis,
                survivalObserved = survival,
                scoreVersion = ResolverSurvivalScoring.ScoreVersion,
            )
        }
    }

    private fun encode(records: List<ResolverEvidence>): String {
        val array = JSONArray()
        records.forEach { evidence ->
            array.put(
                JSONObject()
                    .put("endpoint", evidence.endpoint)
                    .put("engine", evidence.engine)
                    .apply { evidence.networkFingerprint?.let { put("networkFingerprint", it) } }
                    .put("successfulValidations", evidence.successfulValidations)
                    .put("failedValidations", evidence.failedValidations)
                    .put("invalidReplyFailures", evidence.invalidReplyFailures)
                    .put("consecutiveFailures", evidence.consecutiveFailures)
                    .apply { evidence.lastSuccessAtMillis?.let { put("lastSuccessAtMillis", it) } }
                    .apply { evidence.lastFailureAtMillis?.let { put("lastFailureAtMillis", it) } }
                    .apply { evidence.latencyMillis?.let { put("latencyMillis", it) } }
                    .apply { evidence.lossRate?.let { put("lossRate", it) } }
                    .put("survivalObserved", evidence.survivalObserved)
                    .put("scoreVersion", evidence.scoreVersion),
            )
        }
        return JSONObject()
            .put("schemaVersion", SchemaVersion)
            .put("records", array)
            .toString()
    }

    private fun decode(raw: String): List<ResolverEvidence> {
        require(raw.length <= MaxStoreBytes) { "Resolver survival store exceeds size limit" }
        val root = JSONObject(raw)
        require(root.optInt("schemaVersion", -1) == SchemaVersion) { "Unsupported survival schema" }
        val array = root.optJSONArray("records") ?: throw IllegalArgumentException("Missing resolver evidence records")
        require(array.length() <= DefaultMaxRecords) { "Resolver survival record limit exceeded" }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: throw IllegalArgumentException("Invalid resolver evidence record")
                val endpoint = item.optString("endpoint").trim()
                val engineRaw = item.optString("engine").trim()
                require(normalizeEvidenceEndpoint(endpoint) != null && engineRaw.isNotEmpty()) { "Invalid resolver evidence identity" }
                add(
                    ResolverEvidence(
                        endpoint = endpoint,
                        engine = DnsClientEngine.normalize(engineRaw),
                        networkFingerprint = item.nullableString("networkFingerprint"),
                        successfulValidations = item.optInt("successfulValidations", 0).coerceAtLeast(0),
                        failedValidations = item.optInt("failedValidations", 0).coerceAtLeast(0),
                        invalidReplyFailures = item.optInt("invalidReplyFailures", 0).coerceAtLeast(0),
                        consecutiveFailures = item.optInt("consecutiveFailures", 0).coerceAtLeast(0),
                        lastSuccessAtMillis = item.nullableLong("lastSuccessAtMillis"),
                        lastFailureAtMillis = item.nullableLong("lastFailureAtMillis"),
                        latencyMillis = item.nullableLong("latencyMillis"),
                        lossRate = item.nullableDouble("lossRate"),
                        survivalObserved = item.optBoolean("survivalObserved", false),
                        scoreVersion = item.optInt("scoreVersion", ResolverSurvivalScoring.ScoreVersion),
                    ),
                )
            }
        }
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf(String::isNotBlank) else null

    private fun JSONObject.nullableLong(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private fun JSONObject.nullableDouble(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    private fun recordKey(evidence: ResolverEvidence): String =
        recordKey(evidence.endpoint, evidence.engine, evidence.networkFingerprint)

    private fun recordKey(endpoint: String, engine: String, fingerprint: String?): String =
        "${DnsClientEngine.normalize(engine)}\u0000${fingerprint.orEmpty()}\u0000${endpoint.trim()}"

    companion object {
        const val SchemaVersion = 1
        const val DefaultMaxRecords = 512
        private const val StoreRelativePath = "resolver-survival/evidence-v1.json"
        private const val MaxStoreBytes = 1_048_576
        private const val MaxFingerprintLength = 64
        private val processLocks = ConcurrentHashMap<String, ReentrantLock>()
    }
}

private fun normalizeEvidenceEndpoint(raw: String): String? {
    if (raw.length > 255 || raw.contains('/')) return null
    return shop.whitedns.client.model.validateResolverText(raw).normalizedResolvers.singleOrNull()
}

private fun Int.saturatingIncrement(): Int = if (this == Int.MAX_VALUE) this else this + 1
