package com.kachat.app.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.services.CoinGeckoApi
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The KAS price in the currency picked in Settings > Customization, for the Send screen's
 * KAS / your-currency switch (iOS KaspaPriceStore). KaChat read this off its Portfolio
 * (PortfolioViewModel); the price part is all KaChat Chess needs, so it is all that came along:
 * fetched from CoinGecko, kept across launches, refetched when it is older than five minutes or
 * the currency changes.
 */
@HiltViewModel
class KaspaPriceViewModel @Inject constructor(
    @ApplicationContext context: Context,
    settings: AppSettingsRepository,
    private val coinGecko: CoinGeckoApi,
) : ViewModel() {
    private val prefs = context.getSharedPreferences("kachat_kas_price", Context.MODE_PRIVATE)

    /** The currency code the price is in ("usd", "eur", ...). */
    val currency: StateFlow<String> = settings.currency.stateIn(viewModelScope, SharingStarted.Eagerly, "usd")

    /** KAS price in [currency]; null until the first price arrives. The name is KaChat's, kept so
     *  the copied call sites read the same. */
    private val _currentPriceUsd = MutableStateFlow<Double?>(null)
    val currentPriceUsd: StateFlow<Double?> = _currentPriceUsd.asStateFlow()

    private var fetchJob: Job? = null

    init {
        viewModelScope.launch {
            currency.collect { code ->
                fetchJob?.cancel()
                _currentPriceUsd.value = prefs.getString(priceKey(code), null)?.toDoubleOrNull()
                val fetchedAt = prefs.getLong(fetchedKey(code), 0L)
                val fresh = _currentPriceUsd.value != null && System.currentTimeMillis() - fetchedAt < STALE_MS
                if (!fresh) fetch(code)
            }
        }
    }

    private fun fetch(code: String) {
        fetchJob = viewModelScope.launch {
            // CoinGecko's keyless tier throttles bursts, so a miss is asked again on a growing pause.
            for (pauseSeconds in listOf(0L, 3L, 8L, 20L, 45L)) {
                if (pauseSeconds > 0) delay(pauseSeconds * 1000)
                val price = runCatching { coinGecko.getSimplePrice(vsCurrencies = code).kaspa[code] }.getOrNull()
                if (price != null) {
                    _currentPriceUsd.value = price
                    prefs.edit()
                        .putString(priceKey(code), price.toString())
                        .putLong(fetchedKey(code), System.currentTimeMillis())
                        .apply()
                    break
                }
            }
        }
    }

    private fun priceKey(code: String) = "price_$code"
    private fun fetchedKey(code: String) = "fetched_at_$code"

    private companion object {
        const val STALE_MS = 5 * 60 * 1000L
    }
}
