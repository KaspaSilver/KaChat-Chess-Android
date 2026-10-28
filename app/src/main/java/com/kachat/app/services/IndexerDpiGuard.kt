package com.kachat.app.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.kachat.app.repository.AppSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deep-packet-inspection fallback for the Message Indexer (iOS KasiaAPIClient's DPI mode and
 * DpiModeState). Some networks break HTTP/2 to a host while HTTP/1.1 to it still works. When an
 * indexer request over HTTP/2 times out or loses its connection in a way that looks like that,
 * it is tried again over HTTP/1.1; when that goes through, DPI is suspected and every indexer
 * request on this network goes straight over HTTP/1.1 until the network changes. The
 * Connection Status page says so, and the connection dot turns a darker green.
 *
 * Confidence works as on iOS: HTTP/2 successes build confidence that there is no DPI (large
 * responses more); with little confidence one DPI-like failure is enough to try HTTP/1.1, with
 * high confidence it takes two failures within 25 seconds, the device online, and the indexer's
 * root answering over HTTP/1.1. Only requests to the Message Indexer's host are watched.
 */
@Singleton
class IndexerDpiGuard @Inject constructor(
    @ApplicationContext context: Context,
    settings: AppSettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _dpiSuspected = MutableStateFlow(false)
    /** DPI suspected on the current network: indexer requests are going over HTTP/1.1. */
    val dpiSuspected: StateFlow<Boolean> = _dpiSuspected.asStateFlow()

    @Volatile private var indexerHost: String? = null

    // The network "epoch" (iOS NetworkEpochMonitor): a new default network starts afresh.
    private val lock = Any()
    private var epoch = 0
    private var currentNetwork: Network? = null
    private var forcedHttp1Epoch: Int? = null
    private var noDpiConfidence = 0
    private var failureStreak = 0
    private var lastFailureAt = 0L
    private var lastRootCheck: Triple<Int, Long, Boolean>? = null

    /** The same client without this guard, speaking HTTP/1.1 only - set by [wrap]. */
    private var http1Client: OkHttpClient? = null

    init {
        scope.launch {
            settings.indexerUrl.collect { url ->
                indexerHost = url.toHttpUrlHostOrNull()
            }
        }
        runCatching {
            connectivityManager?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    synchronized(lock) {
                        if (network == currentNetwork) return
                        currentNetwork = network
                        resetLocked()
                    }
                }
            })
        }.onFailure { Log.w(TAG, "Could not watch the network: ${it.message}") }
    }

    /** [base] with this guard installed; the HTTP/1.1 retry client is built from [base] itself. */
    fun wrap(base: OkHttpClient): OkHttpClient {
        http1Client = base.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()
        return base.newBuilder().addInterceptor(Interceptor { chain -> intercept(chain) }).build()
    }

    private fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val http1 = http1Client
        if (http1 == null || !request.url.isHttps || request.url.host != indexerHost) return chain.proceed(request)
        val requestEpoch = synchronized(lock) { epoch }
        if (synchronized(lock) { forcedHttp1Epoch == requestEpoch }) {
            return http1.newCall(request).execute()
        }
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            if (!isDpiLike(e) || !shouldTryHttp1(request, requestEpoch)) throw e
            Log.w(TAG, "DPI-like failure on ${request.url.host}; trying HTTP/1.1: $e")
            val retried = http1.newCall(request).execute()
            markHttp1(requestEpoch)
            return retried
        }
        recordHttp2Success(response.body?.contentLength() ?: 0L)
        return response
    }

    private fun recordHttp2Success(bytes: Long) = synchronized(lock) {
        failureStreak = 0
        lastFailureAt = 0L
        var gain = CONFIDENCE_GAIN_SUCCESS
        if (bytes >= LARGE_RESPONSE_BYTES) gain += CONFIDENCE_GAIN_LARGE_RESPONSE
        noDpiConfidence = minOf(100, noDpiConfidence + gain)
    }

    /** iOS recordFailureAndEvaluateHTTP1Fallback, then the root probe when it asks for one. */
    private fun shouldTryHttp1(request: Request, requestEpoch: Int): Boolean {
        val requireRootProbe: Boolean
        synchronized(lock) {
            val now = System.currentTimeMillis()
            failureStreak = if (lastFailureAt != 0L && now - lastFailureAt <= FAILURE_WINDOW_MS) failureStreak + 1 else 1
            lastFailureAt = now
            if (noDpiConfidence < HIGH_CONFIDENCE) {
                requireRootProbe = false
            } else {
                if (!isOnline()) return false
                if (failureStreak < FAILURES_WHEN_CONFIDENT) return false
                requireRootProbe = true
            }
        }
        return !requireRootProbe || rootReachable(request, requestEpoch)
    }

    /** The indexer's root over HTTP/1.1 (any HTTP status counts); a good answer is reused for a
     *  short while, a failed one never. */
    private fun rootReachable(request: Request, requestEpoch: Int): Boolean {
        synchronized(lock) {
            lastRootCheck?.let { (checkEpoch, at, ok) ->
                if (ok && checkEpoch == requestEpoch && System.currentTimeMillis() - at < ROOT_CHECK_REUSE_MS) return true
            }
        }
        val client = http1Client?.newBuilder()?.callTimeout(6, TimeUnit.SECONDS)?.build() ?: return false
        val root = request.url.newBuilder().encodedPath("/").query(null).build()
        val ok = runCatching { client.newCall(Request.Builder().url(root).build()).execute().use { it.code in 200..599 } }
            .getOrDefault(false)
        synchronized(lock) { lastRootCheck = Triple(requestEpoch, System.currentTimeMillis(), ok) }
        return ok
    }

    private fun markHttp1(requestEpoch: Int) {
        synchronized(lock) {
            if (epoch != requestEpoch) return
            forcedHttp1Epoch = requestEpoch
            failureStreak = 0
            lastFailureAt = 0L
        }
        _dpiSuspected.value = true
        Log.w(TAG, "DPI suspected - indexer requests go over HTTP/1.1 on this network")
    }

    private fun resetLocked() {
        epoch += 1
        forcedHttp1Epoch = null
        noDpiConfidence = 0
        failureStreak = 0
        lastFailureAt = 0L
        lastRootCheck = null
        _dpiSuspected.value = false
    }

    private fun isOnline(): Boolean {
        val cm = connectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** A timeout or a dropped connection - what iOS reads as `.timedOut` / `.networkConnectionLost`. */
    private fun isDpiLike(e: IOException): Boolean = when {
        e is InterruptedIOException -> true // SocketTimeoutException included
        e is EOFException -> true
        e is SocketException -> true
        e.javaClass.name == "okhttp3.internal.http2.StreamResetException" -> true
        e.message?.contains("unexpected end of stream", ignoreCase = true) == true -> true
        else -> false
    }

    private fun String.toHttpUrlHostOrNull(): String? = toHttpUrlOrNull()?.host

    private companion object {
        const val TAG = "IndexerDpiGuard"
        const val LARGE_RESPONSE_BYTES = 2 * 1024L
        const val CONFIDENCE_GAIN_SUCCESS = 15
        const val CONFIDENCE_GAIN_LARGE_RESPONSE = 25
        const val HIGH_CONFIDENCE = 70
        const val FAILURE_WINDOW_MS = 25_000L
        const val FAILURES_WHEN_CONFIDENT = 2
        const val ROOT_CHECK_REUSE_MS = 20_000L
    }
}
