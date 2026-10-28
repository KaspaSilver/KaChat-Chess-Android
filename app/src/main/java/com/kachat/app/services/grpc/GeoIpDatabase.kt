package com.kachat.app.services.grpc

import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Where an IPv4 address is, from the bundled database. */
data class GeoIpLookup(
    val latitude: Double,
    val longitude: Double,
    val countryCode: String?,
    val asn: String?,
)

/**
 * The bundled IPv4 -> place database (assets/geoip-lite.json, the iPhone app's
 * Resources/geoip-lite.json; iOS NodeProfiler's LocalGeoIPDatabase). Nothing is looked up over
 * the network: a node the database does not know simply has no place. Loaded once, on first use,
 * off the main thread; ranges are kept sorted for a binary search.
 */
class GeoIpDatabase(private val context: Context) {
    private class Range(val start: Long, val end: Long, val lookup: GeoIpLookup)

    @Volatile private var ranges: List<Range>? = null
    @Volatile private var loading = false
    private val lock = Any()

    /** Starts loading the database in the background (it is 50,000 ranges of JSON). */
    fun preload() {
        if (ranges != null || loading) return
        loading = true
        Thread({ load() }, "GeoIpDatabase").apply { isDaemon = true }.start()
    }

    /**
     * Null for an IPv6 address, a host name, or an address the database does not cover - and
     * until the database has loaded: a lookup never waits for it (it starts the load instead).
     */
    fun lookup(ip: String): GeoIpLookup? {
        val target = ipv4ToLong(ip) ?: return null
        val all = ranges ?: return null.also { preload() }
        var low = 0
        var high = all.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val entry = all[mid]
            when {
                target < entry.start -> high = mid - 1
                target > entry.end -> low = mid + 1
                else -> return entry.lookup
            }
        }
        return null
    }

    private fun load(): List<Range> = synchronized(lock) {
        ranges?.let { return it }
        val built = runCatching {
            context.assets.open(ASSET).bufferedReader().use { reader ->
                val array = JsonParser.parseReader(reader).asJsonArray
                val list = ArrayList<Range>(array.size())
                for (element in array) {
                    val item = element.asJsonObject
                    fun string(key: String) = item.get(key)?.takeIf { !it.isJsonNull }?.asString
                    fun double(key: String) = item.get(key)?.takeIf { !it.isJsonNull }?.asDouble
                    val latitude = double("latitude") ?: double("lat") ?: continue
                    val longitude = double("longitude") ?: double("lon") ?: continue
                    val bounds = string("cidr")?.let(::cidrRange)
                        ?: run {
                            val start = string("startIp")?.let(::ipv4ToLong)
                            val end = string("endIp")?.let(::ipv4ToLong)
                            if (start != null && end != null && start <= end) start to end else null
                        }
                        ?: continue
                    list.add(
                        Range(
                            bounds.first, bounds.second,
                            GeoIpLookup(latitude, longitude, string("countryCode") ?: string("country_code"), string("asn")),
                        )
                    )
                }
                list.sortBy { it.start }
                list
            }
        }.getOrElse {
            Log.w(TAG, "Could not load $ASSET", it)
            emptyList()
        }
        ranges = built
        built
    }

    companion object {
        private const val TAG = "GeoIpDatabase"
        private const val ASSET = "geoip-lite.json"

        /** Great-circle distance in km (iOS NodeProfiler.haversineDistanceKm). */
        fun haversineDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val earthRadiusKm = 6_371.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return earthRadiusKm * 2 * atan2(sqrt(a), sqrt(1 - a))
        }

        /** The host of "ip:port" or "[v6]:port" (or a bare address). */
        fun hostOf(address: String): String = when {
            address.startsWith("[") -> address.substringAfter("[").substringBefore("]")
            address.count { it == ':' } == 1 -> address.substringBefore(":")
            else -> address
        }

        internal fun ipv4ToLong(ip: String): Long? {
            val parts = ip.trim().split(".")
            if (parts.size != 4) return null
            var value = 0L
            for (part in parts) {
                val octet = part.toIntOrNull() ?: return null
                if (octet !in 0..255) return null
                value = (value shl 8) or octet.toLong()
            }
            return value
        }

        internal fun cidrRange(cidr: String): Pair<Long, Long>? {
            val base = ipv4ToLong(cidr.substringBefore("/")) ?: return null
            val bits = cidr.substringAfter("/", "32").toIntOrNull()?.takeIf { it in 0..32 } ?: return null
            val size = 1L shl (32 - bits)
            val start = base and (0xFFFFFFFFL xor (size - 1))
            return start to (start + size - 1)
        }
    }
}
