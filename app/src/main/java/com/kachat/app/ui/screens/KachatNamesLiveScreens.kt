package com.kachat.app.ui.screens

import com.kachat.app.ui.theme.iosShadow
import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.R
import com.kachat.app.models.ContactEntity
import com.kachat.app.repository.ChatRepository
import com.kachat.app.services.WalletManager
import com.kachat.app.services.WalletService
import com.kachat.app.services.kachatnames.Event
import com.kachat.app.services.kachatnames.GapInfo
import com.kachat.app.services.kachatnames.KachatNames
import com.kachat.app.services.kachatnames.KachatNamesActions
import com.kachat.app.services.kachatnames.KachatNamesRegistry
import com.kachat.app.services.kachatnames.KachatNamesService
import com.kachat.app.services.kachatnames.KachatSocialImageResolver
import com.kachat.app.services.kachatnames.Lookup
import com.kachat.app.services.kachatnames.NameInfo
import com.kachat.app.services.kachatnames.OfferInfo
import com.kachat.app.services.kachatnames.Params
import com.kachat.app.services.kachatnames.PendingRegistration
import com.kachat.app.services.kachatnames.Plan
import com.kachat.app.services.kachatnames.Profile
import com.kachat.app.services.kachatnames.SocialProfile
import com.kachat.app.services.kachatnames.SocialSource
import com.kachat.app.services.kachatnames.Status
import com.kachat.app.services.kachatnames.nowMs
import com.kachat.app.ui.theme.IosActivityIndicator
import com.kachat.app.ui.theme.IosAlertDialog
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaUnit
import com.kachat.app.util.authenticateWithDeviceCredential
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

// ---------------------------------------------------------------------------------------------
// The live `.kachat` screens, where the registry is live (testnet-10, and mainnet since iOS ef6b21e;
// with a verified registry manifest) - a port
// of iOS KaChat/Views/Ecosystem/KachatNamesLiveViews.swift (KaChat 5df42b4): the hub's search,
// registrations in flight, Marketplace / My Names / Activity, the name detail with its actions,
// every transaction sheet, Your Domains > .kachat and the address profile editor. Where the
// registry isn't launched, the same screens show empty under "Coming soon" (iOS 7227d69). Every spending or destructive action shows its cost first, asks to confirm, then
// passes the device's own lock (authenticateWithDeviceCredential, iOS DeviceAuth) before anything
// is signed.
//
// iOS presents its sheets as full-height page sheets with Cancel top left; here, as in the rest of
// the .kachat port (KachatFormSheet), a sheet is a full-screen swap with Cancel top left.
// ---------------------------------------------------------------------------------------------

// MARK: - Amounts

/** "35 TKAS", "0.2 TKAS", "1.99831 TKAS": exact, trailing zeros dropped (iOS `KaspaUnit.amount`). */
fun KaspaUnit.amount(sompi: Long): String = "${plain(sompi)} $symbol"

/** "+1.99 TKAS" / "-36.002 TKAS". */
fun KaspaUnit.signed(delta: Long): String = if (delta >= 0) "+${amount(delta)}" else "-${amount(-delta)}"

// MARK: - Shared pieces

object KachatLive {
    /** The registry is live on this network (testnet-10 and mainnet) - reads and actions run. Not
     *  the .kachat UI, which is on everywhere ([KachatNamesService.isEnabled], iOS 7227d69). */
    val isEnabled: Boolean get() = KachatNamesService.isLaunched

    /** testnet-10 and mainnet run at 10 blocks per second */
    const val DAA_PER_SECOND: Long = 10

    /**
     * MAINNET.md C6 (iOS 0312a3f `KachatLiveNameDetail.currentOffers`): an offer is bound to a
     * seller key, so one made to this owner before the name last left them - and came back - could
     * be accepted again. Only offers made since the current owner got the name are shown for them;
     * others to other sellers (declined, on their way back to the buyer) stay.
     */
    fun currentOffers(offers: List<OfferInfo>, owner: ByteArray, history: List<Event>): List<OfferInfo> {
        val ownershipOps = setOf("register", "import", "transfer", "sale", "offer_accepted", "offer_accept")
        val since = history.filter { it.op in ownershipOps }.mapNotNull { it.at }.maxOrNull() ?: return offers
        return offers.filter { o ->
            val created = o.createdAt
            if (!o.seller.contentEquals(owner) || created == null) return@filter true
            created >= since
        }
    }

    /** An event party: an address (indexer) or an x-only key in hex (walker), as a short address. */
    fun party(s: String?): String? {
        if (s.isNullOrEmpty()) return null
        if (s.startsWith("kaspa")) return KachatNamesRegistry.shortAddress(s)
        val key = runCatching { KachatNames.unhex32(s) }.getOrNull()
        val a = key?.let { KachatNamesRegistry.address(it) }
        return a?.let { KachatNamesRegistry.shortAddress(it) } ?: s
    }

