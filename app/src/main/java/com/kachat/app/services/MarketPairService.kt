package com.kachat.app.services

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The things a chart can be compared against, one at a time. Bitcoin is a currency the app
 * already knows, so it needs no Yahoo symbol; the rest are quoted in US dollars and divided into
 * KAS/USD. Mirrors iOS's ChartPair (39adefe).
 */
enum class ChartPair(
    val id: String,
    val code: String,
    val title: String,
    val subtitle: String,
    val yahooSymbol: String?,
    /** Written after an amount: "0.0000203 oz". */
    val unitSuffix: String,
) {
    BITCOIN("bitcoin", "BTC", "Bitcoin", "Kaspa priced in bitcoin", null, ""),
    VOO("voo", "VOO", "VOO", "Shares of Vanguard's S&P 500 ETF", "VOO", " VOO"),
    GOLD("gold", "XAU", "Gold", "Troy ounces of gold", "GC=F", " oz"),
    SILVER("silver", "XAG", "Silver", "Troy ounces of silver", "SI=F", " oz");

    companion object {
        fun fromId(id: String?): ChartPair? = entries.firstOrNull { it.id == id }
    }
}
