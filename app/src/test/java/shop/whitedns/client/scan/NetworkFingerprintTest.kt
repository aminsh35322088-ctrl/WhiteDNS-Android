package shop.whitedns.client.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkFingerprintTest {
    @Test
    fun canonicalizesCellularCarrierAndIpFamily() {
        val fingerprint = NetworkFingerprint.fromParts(
            transport = " CELLULAR ",
            carrierCode = "43211",
            hasIpv4 = true,
            hasIpv6 = false,
        )
        assertEquals("cellular:43211:v4", fingerprint?.value)
    }

    @Test
    fun invalidCarrierIsDroppedAndDualStackIsCoarse() {
        val fingerprint = NetworkFingerprint.fromParts(
            transport = "wifi",
            carrierCode = "my-phone-or-ssid",
            hasIpv4 = true,
            hasIpv6 = true,
        )
        assertEquals("wifi:dual", fingerprint?.value)
    }

    @Test
    fun onlyKnownCoarseTransportIsPersistedAndCarrierIsCellularOnly() {
        assertNull(NetworkFingerprint.fromParts("my-private-wifi-name", "43211", true, false))
        assertEquals("wifi:v4", NetworkFingerprint.fromParts("wifi", "43211", true, false)?.value)
    }

    @Test
    fun missingNetworkReturnsNullInsteadOfInventingIdentity() {
        assertNull(NetworkFingerprint.fromParts("", null, false, false))
    }

    @Test
    fun fingerprintApiContainsOnlyCoarseInputs() {
        val fingerprint = NetworkFingerprint.fromParts("vpn", null, false, true)
        assertEquals("vpn:v6", fingerprint?.value)
    }
}
