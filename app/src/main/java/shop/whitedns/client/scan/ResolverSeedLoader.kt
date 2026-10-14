package shop.whitedns.client.scan

import android.content.Context
import shop.whitedns.client.model.validateResolverText

object ResolverSeedLoader {
    private const val AssetPath = "resolvers/iran_survival_resolvers.txt"

    fun load(context: Context): List<String> {
        return runCatching {
            context.assets.open(AssetPath)
                .bufferedReader(Charsets.UTF_8)
                .useLines(::parse)
        }.getOrDefault(emptyList())
    }

    internal fun parse(lines: Sequence<String>): List<String> {
        val raw = lines
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .joinToString(separator = "\n")
        if (raw.isEmpty()) return emptyList()
        return validateResolverText(raw).normalizedResolvers.distinct()
    }
}
