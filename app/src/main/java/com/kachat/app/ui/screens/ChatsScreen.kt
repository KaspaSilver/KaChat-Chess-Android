package com.kachat.app.ui.screens

import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Settings
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.ArrowCircleUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.navigation.NavController
import coil.compose.SubcomposeAsyncImage
import com.kachat.app.R
import com.kachat.app.ui.theme.IosRoundedFontFamily
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.ui.theme.KaspaSubtext
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.viewmodels.WalletViewModel
import com.kachat.app.viewmodels.ConnectionViewModel
import com.kachat.app.models.displayName
import com.kachat.app.models.MessageEntity
import com.kachat.app.util.ImageMessage
import com.kachat.app.util.MessageReply
import com.kachat.app.util.VoiceMessage
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.animate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Contact avatar — the single place the app's avatar resolution order lives, used everywhere a
 * contact is shown:
 *
 *   1. [imageUrl] — the contact's KNS profile photo (a remote https URL), when they have one.
 *   2. [backupPhotoBase64] — the photo carried in the chat backup (e.g. one set on desktop).
 *   3. the person glyph.
 *
 * KaChat no longer reads the phone's Contacts (iOS 00767a4), so there is no Contacts-app photo.
 * The image goes through Coil (memory/disk caches, off-main-thread decode); one that fails to
 * load falls through to the backup photo rather than dead-ending on the glyph.
 *
 * On testnet identity is `.kachat`: for an [address] the first step is its `.kachat` avatar (from
 * its profile's social link, looked up on this device), never the KNS [imageUrl] - one change for
 * the chat list, chat header, groups, public chats, calls, chess and contacts (iOS e52357d
 * `KNSAvatarView`). Mainnet, or no [address]: [imageUrl] as before.
 */
@Composable
fun ContactAvatar(
    imageUrl: String?,
    fallbackText: String,
    size: Dp,
    modifier: Modifier = Modifier,
    backgroundColor: Color = LocalAppColors.current.surface,
    fontSize: TextUnit = 16.sp,
    backupPhotoBase64: String? = null,
    /** Whose avatar this is (a `kaspa:` / `kaspatest:` address), for the testnet `.kachat` rule. */
    address: String? = null
) {
    val shownUrl = if (address != null && com.kachat.app.services.kachatnames.KachatNamesService.isEnabled) {
        kachatAvatarUrl(address)
    } else {
        imageUrl
    }
    val candidates = remember(shownUrl) {
        listOfNotNull(shownUrl?.takeIf { it.isNotBlank() })
    }
    // Cross-platform backup photo (base64 JPEG); the last fallback before the glyph. Decoded off
    // the main thread and kept app-wide, so opening a list decodes each photo once rather than
    // every visible row on the main thread every time the screen comes back.
    val backupBitmap by produceState(
        initialValue = backupPhotoBase64?.let { backupAvatarCache.get(it) },
        backupPhotoBase64
    ) {
        val base64 = backupPhotoBase64
        if (base64.isNullOrBlank()) { value = null; return@produceState }
        backupAvatarCache.get(base64)?.let { value = it; return@produceState }
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { decodeBase64Avatar(base64) }
            ?.also { backupAvatarCache.put(base64, it) }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        AvatarImageChain(candidates, fallbackText, fontSize, backupBitmap)
    }
}

/**
 * [address]'s `.kachat` avatar - its profile's avatar link (the registry's cached identity) as the
 * platform shows it (the social image cache, looked up when missing or stale). Re-renders when
 * either answer lands. Null when it has none (iOS e52357d; mainnet too since d36fc42, where the
 * identity is profile-only).
 */
@Composable
fun kachatAvatarUrl(address: String): String? {
    val resolver = com.kachat.app.services.kachatnames.KachatSocialImageResolver.instance ?: return null
    val entries by resolver.entries.collectAsState()
    val link = com.kachat.app.services.kachatnames.KachatNamesRegistry.cachedIdentityOf(address)?.profile?.avatar
    LaunchedEffect(link) { resolver.refreshIfStale(link) }
    return resolver.cached(link, entries)?.avatar
}

private val backupAvatarCache = android.util.LruCache<String, ImageBitmap>(64)

private fun decodeBase64Avatar(base64: String?): ImageBitmap? {
    if (base64.isNullOrBlank()) return null
    return try {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        // Backup photos arrive in other devices' archives; the claimed size is not trusted.
        com.kachat.app.util.SafeBitmapDecode.decode(bytes, maxDimension = 512)?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

/**
 * Renders [candidates] in order, dropping to the next on load failure; then the backup photo, then
 * the glyph. A plain AsyncImage over the glyph rather than SubcomposeAsyncImage: subcomposition per
 * avatar is what Coil advises against in scrolling lists, and every list in the app draws these.
 */
@Composable
private fun AvatarImageChain(candidates: List<String>, fallbackText: String, fontSize: TextUnit, backupBitmap: ImageBitmap? = null) {
    var failedCount by remember(candidates) { mutableIntStateOf(0) }
    val current = candidates.getOrNull(failedCount)
    // A new request once User Info > Rescan Profile downloaded this picture again (iOS bc95d17).
    val model = rememberProfileImageModel(current)
    if (current == null) {
        if (backupBitmap != null) {
            Image(
                bitmap = backupBitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            AvatarInitials(fallbackText, fontSize)
        }
        return
    }
    var loaded by remember(current) { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // The glyph shows while the image loads, as the loading slot did before.
        if (!loaded) AvatarInitials(fallbackText, fontSize)
        coil.compose.AsyncImage(
            model = model,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onSuccess = { loaded = true },
            onError = { failedCount++ }
        )
    }
}

@Composable
private fun AvatarInitials(text: String, fontSize: TextUnit) {
    // 4.0 (matches iOS): no photo shows a person glyph, not initials - initials read like
    // random letters for KNS-less addresses and looked inconsistent next to real avatars.
    // iOS KNSAvatarView.fallbackAvatar: a filled person on the accent at 20%.
    Box(
        modifier = Modifier.fillMaxSize().background(KaspaTeal.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Person,
            contentDescription = null,
            tint = KaspaTeal,
            modifier = Modifier.fillMaxSize(0.5f)
        )
    }
}

