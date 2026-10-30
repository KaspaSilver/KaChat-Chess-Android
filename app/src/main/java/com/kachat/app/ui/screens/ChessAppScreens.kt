package com.kachat.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kachat.app.R
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.AppVersion
import com.kachat.app.util.ChessLeaderboardPeriod
import com.kachat.app.util.authenticateWithDeviceCredential
import com.kachat.app.viewmodels.WalletViewModel

// KaChat Chess's own tab screens (iOS App/ChessMainTabView.swift, Views/Settings/SettingsView.swift's
// SettingsView and Views/Chess/ChessPlayerView.swift): the Leaderboard and Wallet tabs, the short
// Settings list, and a player's card.

/**
 * The Leaderboard tab: both boards, 1v1 and Tournaments, each for this month or all time. The rows
 * are Chess Online's own ([ChessLeaderboardRows]).
 */
@Composable
fun ChessLeaderboardTabScreen(navController: NavController) {
    val vm: ChessTournamentViewModel = hiltViewModel()
    // Reads the arena while the board is up, the same as the Play screens do.
    HoldArena(vm.service)
    var mode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(ChessLobbyMode.DUEL) }
    Scaffold(
        containerColor = LocalAppColors.current.background,
        contentWindowInsets = WindowInsets(0),
        topBar = { ChessHomeBar(onBack = null) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            UnderlineTabBar(
                titles = ChessLobbyMode.entries.map { it.label },
                selectedIndex = mode.ordinal,
                onSelect = { mode = ChessLobbyMode.entries[it] },
            )
            key(mode) { ChessLeaderboardRows(mode, navController) }
        }
    }
}

/**
 * The Wallet tab: the one address every move is signed by - its balance, history, UTXOs, Receive
 * and Send (iOS ChessWalletView).
 */
@Composable
fun ChessWalletScreen(walletViewModel: WalletViewModel) {
    IdentityAddressDetailScreen(onBack = null, viewModel = walletViewModel, title = "Wallet")
}

/**
 * The Settings tab: KaChat Chess's own short list. The pages behind it are KaChat's app-wide ones
 * (Customization, Security, Connection); the account actions that live on KaChat's Profile screen
 * (seed phrase, Log Out) sit here, since there is no Profile.
 */
@Composable
fun ChessSettingsScreen(navController: NavController, walletViewModel: WalletViewModel) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val biometricSeedPhraseEnabled by walletViewModel.biometricSeedPhraseEnabled.collectAsState()
    val address by walletViewModel.address.collectAsState()
    val deleteError by walletViewModel.deleteAccountError.collectAsState()
    var showLogoutConfirmation by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(deleteError) {
        deleteError?.let {
            IosToasts.error("Couldn't remove the account: $it")
            walletViewModel.clearDeleteAccountError()
        }
    }

    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = WindowInsets(0),
        topBar = { ChessHomeBar(onBack = null) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                // The first card 67 under the bar's centre, as on the iPhone (insetGrouped's top
                // margin under an inline title).
                .padding(top = 16.dp, bottom = LocalDockInset.current + 24.dp),
        ) {
            SettingsFormSection(title = null, first = true) {
                SettingsNavigationItem(stringResource(R.string.customization), Icons.Outlined.Brush, onClick = { navController.navigate("chess_settings/customization") })
                SettingsDivider(inset = IosRowTextInset)
                SettingsNavigationItem(stringResource(R.string.security), com.kachat.app.ui.theme.IosSymbols.LockShield, onClick = { navController.navigate("chess_settings/security") })
                SettingsDivider(inset = IosRowTextInset)
                SettingsNavigationItem(stringResource(R.string.connection), Icons.Outlined.CellTower, onClick = { navController.navigate("chess_settings/connection") })
            }
            SettingsFormSection(title = "Account") {
                // Straight into the seed phrase, behind the biometric gate when enabled.
                SettingsActionItem(stringResource(R.string.view_seed_phrase), Icons.Outlined.Key, colors.danger) {
                    if (biometricSeedPhraseEnabled) {
                        context.authenticateWithDeviceCredential(
                            title = "Unlock to View Seed Phrase",
                            onSuccess = { navController.navigate("seed_phrase") },
                        )
                    } else {
                        navController.navigate("seed_phrase")
                    }
                }
                SettingsDivider(inset = IosRowTextInset)
                SettingsActionItem(stringResource(R.string.log_out), Icons.AutoMirrored.Filled.Logout, colors.danger) {
                    showLogoutConfirmation = true
                }
                SettingsDivider(inset = IosRowTextInset)
                SettingsActionItem("Remove Account from This Phone", Icons.Outlined.Delete, colors.danger) {
                    showDeleteConfirmation = true
                }
            }
            SettingsFormSection(title = stringResource(R.string.about)) {
                SettingsInfoItem(stringResource(R.string.version), AppVersion.display)
                SettingsDivider()
                Text(
                    stringResource(R.string.open_source_licenses),
                    color = KaspaTeal,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navController.navigate("open_source_licenses") }
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }

    if (showLogoutConfirmation) {
        ConfirmActionSheet(
            title = stringResource(R.string.log_out),
            confirmTitle = stringResource(R.string.log_out),
            confirmSubtitle = "Signs out of this account. Its keys stay on this phone.",
            confirmIcon = Icons.AutoMirrored.Filled.Logout,
            onConfirm = { walletViewModel.logout() },
            onDismiss = { showLogoutConfirmation = false },
        )
    }
    if (showDeleteConfirmation) {
        ConfirmActionSheet(
            title = "Remove Account",
            confirmTitle = "Remove Account",
            confirmSubtitle = "Deletes this account's keys from this phone. Without your seed phrase it cannot be recovered.",
            confirmIcon = Icons.Outlined.Delete,
            onConfirm = { address?.let { walletViewModel.deleteWallet(it) } },
            onDismiss = { showDeleteConfirmation = false },
        )
    }
}