    fun eventIcon(op: String): ImageVector = when (op) {
        "register" -> KachatSymbols.AtBadgePlus
        "transfer" -> Icons.Default.SwapHoriz
        "list" -> Icons.Outlined.Sell
        "delist" -> KachatSymbols.TagSlash
        "sale", "offer_accepted" -> Icons.Outlined.ShoppingCart
        "extend" -> KachatSymbols.CalendarBadgePlus
        "renew" -> Icons.Default.Refresh
        "release" -> Icons.AutoMirrored.Filled.Undo
        "reclaim" -> Icons.Default.Recycling
        // registry v5's import from the old registry (iOS dd836cb `arrow.down.doc`)
        "import" -> Icons.Outlined.FileDownload
        else -> Icons.Outlined.PanTool
    }

    @StringRes
    fun eventTitle(op: String): Int = when (op) {
        "register" -> R.string.kn_ev_registered
        "transfer" -> R.string.kn_ev_transferred
        "list" -> R.string.kn_ev_listed
        "delist" -> R.string.kn_ev_delisted
        "sale" -> R.string.kn_ev_sold
        "offer_accepted", "offer_accept" -> R.string.kn_ev_offer_accepted
        "extend" -> R.string.kn_ev_extended
        "renew" -> R.string.kn_ev_renewed
        "release" -> R.string.kn_ev_released
        "reclaim" -> R.string.kn_ev_reclaimed
        "import" -> R.string.kn_ev_imported
        "offer" -> R.string.kn_ev_offer_made
        "offer_withdraw" -> R.string.kn_ev_offer_withdrawn
        "offer_refund" -> R.string.kn_ev_offer_refunded
        "offer_decline" -> R.string.kn_ev_offer_declined
        else -> R.string.km_activity
    }

    /** Why a typed name is not a name. */
    @StringRes
    fun invalidReason(name: String): Int? {
        val b = name.toByteArray(Charsets.UTF_8)
        if (b.isEmpty() || b.size > 32) return R.string.kn_invalid_length
        if (!b.all { val x = it.toInt() and 0xff; x in 0x61..0x7a || x in 0x30..0x39 || x == 0x2d }) return R.string.kn_invalid_chars
        if (b.first().toInt() == 0x2d || b.last().toInt() == 0x2d) return R.string.kn_invalid_hyphen
        return null
    }

