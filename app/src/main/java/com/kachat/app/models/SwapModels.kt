package com.kachat.app.models

import androidx.room.Entity

/**
 * Local record of a swap this device initiated, kept for the "Swap History" list — ChangeNOW is
 * the source of truth for the exchange itself, this just remembers it happened and caches the
 * last status we saw so the list has something to show without a network round trip on open.
 */
@Entity(tableName = "swap_transactions", primaryKeys = ["id"])
data class SwapTransactionEntity(
    val id: String, // ChangeNOW exchange id — also the primary key
    val fromTicker: String,
    val fromNetwork: String,
    val toTicker: String,
    val toNetwork: String,
    val fromAmount: String,
    val toAmount: String,
    val payinAddress: String,
    val payoutAddress: String,
    val status: String,
    val createdAtMillis: Long,
    val kasSendTxId: String? = null, // set once this device auto-sent KAS to payinAddress, when KAS was the "from" side
    val addedToPortfolio: Boolean = false // set once the KAS leg of this swap has been recorded as a portfolio transaction
)
