package com.kachat.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavController
import com.kachat.app.repository.BroadcastRepository
import com.kachat.app.services.ChessBotGame
import com.kachat.app.services.ChessBotProgress
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.ChessBot
import com.kachat.app.util.ChessBotCodec
import com.kachat.app.util.ChessBotResult
import com.kachat.app.util.ChessBotSidePreference
import com.kachat.app.util.ChessColor
import com.kachat.app.util.ChessEngine
import com.kachat.app.util.ChessMove
import com.kachat.app.util.ChessPiece
import com.kachat.app.util.ChessPieceType
import com.kachat.app.util.ChessSquare
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.rememberHaptics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

// Play > Bots (iOS ChessBotsView / ChessBotGameView, c3c04a1): the ladder of five bots, one bot's
// page, and a game against it. The rules and the on-chain record are ChessBotGame's.

@HiltViewModel
class ChessBotsViewModel @Inject constructor(
    val progress: ChessBotProgress,
    private val broadcastRepository: BroadcastRepository,
) : ViewModel() {
    /** A fresh game. Its sends run on the app's own scope, not this screen's: a resignation sent
     *  on the way out still goes on chain after the board has closed (iOS's Task does the same). */
    fun newGame(bot: ChessBot, playerColor: ChessColor): ChessBotGame =
        ChessBotGame(bot, playerColor, broadcastRepository, progress, gameScope)

    private companion object {
        val gameScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}

/** Whether the game on screen holds its player - a bot game, or a 1v1 or tournament game being
 *  played rather than watched, still on. The dock stays hidden until it is over, as iOS hides the
 *  tab bar while the player is locked in (ChessMainShell reads it). */
object ChessGameDock {
    var lockedIn by mutableStateOf(false)
        private set
    /** The screen that last set [lockedIn]: one game screen opening straight over another (the
     *  next round's board) is set before the old one leaves, and the old one must not clear it. */
    private var holder: Any? = null

    /** Holds the dock away from the screen calling it while [locked], for as long as it is shown. */
    @Composable
    fun Hold(locked: Boolean) {
        DisposableEffect(locked) {
            val token = Any()
            holder = token
            lockedIn = locked
            onDispose { if (holder === token) lockedIn = false }
        }
    }
}

/** Play > Bots: the five bots as a ladder, level 1 to 5. Each level opens when the one before it
 *  is beaten; a locked bot shows who to beat first. */
@Composable
fun ChessBotsScreen(navController: NavController) {
    val vm: ChessBotsViewModel = hiltViewModel()
    val records by vm.progress.records.collectAsState()
    val colors = LocalAppColors.current
    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = WindowInsets(0),
        topBar = { ChessHomeBar(onBack = { navController.popBackStack() }, showConnectionDot = false) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(bottom = LocalDockInset.current + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Default.Memory, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(48.dp))
                Text("Climb the ladder", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text(
                    "Five bots, level 1 to 5. Beat one to unlock the next. Every move, yours and the bot's, is a Kaspa transaction.",
                    color = colors.textSecondary, fontSize = 15.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
            for (bot in ChessBot.all) {
                val unlocked = vm.progress.isUnlocked(bot, records)
                val record = vm.progress.record(bot, records)
                Row(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.surface)
                        .clickable(enabled = unlocked) { navController.navigate("chess_bot/${bot.level}") }
                        .padding(14.dp)
                        .alpha(if (unlocked) 1f else 0.6f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChessBotAvatar(bot, 56.dp, greyed = !unlocked)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(bot.name, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Level ${bot.level} · ${bot.title}", color = colors.textSecondary, fontSize = 15.sp)
                        if (unlocked) {
                            ChessBotLevelStars(bot.level)
                            if (record.wins + record.losses + record.draws > 0) ChessBotRecordLine(record)
                        } else {
                            ChessBot.forLevel(bot.level - 1)?.let { previous ->
                                Text("Beat ${previous.name} to unlock", color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Icon(
                        if (unlocked) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Default.Lock,
                        contentDescription = null, tint = colors.textSecondary,
                        modifier = Modifier.size(if (unlocked) 24.dp else 18.dp),
                    )
                }
            }
        }
    }
}

/** One bot before a game: who they are, the record against them, which side to play, what a
 *  game costs on chain - then Play. */
@Composable
fun ChessBotDetailScreen(level: Int, navController: NavController) {
    val bot = ChessBot.forLevel(level) ?: return
    val vm: ChessBotsViewModel = hiltViewModel()
    val records by vm.progress.records.collectAsState()
    val record = vm.progress.record(bot, records)
    val colors = LocalAppColors.current
    var side by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(ChessBotSidePreference.WHITE) }
    val haptic = rememberHaptics()
    val feeLine = remember(bot) {
        val each = ChessBotCodec.moveFeeText()?.let { "about $it each" } ?: "one small fee each"
        "Every move, yours and ${bot.name}'s, is a Kaspa transaction from your wallet, $each. Starting and resigning are one transaction too."
    }
    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = WindowInsets(0),
        topBar = { ChessHomeBar(onBack = { navController.popBackStack() }, showConnectionDot = false) },
        bottomBar = {
            Box(
                Modifier.fillMaxWidth().background(colors.background)
                    .padding(bottom = LocalDockInset.current)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(KaspaTeal)
                        .clickable {
                            haptic(IosHaptic.IMPACT_MEDIUM)
                            navController.navigate("chess_bot_game/${bot.level}/${side.name}")
                        }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Play ${bot.name}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1)
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChessBotAvatar(bot, 104.dp)
                Text(bot.name, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 22.sp, textAlign = TextAlign.Center)
                Text("Level ${bot.level} · ${bot.title}", color = colors.textSecondary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                ChessBotLevelStars(bot.level)
                Text(
                    bot.bio, color = colors.textSecondary, fontSize = 15.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp).padding(top = 4.dp),
                )
            }
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Your record", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                ChessBotRecordLine(record)
            }
            Column(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Play as", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                KaPostsSegmentedPicker(
                    titles = ChessBotSidePreference.entries.map { it.label },
                    selected = side.ordinal,
                    onSelect = { side = ChessBotSidePreference.entries[it] },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Link, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                Text(feeLine, color = colors.textSecondary, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

/** A game against a bot. Play again starts a fresh game (and a fresh [ChessBotGame]) in place. */
@Composable
fun ChessBotGameScreen(level: Int, sideName: String, navController: NavController) {
    val bot = ChessBot.forLevel(level) ?: return
    val side = runCatching { ChessBotSidePreference.valueOf(sideName) }.getOrDefault(ChessBotSidePreference.WHITE)
    var round by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    key(round) {
        ChessBotMatch(bot, side.resolve(), navController, onPlayAgain = { round += 1 })
    }
}

/** The board against the bot, laid out like the online board: who you play and where the game
 *  stands, the board, then how much of the game is on chain and the moves so far. */
@Composable
private fun ChessBotMatch(bot: ChessBot, playerColor: ChessColor, navController: NavController, onPlayAgain: () -> Unit) {
    val vm: ChessBotsViewModel = hiltViewModel()
    val game = remember { vm.newGame(bot, playerColor) }
    val colors = LocalAppColors.current
    val haptic = rememberHaptics()
    val board by game.board.collectAsState()
    val moves by game.moves.collectAsState()
    val result by game.result.collectAsState()
    val isThinking by game.isThinking.collectAsState()
    val outbox by game.outbox.collectAsState()
    val movesOnChain by game.movesOnChain.collectAsState()
    val sendError by game.sendError.collectAsState()
    var selectedSquare by remember { mutableStateOf<ChessSquare?>(null) }
    var pendingPromotion by remember { mutableStateOf<ChessMove?>(null) }
    var showResignConfirm by remember { mutableStateOf(false) }
    /** The back button while the game is on: leaving means resigning, and it asks. */
    var showLeaveWarning by remember { mutableStateOf(false) }
    var showEnd by remember { mutableStateOf(false) }
    // Drawn from the player's side: playing black puts black at the bottom.
    val flipped = playerColor == ChessColor.BLACK
    val lockedIn = result == null
    val canPlayerMove = result == null && board.sideToMove == playerColor && !isThinking && outbox.isEmpty() && sendError == null

    LaunchedEffect(Unit) { game.start() }
    ChessGameDock.Hold(lockedIn)
    LaunchedEffect(result) {
        val r = result ?: return@LaunchedEffect
        selectedSquare = null
        haptic(
            when (r) {
                is ChessBotResult.Won -> IosHaptic.SUCCESS
                is ChessBotResult.Lost -> IosHaptic.ERROR
                is ChessBotResult.Drawn -> IosHaptic.IMPACT_MEDIUM
            }
        )
        showEnd = true
    }
    androidx.activity.compose.BackHandler(enabled = lockedIn) { showLeaveWarning = true }

    val statusText = when {
        result is ChessBotResult.Won -> "You won by ${result!!.reason}."
        result is ChessBotResult.Lost ->
            if (result!!.reason == "resignation") "You resigned. ${bot.name} wins." else "${bot.name} won by ${result!!.reason}."
        result is ChessBotResult.Drawn -> "Draw by ${result!!.reason}."
        sendError != null -> "Couldn't put the last move on Kaspa."
        isThinking -> "${bot.name} is thinking…"
        outbox.isNotEmpty() -> if (moves.isEmpty()) "Starting the game on Kaspa…" else "Putting the move on Kaspa…"
        board.sideToMove == playerColor ->
            if (ChessEngine.isKingInCheck(playerColor, board)) "Check. Your move." else "Your move"
        else -> "${bot.name} to move"
    }

    val legalDestinations: List<ChessSquare> = selectedSquare?.takeIf { canPlayerMove }?.let { from ->
        ChessEngine.legalMoves(from, board).map { it.to }
    } ?: emptyList()

    // The king of the side to move, when it is in check - tinted red.
    val checkedKing: ChessSquare? = run {
        val sideToMove = board.sideToMove
        if (!ChessEngine.isKingInCheck(sideToMove, board)) return@run null
        for (rank in 0..7) for (file in 0..7) {
            val square = ChessSquare(file, rank)
            val piece = board.piece(square)
            if (piece?.type == ChessPieceType.KING && piece.color == sideToMove) return@run square
        }
        null
    }

    fun tap(sq: ChessSquare) {
        if (!canPlayerMove) return
        val from = selectedSquare
        if (from != null) {
            if (sq in legalDestinations) {
                val move = ChessMove(from, sq, null)
                selectedSquare = null
                if (board.piece(from)?.type == ChessPieceType.PAWN && sq.rank == (if (playerColor == ChessColor.WHITE) 7 else 0)) {
                    pendingPromotion = move
                } else {
                    haptic(IosHaptic.IMPACT_LIGHT)
                    game.play(move)
                }
                return
            }
            selectedSquare = if (board.piece(sq)?.color == playerColor) sq else null
            return
        }
        if (board.piece(sq)?.color == playerColor) selectedSquare = sq
    }

    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            ChessHomeBar(
                onBack = { if (lockedIn) showLeaveWarning = true else navController.popBackStack() },
                showConnectionDot = false,
            ) {
                if (lockedIn) SheetBarButton("Resign", onClick = { showResignConfirm = true }, color = colors.danger)
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(top = 8.dp).padding(bottom = LocalDockInset.current),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header: who you play and where the game stands.
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChessBotAvatar(bot, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(bot.name, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Level ${bot.level} · ${bot.title} · You play ${if (playerColor == ChessColor.WHITE) "white" else "black"}",
                            color = colors.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (isThinking) com.kachat.app.ui.theme.IosActivityIndicator(color = colors.textSecondary, modifier = Modifier.size(20.dp))
                }
                Text(
                    statusText,
                    color = when {
                        sendError != null -> colors.danger
                        result != null -> colors.textSecondary
                        else -> colors.textPrimary
                    },
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                )
            }

            // The board.
            BoxWithConstraints(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val size = maxWidth / 8
                val ranks = if (flipped) (0..7).toList() else (7 downTo 0).toList()
                val files = if (flipped) (7 downTo 0).toList() else (0..7).toList()
                val last = moves.lastOrNull()?.move
                Column(Modifier.matchParentSize()) {
                    for (rank in ranks) {
                        Row {
                            for (file in files) {
                                val sq = ChessSquare(file, rank)
                                val isLight = (file + rank) % 2 != 0
                                Box(
                                    Modifier.size(size).background(if (isLight) ChessLightSquareColor else ChessDarkSquareColor).clickable { tap(sq) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (last != null && (last.from == sq || last.to == sq)) Box(Modifier.fillMaxSize().background(Color.Yellow.copy(alpha = 0.35f)))
                                    if (checkedKing == sq) Box(Modifier.fillMaxSize().background(Color.Red.copy(alpha = 0.45f)))
                                    if (selectedSquare == sq) Box(Modifier.fillMaxSize().background(KaspaTeal.copy(alpha = 0.35f)))
                                    board.piece(sq)?.let { ChessPieceGlyph(it, fontSize = (size.value * 0.7f).sp) }
                                    if (sq in legalDestinations) {
                                        Box(Modifier.size(size * 0.3f).clip(CircleShape).background(KaspaTeal.copy(alpha = 0.55f)))
                                    }
                                }
                            }
                        }
                    }
                }
                val r = result
                androidx.compose.animation.AnimatedVisibility(
                    visible = showEnd && r != null, enter = fadeIn(), exit = fadeOut(),
                    modifier = Modifier.matchParentSize(),
                ) {
                    if (r != null) {
                        ChessBotEndOverlay(
                            bot = bot,
                            result = r,
                            statusText = statusText,
                            unlocked = if (r is ChessBotResult.Won && vm.progress.record(bot).wins == 1) ChessBot.forLevel(bot.level + 1) else null,
                            onPlayAgain = onPlayAgain,
                            onDone = { navController.popBackStack() },
                            onSeeBoard = { showEnd = false },
                        )
                    }
                }
            }

            // How much of the game is on chain: a spinner while a move is going out, a check
            // when everything is, Retry when a send failed.
            Column(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surface).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Link, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("$movesOnChain of ${moves.size} moves on Kaspa", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    when {
                        sendError != null -> Text(
                            "Retry", color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { game.retry() }.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                        outbox.isNotEmpty() -> com.kachat.app.ui.theme.IosActivityIndicator(color = colors.textSecondary, modifier = Modifier.size(16.dp))
                        else -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = colors.success, modifier = Modifier.size(18.dp))
                    }
                }
                sendError?.let { Text(it, color = colors.danger, fontSize = 11.sp, maxLines = 3) }
            }

            // The moves in short notation, numbered in pairs, following the newest.
            val moveScroll = rememberScrollState()
            LaunchedEffect(moves.size) { moveScroll.animateScrollTo(moveScroll.maxValue) }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(moveScroll).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                if (moves.isEmpty()) {
                    Text(if (playerColor == ChessColor.WHITE) "You move first." else "${bot.name} moves first.", color = colors.textSecondary, style = style)
                }
                for (index in moves.indices step 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("${index / 2 + 1}.", color = colors.textSecondary, style = style)
                        Text(notation(moves[index]), color = colors.textPrimary, style = style)
                        moves.getOrNull(index + 1)?.let { Text(notation(it), color = colors.textPrimary, style = style) }
                    }
                }
            }
        }

        pendingPromotion?.let { move ->
            Dialog(onDismissRequest = { pendingPromotion = null }) {
                Column(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Promote to", color = colors.textPrimary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        listOf(ChessPieceType.QUEEN, ChessPieceType.ROOK, ChessPieceType.BISHOP, ChessPieceType.KNIGHT).forEach { type ->
                            Box(
                                Modifier.size(60.dp).clip(RoundedCornerShape(12.dp)).background(colors.surfaceVariant).clickable {
                                    pendingPromotion = null
                                    haptic(IosHaptic.IMPACT_LIGHT)
                                    game.play(ChessMove(move.from, move.to, type))
                                },
                                contentAlignment = Alignment.Center,
                            ) { ChessPieceGlyph(ChessPiece(type, playerColor), fontSize = 44.sp) }
                        }
                    }
                }
            }
        }
    }

    // One sheet behind Resign - and behind the back button while the game is on, where leaving
    // means resigning.
    if (showResignConfirm || showLeaveWarning) {
        val leaving = showLeaveWarning
        val consequence = "It counts as a loss against ${bot.name}. Resigning is one transaction."
        val dismiss = { showResignConfirm = false; showLeaveWarning = false }
        ActionSheetContainer(
            title = if (leaving) "Leave the game?" else "Resign this game?",
            subtitle = null,
            onDismiss = dismiss,
        ) {
            Icon(
                if (leaving) Icons.AutoMirrored.Filled.ExitToApp else Icons.Default.Flag,
                contentDescription = null, tint = colors.danger,
                modifier = Modifier.size(34.dp).align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (leaving) "If you leave, you resign the game. $consequence" else consequence,
                color = colors.textSecondary, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.danger)
                    .clickable {
                        dismiss()
                        game.resign()
                        if (leaving) navController.popBackStack()
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(if (leaving) "Resign and leave" else "Resign", color = Color.White, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceVariant)
                    .clickable { dismiss() }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(if (leaving) "Stay" else "Keep playing", color = colors.textPrimary, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ChessBotEndOverlay(
    bot: ChessBot,
    result: ChessBotResult,
    statusText: String,
    unlocked: ChessBot?,
    onPlayAgain: () -> Unit,
    onDone: () -> Unit,
    onSeeBoard: () -> Unit,
) {
    val colors = LocalAppColors.current
    val (icon, headline, tint) = when (result) {
        is ChessBotResult.Won -> Triple(Icons.Default.EmojiEvents, "You won!", Color(0xFFFF9500))
        is ChessBotResult.Lost -> Triple(Icons.Default.Flag, "You lost", colors.danger)
        is ChessBotResult.Drawn -> Triple(Icons.Default.DragHandle, "Draw", colors.textSecondary)
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(44.dp))
            Text(headline, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(statusText, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, textAlign = TextAlign.Center)
            if (unlocked != null) {
                Text("${unlocked.name} is unlocked!", color = Color(0xFFFF9500), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(KaspaTeal).clickable { onPlayAgain() }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Play again", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.2f)).clickable { onDone() }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Done", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
            }
            Text(
                "See the board", color = Color.White.copy(alpha = 0.8f), fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onSeeBoard() }.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** Short notation for a played move: O-O, Nf3, exd5, e8=Q. */
private fun notation(played: ChessBotGame.PlayedMove): String {
    val move = played.move
    if (played.piece == ChessPieceType.KING && kotlin.math.abs(move.to.file - move.from.file) == 2) {
        return if (move.to.file == 6) "O-O" else "O-O-O"
    }
    val letter = when (played.piece) {
        ChessPieceType.PAWN -> if (played.captured != null) move.from.algebraic.take(1) else ""
        ChessPieceType.KNIGHT -> "N"
        ChessPieceType.BISHOP -> "B"
        ChessPieceType.ROOK -> "R"
        ChessPieceType.QUEEN -> "Q"
        ChessPieceType.KING -> "K"
    }
    val promotion = move.promotion?.let { "=" + (it.promotionLetter ?: "q").uppercase() } ?: ""
    return letter + (if (played.captured != null) "x" else "") + move.to.algebraic + promotion
}

/** A bot's face: initials on its gradient, its level in a badge. */
@Composable
fun ChessBotAvatar(bot: ChessBot, size: Dp, greyed: Boolean = false) {
    val colors = LocalAppColors.current
    val gradient = if (greyed) bot.colors.map { c -> val g = 0.3f * c.red + 0.59f * c.green + 0.11f * c.blue; Color(g, g, g) } else bot.colors
    Box(Modifier.size(size)) {
        Box(
            Modifier.matchParentSize().clip(CircleShape).background(Brush.verticalGradient(gradient)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                bot.initials,
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = (size.value * 0.36f).sp,
                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.25f), offset = androidx.compose.ui.geometry.Offset(0f, 2f), blurRadius = 2f)),
            )
        }
        Box(
            Modifier.align(Alignment.BottomEnd).size(size * 0.36f).clip(CircleShape).background(colors.background)
                .border(1.5.dp, gradient.last(), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("${bot.level}", color = colors.textPrimary, fontWeight = FontWeight.Black, fontSize = (size.value * 0.2f).sp)
        }
    }
}

/** Five stars, [level] of them filled. */
@Composable
fun ChessBotLevelStars(level: Int) {
    val colors = LocalAppColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (star in 1..5) {
            Icon(
                if (star <= level) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = if (star == 1) "Level $level of 5" else null,
                tint = if (star <= level) Color(0xFFFF9500) else colors.textSecondary.copy(alpha = 0.5f),
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
fun ChessBotRecordLine(record: ChessBotProgress.Record) {
    val colors = LocalAppColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Text("${record.wins} W", color = colors.success, style = style)
        Text("${record.losses} L", color = colors.danger, style = style)
        Text("${record.draws} D", color = colors.textSecondary, style = style)
    }
}
