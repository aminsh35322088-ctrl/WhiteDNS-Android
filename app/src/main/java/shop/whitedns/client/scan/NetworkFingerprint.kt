package shop.whitedns.client.scan

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import java.net.Inet4Address
import java.net.Inet6Address

data class NetworkFingerprint(val value: String) {
    companion object {
        fun fromParts(
            transport: String,
            carrierCode: String?,
            hasIpv4: Boolean,
            hasIpv6: Boolean,
        ): NetworkFingerprint? {
            val normalizedTransport = transport.trim().lowercase().takeIf(String::isNotEmpty) ?: return null
            val normalizedCarrier = carrierCode
                ?.trim()
                ?.takeIf { code -> code.length in 5..6 && code.all(Char::isDigit) }
            val ipFamily = when {
                hasIpv4 && hasIpv6 -> "dual"
                hasIpv4 -> "v4"
                hasIpv6 -> "v6"
                else -> "ip-unknown"
            }
            val parts = buildList {
                add(normalizedTransport)
                normalizedCarrier?.let(::add)
                add(ipFamily)
            }
            return NetworkFingerprint(parts.joinToString(":"))
        }
    }
}

object NetworkFingerprintProvider {
    fun current(context: Context): NetworkFingerprint? {
        return runCatching {
            val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
            val network = connectivity.activeNetwork ?: return null
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
            val transport = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
            val linkProperties = connectivity.getLinkProperties(network)
            val addresses = linkProperties?.linkAddresses.orEmpty().map { it.address }
            val hasIpv4 = addresses.any { it is Inet4Address }
            val hasIpv6 = addresses.any { it is Inet6Address }
            val carrierCode = if (transport == "cellular") {
                runCatching {
                    context.getSystemService(TelephonyManager::class.java)?.networkOperator
                }.getOrNull()
            } else {
                null
            }
            NetworkFingerprint.fromParts(transport, carrierCode, hasIpv4, hasIpv6)
        }.getOrNull()
    }
}
