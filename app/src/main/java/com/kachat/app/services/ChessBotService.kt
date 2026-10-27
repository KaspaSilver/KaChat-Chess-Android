package com.kachat.app.services

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kachat.app.repository.BroadcastRepository
import com.kachat.app.util.ChessBoard
import com.kachat.app.util.ChessBot
import com.kachat.app.util.ChessBotCodec
import com.kachat.app.util.ChessBotEngine
import com.kachat.app.util.ChessBotMessage
import com.kachat.app.util.ChessBotResult
import com.kachat.app.util.ChessColor
import com.kachat.app.util.ChessEngine
import com.kachat.app.util.ChessMove
import com.kachat.app.util.ChessPieceType
import com.kachat.app.util.ChessTournamentEngine
import com.kachat.app.util.UserFacingError
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The player's record against each bot, kept on this phone per address (iOS ChessBotProgress
 * c3c04a1). A win against a bot opens the next level.
 */
@Singleton
class ChessBotProgress @Inject constructor(
    @ApplicationContext context: Context,
    private val walletManager: WalletManager,
) {
    data class Record(val wins: Int = 0, val losses: Int = 0, val draws: Int = 0)

    private val prefs = context.getSharedPreferences("chess_bots", Context.MODE_PRIVATE)
    private val gson = Gson()

    /** Address -> level -> record. */
    private val _records = MutableStateFlow(load())
    val records: StateFlow<Map<String, Map<Int, Record>>> = _records.asStateFlow()

    private fun load(): Map<String, Map<Int, Record>> = runCatching {
        val json = prefs.getString(KEY, null) ?: return@runCatching emptyMap()
        val type = object : TypeToken<Map<String, Map<Int, Record>>>() {}.type
        gson.fromJson<Map<String, Map<Int, Record>>>(json, type) ?: emptyMap()
    }.getOrDefault(emptyMap())

    private val address: String get() = runCatching { walletManager.getAddress() }.getOrDefault("")

    fun record(bot: ChessBot, records: Map<String, Map<Int, Record>> = _records.value): Record =
        records[address]?.get(bot.level) ?: Record()

    fun isUnlocked(bot: ChessBot, records: Map<String, Map<Int, Record>> = _records.value): Boolean {
        val previous = ChessBot.forLevel(bot.level - 1) ?: return true
        return record(previous, records).wins > 0
    }

    fun add(result: ChessBotResult, bot: ChessBot) {
        val current = record(bot)
        val updated = when (result) {
            is ChessBotResult.Won -> current.copy(wins = current.wins + 1)
            is ChessBotResult.Lost -> current.copy(losses = current.losses + 1)
            is ChessBotResult.Drawn -> current.copy(draws = current.draws + 1)
        }
        val all = _records.value.toMutableMap()
        all[address] = (all[address] ?: emptyMap()) + (bot.level to updated)
        _records.value = all
        prefs.edit().putString(KEY, gson.toJson(all)).apply()
    }

    private companion object {
        const val KEY = "chessBotRecords"
    }
}

/**
 * One game against a bot (Play > Bots, iOS ChessBotGame c3c04a1). The board lives on this phone,
 * and every step of the game goes on chain as it happens (ONLINE_CHESS.md §7): the start, each
 * move - the player's and the bot's, both signed by the player's address - and a resignation.
 * Sends go out one at a time, in order; the bot replies only once the player's move is on chain,
 * and the player moves only once the bot's is, so the chain always holds the game up to the
 * board. A send that fails holds the game until Retry.
 */