/** Settings' pages: Customization (Appearance, Language, Currency), Security, Connection. */
@Composable
fun ChessSettingsPage(page: String, navController: NavController, walletViewModel: WalletViewModel) {
    val onBack: () -> Unit = { navController.popBackStack() }
    when (page) {
        "customization" -> SettingsFormPage(title = stringResource(R.string.customization), onBack = onBack) {
            SettingsFormSection(title = stringResource(R.string.customization)) {
                AppWideCustomizationPickers(walletViewModel)
            }
        }
        "security" -> SettingsFormPage(title = stringResource(R.string.security), onBack = onBack) {
            SettingsFormSection(title = stringResource(R.string.security)) {
                SecuritySettingsItems(walletViewModel = walletViewModel)
            }
        }
        "connection" -> SettingsFormPage(title = stringResource(R.string.connection), onBack = onBack) {
            val explorer by walletViewModel.kaspaExplorer.collectAsState()
            ConnectionHubSections(
                explorerName = explorer.displayName,
                onOpenConnectionSettings = { navController.navigate("connection_settings") },
                onOpenExplorer = { navController.navigate("kaspa_explorer_settings") },
            )
        }
    }
}

/**
 * A player's card, opened by tapping a player on a leaderboard: who they are (avatar, name,
 * address) and their record here, this month and all time. KaChat opened its chat contact screen
 * for this; there are no chats here.
 */
@Composable
fun ChessPlayerScreen(address: String, onDone: () -> Unit) {
    val vm: ChessTournamentViewModel = hiltViewModel()
    val service = vm.service
    val colors = LocalAppColors.current
    val contacts by vm.contacts.collectAsState()
    val knsNames by service.knsNames.collectAsState()
    val month by service.board(ChessLeaderboardPeriod.MONTH).collectAsState()
    val allTime by service.board(ChessLeaderboardPeriod.ALL_TIME).collectAsState()
    val explorer by hiltViewModel<WalletViewModel>().kaspaExplorer.collectAsState()
    val uriHandler = LocalUriHandler.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val haptic = com.kachat.app.util.rememberHaptics()
    val name = chessName(address, contacts, knsNames)

    fun row(board: List<com.kachat.app.util.ChessLeaderboardRow>) =
        board.firstOrNull { it.address.equals(address, ignoreCase = true) }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            SheetNavBar(
                title = "Player",
                modifier = Modifier.statusBarsPadding(),
                trailing = { SheetBarButton(stringResource(R.string.done), onClick = onDone) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ChessAvatar(address, contacts, size = 88)
                Text(
                    name,
                    color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            SettingsFormSection(title = "Address") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(address))
                            haptic(com.kachat.app.util.IosHaptic.SUCCESS)
                            copied = true
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        address,
                        color = colors.textPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                        maxLines = 2, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(20.dp),
                    )
                }
                SettingsDivider()
                SettingsActionItem("View in Explorer", Icons.Default.Explore, KaspaTeal) {
                    uriHandler.openUri(explorer.addressUrl(address))
                }
            }
            for ((title, board) in listOf("This month" to month, "All time" to allTime)) {
                val record = row(board)
                SettingsFormSection(title = title) {
                    ChessRecordLine("1v1 games", record?.wins ?: 0, record?.losses ?: 0)
                    SettingsDivider()
                    ChessRecordLine("Tournaments", record?.tournamentsWon ?: 0, record?.tournamentsLost ?: 0)
                }
            }
        }
    }
}

@Composable
private fun ChessRecordLine(title: String, wins: Int, losses: Int) {
    val colors = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("$wins W", color = colors.success, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(8.dp))
        Text("$losses L", color = colors.danger, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}
