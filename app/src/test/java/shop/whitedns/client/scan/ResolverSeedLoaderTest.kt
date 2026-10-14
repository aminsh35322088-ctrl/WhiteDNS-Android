package shop.whitedns.client.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolverSeedLoaderTest {
    @Test
    fun parseIgnoresCommentsInvalidRowsAndDuplicates() {
        val seeds = ResolverSeedLoader.parse(
            sequenceOf(
                "# bootstrap hints only",
                "1.1.1.1",
                "1.1.1.1",
                "not-a-resolver",
                "8.8.8.8:70000",
                "",
                "9.9.9.9",
            ),
        )
        assertEquals(listOf("1.1.1.1", "9.9.9.9"), seeds)
    }

    @Test
    fun parseNormalizesDefaultPortAndPreservesNonDefaultPort() {
        val seeds = ResolverSeedLoader.parse(
            sequenceOf(
                "8.8.8.8:53",
                "8.8.4.4:5353",
                "[2001:4860:4860::8888]:5353",
            ),
        )
        assertEquals(
            listOf(
                "8.8.8.8",
                "8.8.4.4:5353",
                "[2001:4860:4860:0:0:0:0:8888]:5353",
            ),
            seeds,
        )
    }
}