class ChessBotGame(
    val bot: ChessBot,
    val playerColor: ChessColor,
    private val broadcastRepository: BroadcastRepository,
    private val progress: ChessBotProgress,
    private val scope: CoroutineScope,
) {
    data class PlayedMove(
        val move: ChessMove,
        val color: ChessColor,
        val piece: ChessPieceType,
        val captured: ChessPieceType?,
    )

    val id: String = java.util.UUID.randomUUID().toString().lowercase()

    private val _board = MutableStateFlow(ChessEngine.initialBoard())
    val board: StateFlow<ChessBoard> = _board.asStateFlow()
    private val _moves = MutableStateFlow<List<PlayedMove>>(emptyList())
    val moves: StateFlow<List<PlayedMove>> = _moves.asStateFlow()
    private val _result = MutableStateFlow<ChessBotResult?>(null)
    val result: StateFlow<ChessBotResult?> = _result.asStateFlow()
    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()
    /** Messages still to go on chain, the first one sending. */
    private val _outbox = MutableStateFlow<List<ChessBotMessage>>(emptyList())
    val outbox: StateFlow<List<ChessBotMessage>> = _outbox.asStateFlow()
    /** Moves on chain so far (the start message not counted). */
    private val _movesOnChain = MutableStateFlow(0)
    val movesOnChain: StateFlow<Int> = _movesOnChain.asStateFlow()
    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    private var isSending = false
    private var started = false
    private val positionCounts = mutableMapOf<String, Int>()
    private var halfmoveClock = 0

    val botColor: ChessColor get() = playerColor.opposite
    val isOver: Boolean get() = _result.value != null

    /** The player may move: their turn, the bot not thinking, everything before on chain. */
    val canPlayerMove: Boolean
        get() = !isOver && _board.value.sideToMove == playerColor && !_isThinking.value &&
            _outbox.value.isEmpty() && _sendError.value == null

    /** The board opened: the start goes on chain; if the bot has white it moves once that lands. */
    fun start() {
        if (started) return
        started = true
        positionCounts[ChessTournamentEngine.positionKey(_board.value)] = 1
        enqueue(ChessBotCodec.start(id, bot, playerColor))
    }

    fun play(move: ChessMove) {
        if (!canPlayerMove || !ChessEngine.isLegal(move, _board.value)) return
        apply(ChessEngine.normalizingPromotion(move, _board.value))
    }

    fun resign() {
        if (!started || isOver) return
        finish(ChessBotResult.Lost("resignation"))
        enqueue(ChessBotCodec.resign(id))
    }

    fun retry() {
        _sendError.value = null
        pump()
    }

    // MARK: - The board

    private fun apply(move: ChessMove) {
        val board = _board.value
        val piece = board.piece(move.from) ?: return
        val isEnPassant = piece.type == ChessPieceType.PAWN && move.to == board.enPassantTarget && board.piece(move.to) == null
        val captured = board.piece(move.to)?.type ?: if (isEnPassant) ChessPieceType.PAWN else null
        val mover = board.sideToMove
        val next = ChessEngine.apply(move, board)
        _board.value = next
        _moves.value = _moves.value + PlayedMove(move, mover, piece.type, captured)
        enqueue(ChessBotCodec.move(id, _moves.value.size, move))

        halfmoveClock = if (piece.type == ChessPieceType.PAWN || captured != null) 0 else halfmoveClock + 1
        val key = ChessTournamentEngine.positionKey(next)
        positionCounts[key] = (positionCounts[key] ?: 0) + 1

        when {
            ChessEngine.isCheckmate(next) ->
                finish(if (mover == playerColor) ChessBotResult.Won("checkmate") else ChessBotResult.Lost("checkmate"))
            ChessEngine.isStalemate(next) -> finish(ChessBotResult.Drawn("stalemate"))
            ChessEngine.isInsufficientMaterial(next) -> finish(ChessBotResult.Drawn("insufficient material"))
            halfmoveClock >= 100 -> finish(ChessBotResult.Drawn("the fifty-move rule"))
            (positionCounts[key] ?: 0) >= 3 -> finish(ChessBotResult.Drawn("threefold repetition"))
        }
    }

    private fun finish(result: ChessBotResult) {
        if (_result.value != null) return
        _result.value = result
        progress.add(result, bot)
    }

    /**
     * The bot's turn and the chain caught up: think off the main thread (never quicker than a
     * beat, so a reply does not land in the same instant as the player's move), then play.
     */
    private fun botMoveIfDue() {
        if (isOver || _board.value.sideToMove != botColor || _isThinking.value || _outbox.value.isNotEmpty() || _sendError.value != null) return
        _isThinking.value = true
        val position = _board.value
        val settings = bot.searchSettings(_moves.value.size)
        val avoiding = repeatingMoves()
        val began = System.currentTimeMillis()
        scope.launch {
            val move = withContext(Dispatchers.Default) {
                ChessBotEngine.bestMove(position, settings, avoiding)
            }
            val elapsed = System.currentTimeMillis() - began
            if (elapsed < 800) delay(800 - elapsed)
            _isThinking.value = false
            if (isOver || move == null || _board.value != position) return@launch
            apply(move)
        }
    }

    /** The bot's moves that bring back a position already seen this game. */
    private fun repeatingMoves(): List<ChessMove> {
        val board = _board.value
        return ChessEngine.legalMoves(board).filter { move ->
            positionCounts[ChessTournamentEngine.positionKey(ChessEngine.apply(move, board))] != null
        }
    }

    // MARK: - On chain

    private fun enqueue(message: ChessBotMessage) {
        _outbox.value = _outbox.value + message
        pump()
    }

    private fun pump() {
        if (isSending || _sendError.value != null) return
        val next = _outbox.value.firstOrNull() ?: return
        isSending = true
        scope.launch {
            val error = send(next)
            isSending = false
            if (error != null) {
                _sendError.value = error
                return@launch
            }
            _outbox.value = _outbox.value.drop(1)
            if (next.a == "move") _movesOnChain.value += 1
            pump()
            botMoveIfDue()
        }
    }

    /**
     * One broadcast transaction, with the retries back-to-back sends need: the change of the last
     * transaction is not spendable until it is mined (the arena's sends do the same). Returns the
     * error to show, or null once it is sent.
     */
    private suspend fun send(message: ChessBotMessage): String? {
        val content = ChessBotCodec.encode(message)
        var attempt = 0
        while (true) {
            try {
                broadcastRepository.sendBroadcast(ChessBotCodec.CHANNEL, content)
                return null
            } catch (e: Exception) {
                attempt += 1
                if (attempt > 5) {
                    Log.w("ChessBots", "Bot game send failed after retries", e)
                    return UserFacingError.message(e, "Could not send")
                }
                delay(1_200)
            }
        }
    }
}