    /** iOS `.dateTime.year().month().day()` / `.formatted(date: .abbreviated)` / `KachatLive.day`:
     *  "Oct 2, 2026". */
    fun date(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

    /**
     * A unix-ms day as a row value ("Oct 12, 2027"), with the time when it is within two days
     * (testnet's 24-hour periods, or a renewal that opens tomorrow): iOS 49c0baa `KachatLive.day`
     * and `KachatNamesActions.dayString`.
     */
    fun day(ms: Long): String =
        if (kotlin.math.abs(ms - KachatNames.nowMs()) < 2 * 86_400_000L) {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
        } else {
            date(ms)
        }

    /** The registry parameters, once the manifest is verified (iOS `KachatLive.params`, bd2c54a). */
    fun params(service: KachatNamesService): Params? = service.manifest.value?.params

    /** Whether a period is a year (mainnet), not a short test clock (testnet's 24 hours; iOS 49c0baa). */
    fun yearlyPeriods(p: Params?): Boolean = (p?.periodMs ?: KachatNames.YEAR_MS) == KachatNames.YEAR_MS

    /**
     * A length of time ("10m", "10d") in the app's language (iOS 49c0baa `KachatLive.duration`):
     * days from a day up, else hours and minutes from an hour up, else minutes; zero units dropped.
     */
    fun duration(ms: Long, context: Context): String {
        val s = ms / 1000
        val parts = when {
            s >= 86_400 -> listOf(KachatNamesActions.TimeLeftUnit.DAY to s / 86_400)
            s >= 3600 -> listOf(KachatNamesActions.TimeLeftUnit.HOUR to s / 3600, KachatNamesActions.TimeLeftUnit.MINUTE to (s % 3600) / 60)
                .filter { it.second > 0 }
            else -> listOf(KachatNamesActions.TimeLeftUnit.MINUTE to s / 60)
        }
        return timeLeft(parts, context)
    }

    /**
     * What registering [name] costs for its first period (registry v4: fixed, baked into the
     * pinned templates; iOS c8f1086 `KachatLive.price`). Each further period costs [renewPrice].
     */
    fun price(registry: KachatNamesRegistry, name: String): Long? {
        val prices = registry.registerPrices ?: return null
        if (prices.size != 5) return null
        return prices[KachatNames.Codec.tier(name.toByteArray(Charsets.UTF_8).size)]
    }

    /** What one more period of [name] costs: extend, renew, and registering past the first period
     *  (iOS c8f1086 `KachatLive.renewPrice`). */
    fun renewPrice(registry: KachatNamesRegistry, name: String): Long? {
        val prices = registry.renewPrices ?: return null
        if (prices.size != 5) return null
        return prices[KachatNames.Codec.tier(name.toByteArray(Charsets.UTF_8).size)]
    }

    /** Whether extending [info] by [years] fills its period to exactly `maxYears` (iOS
     *  `KachatExtendSheet.fillsPeriod`, bd2c54a; periodMs from 49c0baa). */
    fun fillsPeriod(info: NameInfo, years: Long, p: Params): Boolean {
        val start = info.periodStart ?: return false
        return info.expiresAt + years * p.periodMs == start + p.maxYears * p.periodMs
    }

    /**
     * "2d 4h" / "3h 12m" / "5m" in the app's language: iOS's abbreviated `DateComponentsFormatter`
     * (ba07975 `KachatOfferRow.expiresIn`, now 7f50e84 `KachatOfferState.timeLeft`) is ICU's narrow measure format.
     */
    fun timeLeft(parts: List<Pair<KachatNamesActions.TimeLeftUnit, Long>>, context: Context): String {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val measures = parts.map { (unit, value) ->
            android.icu.util.Measure(
                value,
                when (unit) {
                    KachatNamesActions.TimeLeftUnit.DAY -> android.icu.util.MeasureUnit.DAY
                    KachatNamesActions.TimeLeftUnit.HOUR -> android.icu.util.MeasureUnit.HOUR
                    KachatNamesActions.TimeLeftUnit.MINUTE -> android.icu.util.MeasureUnit.MINUTE
                }
            )
        }
        // One formatter per language, not one per call: the Expired page ticks every tile's
        // countdown once a second (iOS defda2e). Formatted under the lock, as ICU formatters are
        // not documented as safe to share across threads.
        synchronized(measureFormats) {
            val format = measureFormats.getOrPut(locale) {
                android.icu.text.MeasureFormat.getInstance(locale, android.icu.text.MeasureFormat.FormatWidth.NARROW)
            }
            return format.formatMeasures(*measures.toTypedArray())
        }
    }

    private val measureFormats = HashMap<Locale, android.icu.text.MeasureFormat>()

    /**
     * Time left until a moment, for a live countdown (iOS cb3c27d `KachatLive.countdown`): "2d 5h"
     * while days remain, else "1:04:09" or "4:09" (hours, minutes, seconds).
     */
    fun countdown(ms: Long, context: Context): String {
        val s = maxOf(ms, 0L) / 1000
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        return when {
            s >= 86_400 -> timeLeft(
                listOf(KachatNamesActions.TimeLeftUnit.DAY to s / 86_400, KachatNamesActions.TimeLeftUnit.HOUR to (s % 86_400) / 3600)
                    .filter { it.second > 0 },
                context
            )
            s >= 3600 -> String.format(locale, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
            else -> String.format(locale, "%d:%02d", s / 60, s % 60)
        }
    }

    /** iOS `.relative(presentation: .named)`: "2 hours ago", "yesterday". */
    fun relative(ms: Long): String =
        DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    /** What the transaction does to the wallet: its outputs to the wallet minus its inputs from it. */
    fun balanceChange(plan: Plan, me: ByteArray): Long {
        val mine = KachatNames.Codec.p2pkScript(me)
        val received = plan.unsignedTx.outputs.filter { it.script.contentEquals(mine) }.sumOf { it.value }
        val spent = plan.entries.filter { it.script.contentEquals(mine) }.sumOf { it.amount }
        return received - spent
    }
}

/** One line that shrinks to fit rather than being cut, down to [minScale] of its size (iOS
 *  `.lineLimit(1).minimumScaleFactor`). */
@Composable
internal fun KachatFitText(
    text: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit = 15.sp,
    fontWeight: FontWeight = FontWeight.SemiBold,
    fontFamily: FontFamily? = null,
    minScale: Float = 0.7f,
    textAlign: TextAlign? = null,
    modifier: Modifier = Modifier,
) {
    var size by remember(text) { mutableStateOf(fontSize) }
    Text(
        text,
        color = color,
        fontSize = size,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        maxLines = 1,
        softWrap = false,
        textAlign = textAlign,
        modifier = modifier,
        onTextLayout = { result ->
            if (result.hasVisualOverflow && size.value > fontSize.value * minScale) {
                size = (size.value * 0.94f).coerceAtLeast(fontSize.value * minScale).sp
            }
        },
    )
}

