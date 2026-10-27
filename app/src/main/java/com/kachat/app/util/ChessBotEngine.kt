package com.kachat.app.util

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * The bots' move search (Play > Bots, iOS ChessBotEngine c3c04a1). [ChessEngine] is the rules - it
 * decides what is legal, and every move a bot plays is checked against its list - but it copies
 * the whole board for every move it tries, far too slow to look several moves ahead on a phone.
 * So the search runs on its own compact board ([BotPosition]: 64 ints, make/unmake in place) with
 * a plain alpha-beta: iterative deepening under a time limit, captures searched to quiet
 * positions, piece-square tables for the evaluation. The levels differ in how deep they look and
 * how much noise they add to their choice (see [ChessBot.all]).
 */
object ChessBotEngine {
    data class Settings(
        /** Plies searched at most (iterative deepening stops early on the time limit). */
        val depth: Int,
        /** Follow captures past the depth so a piece is never counted mid-exchange. */
        val quiescence: Boolean,
        /** Centipawns of random noise on each root move's score: how loosely the bot plays. */
        val noise: Int,
        /** Chance of an outright random legal move - the beginner's blunders. */
        val randomChance: Double,
        /** Seconds of thinking at most, past depth 1. */
        val timeLimitSeconds: Double,
    )

    /**
     * The bot's move in [board], or null when there is none (the game is over). [avoiding]: moves
     * that repeat a position already seen in this game - scored as a draw, so a bot that is
     * winning does not shuffle into threefold repetition (and one that is losing takes the draw
     * when it can).
     */
    fun bestMove(board: ChessBoard, settings: Settings, avoiding: List<ChessMove> = emptyList()): ChessMove? {
        val legal = ChessEngine.legalMoves(board)
        if (legal.isEmpty()) return null
        if (legal.size == 1) return legal[0]
        if (Random.nextDouble() < settings.randomChance) return legal.random()
        val search = BotSearch(BotPosition(board), settings, avoiding)
        val move = search.run()?.chessMove ?: return legal.random()
        // The rules engine has the last word: a search move it does not know (it never should
        // happen) becomes a random legal one rather than an illegal move on chain.
        return if (move in legal) move else legal.random()
    }
}

// MARK: - Board

/** A move on [BotPosition]'s board: squares 0...63 (rank * 8 + file, a1 = 0). */
internal class BotMove(
    val from: Int,
    val to: Int,
    /** Promotion piece code (2 knight, 3 bishop, 4 rook, 5 queen), 0 for none. */
    val promo: Int = 0,
    val flag: Flag = Flag.NORMAL,
    /** Move-ordering score: captures by most valuable victim, then least valuable attacker. */
    val order: Int = 0,
) {
    enum class Flag { NORMAL, DOUBLE_PUSH, EN_PASSANT, CASTLE }

    val chessMove: ChessMove
        get() = ChessMove(
            from = ChessSquare(from % 8, from / 8),
            to = ChessSquare(to % 8, to / 8),
            promotion = when (promo) {
                2 -> ChessPieceType.KNIGHT
                3 -> ChessPieceType.BISHOP
                4 -> ChessPieceType.ROOK
                5 -> ChessPieceType.QUEEN
                else -> null
            },
        )
}

/** Precomputed targets per square, so move generation never checks the board's edges. */
private object BotTables {
    private fun targets(offsets: List<Pair<Int, Int>>): Array<IntArray> = Array(64) { square ->
        val file = square % 8
        val rank = square / 8
        offsets.mapNotNull { (df, dr) ->
            val f = file + df
            val r = rank + dr
            if (f in 0..7 && r in 0..7) r * 8 + f else null
        }.toIntArray()
    }

    val knight = targets(listOf(1 to 2, 2 to 1, 2 to -1, 1 to -2, -1 to -2, -2 to -1, -2 to 1, -1 to 2))
    val king = targets(listOf(1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1))

    /** Rays from each square: directions 0...3 straight, 4...7 diagonal. */
    val rays: Array<Array<IntArray>> = Array(64) { square ->
        val file = square % 8
        val rank = square / 8
        listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0, 1 to 1, 1 to -1, -1 to 1, -1 to -1).map { (df, dr) ->
            val ray = mutableListOf<Int>()
            var f = file + df
            var r = rank + dr
            while (f in 0..7 && r in 0..7) {
                ray.add(r * 8 + f)
                f += df
                r += dr
            }
            ray.toIntArray()
        }.toTypedArray()
    }

    /** Castling bits a move from or to this square clears (1 white king side, 2 white queen
     *  side, 4 black king side, 8 black queen side). */
    val castleMask = IntArray(64) { square ->
        when (square) {
            0 -> 2
            4 -> 3
            7 -> 1
            56 -> 8
            60 -> 12
            63 -> 4
            else -> 0
        }
    }
}

/**
 * The search's board. Pieces are codes 1 pawn ... 6 king, positive for white, negative for
 * black; [side] is 1 for white to move, -1 for black.
 */
internal class BotPosition(board: ChessBoard) {
    private val squares = IntArray(64)
    var side: Int
        private set
    private var castling: Int
    private var enPassant: Int
    /** King squares: [white, black]. */
    private val kings = intArrayOf(4, 60)

    private class Undo(val move: BotMove, val captured: Int, val castling: Int, val enPassant: Int)
    private val history = ArrayList<Undo>()

    init {
        for (rank in 0..7) {
            for (file in 0..7) {
                val piece = board.squares[rank][file] ?: continue
                val code = when (piece.type) {
                    ChessPieceType.PAWN -> 1
                    ChessPieceType.KNIGHT -> 2
                    ChessPieceType.BISHOP -> 3
                    ChessPieceType.ROOK -> 4
                    ChessPieceType.QUEEN -> 5
                    ChessPieceType.KING -> 6
                }
                squares[rank * 8 + file] = if (piece.color == ChessColor.WHITE) code else -code
                if (piece.type == ChessPieceType.KING) kings[if (piece.color == ChessColor.WHITE) 0 else 1] = rank * 8 + file
            }
        }
        side = if (board.sideToMove == ChessColor.WHITE) 1 else -1
        castling = (if (board.whiteCanCastleKingside) 1 else 0) or (if (board.whiteCanCastleQueenside) 2 else 0) or
            (if (board.blackCanCastleKingside) 4 else 0) or (if (board.blackCanCastleQueenside) 8 else 0)
        enPassant = board.enPassantTarget?.let { it.rank * 8 + it.file } ?: -1
    }

    // MARK: Attacks

    fun isAttacked(square: Int, color: Int): Boolean {
        val file = square % 8
        val rank = square / 8
        // A white pawn attacks from the rank below, a black one from the rank above.
        val pawnRank = rank - color
        if (pawnRank in 0..7) {
            if (file > 0 && squares[pawnRank * 8 + file - 1] == color) return true
            if (file < 7 && squares[pawnRank * 8 + file + 1] == color) return true
        }
        for (target in BotTables.knight[square]) if (squares[target] == 2 * color) return true
        for (target in BotTables.king[square]) if (squares[target] == 6 * color) return true
        val rays = BotTables.rays[square]
        for (direction in 0 until 8) {
            for (target in rays[direction]) {
                val piece = squares[target]
                if (piece == 0) continue
                if (piece * color > 0) {
                    val type = abs(piece)
                    if (type == 5 || type == (if (direction < 4) 4 else 3)) return true
                }
                break
            }
        }
        return false
    }

    /** The side to move is in check. */
    val inCheck: Boolean get() = isAttacked(kings[if (side == 1) 0 else 1], -side)

    /** The side that just moved left its own king attacked: the move was not legal. */
    val moverInCheck: Boolean get() = isAttacked(kings[if (side == 1) 1 else 0], side)

    // MARK: Move generation (pseudo-legal; `make` then `moverInCheck` filters)

    fun moves(capturesOnly: Boolean): List<BotMove> {
        val moves = ArrayList<BotMove>(if (capturesOnly) 12 else 48)
        val us = side
        for (square in 0 until 64) {
            val piece = squares[square]
            if (piece * us <= 0) continue
            when (abs(piece)) {
                1 -> pawnMoves(square, capturesOnly, moves)
                2 -> for (target in BotTables.knight[square]) add(square, target, capturesOnly, moves)
                3 -> slide(square, 4 until 8, capturesOnly, moves)
                4 -> slide(square, 0 until 4, capturesOnly, moves)
                5 -> slide(square, 0 until 8, capturesOnly, moves)
                else -> {
                    for (target in BotTables.king[square]) add(square, target, capturesOnly, moves)
                    if (!capturesOnly) castlingMoves(square, moves)
                }
            }
        }
        moves.sortByDescending { it.order }
        return moves
    }

    /** The legal moves, best-looking first. */
    fun legalMoves(): List<BotMove> = moves(capturesOnly = false).filter { move ->
        make(move)
        val legal = !moverInCheck
        unmake()
        legal
    }

    private fun add(from: Int, to: Int, capturesOnly: Boolean, moves: MutableList<BotMove>) {
        val victim = squares[to]
        if (victim * side > 0) return
        if (victim == 0) {
            if (!capturesOnly) moves.add(BotMove(from, to))
            return
        }
        moves.add(BotMove(from, to, order = captureOrder(abs(victim), abs(squares[from]))))
    }

    private fun captureOrder(victim: Int, attacker: Int): Int = 10_000 + VALUES[victim] * 10 - attacker

    private fun slide(from: Int, directions: IntRange, capturesOnly: Boolean, moves: MutableList<BotMove>) {
        val rays = BotTables.rays[from]
        for (direction in directions) {
            for (target in rays[direction]) {
                val piece = squares[target]
                if (piece == 0) {
                    if (!capturesOnly) moves.add(BotMove(from, target))
                    continue
                }
                if (piece * side < 0) {
                    moves.add(BotMove(from, target, order = captureOrder(abs(piece), abs(squares[from]))))
                }
                break
            }
        }
    }

    private fun pawnMoves(from: Int, capturesOnly: Boolean, moves: MutableList<BotMove>) {
        val step = side * 8
        val rank = from / 8
        val file = from % 8
        val promotes = rank == (if (side == 1) 6 else 1)
        val startRank = if (side == 1) 1 else 6

        fun promotions(target: Int, order: Int) {
            // Queen and knight only: a rook or bishop promotion is never better than a queen
            // except to dodge stalemate, which is not worth the search time here.
            moves.add(BotMove(from, target, promo = 5, order = order + 9_000))
            moves.add(BotMove(from, target, promo = 2, order = order + 1_000))
        }

        val one = from + step
        if (squares[one] == 0) {
            if (promotes) {
                promotions(one, 0)
            } else if (!capturesOnly) {
                moves.add(BotMove(from, one))
                if (rank == startRank && squares[one + step] == 0) {
                    moves.add(BotMove(from, one + step, flag = BotMove.Flag.DOUBLE_PUSH))
                }
            }
        }
        for (df in intArrayOf(-1, 1)) {
            val targetFile = file + df
            if (targetFile !in 0..7) continue
            val target = one + df
            val victim = squares[target]
            if (victim * side < 0) {
                val order = captureOrder(abs(victim), 1)
                if (promotes) promotions(target, order) else moves.add(BotMove(from, target, order = order))
            } else if (target == enPassant) {
                moves.add(BotMove(from, target, flag = BotMove.Flag.EN_PASSANT, order = captureOrder(1, 1)))
            }
        }
    }

    private fun castlingMoves(from: Int, moves: MutableList<BotMove>) {
        val base = if (side == 1) 0 else 56
        val kingSide = if (side == 1) 1 else 4
        val queenSide = if (side == 1) 2 else 8
        if (from != base + 4) return
        if ((castling and kingSide) != 0 && squares[base + 5] == 0 && squares[base + 6] == 0 && squares[base + 7] == 4 * side &&
            !isAttacked(base + 4, -side) && !isAttacked(base + 5, -side) && !isAttacked(base + 6, -side)
        ) {
            moves.add(BotMove(from, base + 6, flag = BotMove.Flag.CASTLE))
        }
        if ((castling and queenSide) != 0 && squares[base + 3] == 0 && squares[base + 2] == 0 && squares[base + 1] == 0 &&
            squares[base] == 4 * side &&
            !isAttacked(base + 4, -side) && !isAttacked(base + 3, -side) && !isAttacked(base + 2, -side)
        ) {
            moves.add(BotMove(from, base + 2, flag = BotMove.Flag.CASTLE))
        }
    }

    // MARK: Make / unmake

    fun make(move: BotMove) {
        val piece = squares[move.from]
        history.add(Undo(move, squares[move.to], castling, enPassant))
        squares[move.to] = if (move.promo != 0) move.promo * side else piece
        squares[move.from] = 0
        when (move.flag) {
            BotMove.Flag.EN_PASSANT -> squares[move.to - 8 * side] = 0
            BotMove.Flag.CASTLE -> if (move.to > move.from) {
                squares[move.from + 1] = squares[move.from + 3]
                squares[move.from + 3] = 0
            } else {
                squares[move.from - 1] = squares[move.from - 4]
                squares[move.from - 4] = 0
            }
            BotMove.Flag.NORMAL, BotMove.Flag.DOUBLE_PUSH -> Unit
        }
        if (abs(piece) == 6) kings[if (side == 1) 0 else 1] = move.to
        castling = castling and (BotTables.castleMask[move.from] or BotTables.castleMask[move.to]).inv()
        enPassant = if (move.flag == BotMove.Flag.DOUBLE_PUSH) move.from + 8 * side else -1
        side = -side
    }

    fun unmake() {
        val last = history.removeAt(history.size - 1)
        val move = last.move
        side = -side
        val moved = squares[move.to]
        squares[move.from] = if (move.promo != 0) side else moved
        squares[move.to] = last.captured
        when (move.flag) {
            BotMove.Flag.EN_PASSANT -> squares[move.to - 8 * side] = -side
            BotMove.Flag.CASTLE -> if (move.to > move.from) {
                squares[move.from + 3] = squares[move.from + 1]
                squares[move.from + 1] = 0
            } else {
                squares[move.from - 4] = squares[move.from - 1]
                squares[move.from - 1] = 0
            }
            BotMove.Flag.NORMAL, BotMove.Flag.DOUBLE_PUSH -> Unit
        }
        if (abs(moved) == 6) kings[if (side == 1) 0 else 1] = move.from
        castling = last.castling
        enPassant = last.enPassant
    }

    // MARK: Evaluation

    /**
     * Centipawns for the side to move: material, piece-square tables, and in a won endgame a
     * push of the losing king to the edge so the bot can actually finish the game.
     */
    fun evaluate(): Int {
        var nonPawnMaterial = 0
        var balance = 0
        for (square in 0 until 64) {
            val piece = squares[square]
            if (piece == 0) continue
            val type = abs(piece)
            if (type != 1 && type != 6) nonPawnMaterial += VALUES[type]
            balance += if (piece > 0) VALUES[type] else -VALUES[type]
        }
        val endgame = nonPawnMaterial <= 1_300

        var score = balance
        for (square in 0 until 64) {
            val piece = squares[square]
            if (piece == 0) continue
            val type = abs(piece)
            // Tables are drawn from white's side with rank 8 first; black reads them mirrored.
            val index = if (piece > 0) (7 - square / 8) * 8 + square % 8 else square
            val table = if (type == 6 && endgame) KING_ENDGAME else TABLES[type]
            score += if (piece > 0) table[index] else -table[index]
        }

        if (endgame && abs(balance) >= 300) {
            val winner = if (balance > 0) 1 else -1
            val loserKing = kings[if (winner == 1) 1 else 0]
            val winnerKing = kings[if (winner == 1) 0 else 1]
            val edge = max(3 - loserKing % 8, loserKing % 8 - 4) + max(3 - loserKing / 8, loserKing / 8 - 4)
            val distance = abs(loserKing % 8 - winnerKing % 8) + abs(loserKing / 8 - winnerKing / 8)
            score += winner * (edge * 10 + (14 - distance) * 4)
        }
        return score * side
    }

    companion object {
        val VALUES = intArrayOf(0, 100, 320, 330, 500, 900, 0)

        private val PAWN = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0,
            50, 50, 50, 50, 50, 50, 50, 50,
            10, 10, 20, 30, 30, 20, 10, 10,
            5, 5, 10, 25, 25, 10, 5, 5,
            0, 0, 0, 20, 20, 0, 0, 0,
            5, -5, -10, 0, 0, -10, -5, 5,
            5, 10, 10, -20, -20, 10, 10, 5,
            0, 0, 0, 0, 0, 0, 0, 0,
        )
        private val KNIGHT = intArrayOf(
            -50, -40, -30, -30, -30, -30, -40, -50,
            -40, -20, 0, 0, 0, 0, -20, -40,
            -30, 0, 10, 15, 15, 10, 0, -30,
            -30, 5, 15, 20, 20, 15, 5, -30,
            -30, 0, 15, 20, 20, 15, 0, -30,
            -30, 5, 10, 15, 15, 10, 5, -30,
            -40, -20, 0, 5, 5, 0, -20, -40,
            -50, -40, -30, -30, -30, -30, -40, -50,
        )
        private val BISHOP = intArrayOf(
            -20, -10, -10, -10, -10, -10, -10, -20,
            -10, 0, 0, 0, 0, 0, 0, -10,
            -10, 0, 5, 10, 10, 5, 0, -10,
            -10, 5, 5, 10, 10, 5, 5, -10,
            -10, 0, 10, 10, 10, 10, 0, -10,
            -10, 10, 10, 10, 10, 10, 10, -10,
            -10, 5, 0, 0, 0, 0, 5, -10,
            -20, -10, -10, -10, -10, -10, -10, -20,
        )
        private val ROOK = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0,
            5, 10, 10, 10, 10, 10, 10, 5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            0, 0, 0, 5, 5, 0, 0, 0,
        )
        private val QUEEN = intArrayOf(
            -20, -10, -10, -5, -5, -10, -10, -20,
            -10, 0, 0, 0, 0, 0, 0, -10,
            -10, 0, 5, 5, 5, 5, 0, -10,
            -5, 0, 5, 5, 5, 5, 0, -5,
            0, 0, 5, 5, 5, 5, 0, -5,
            -10, 5, 5, 5, 5, 5, 0, -10,
            -10, 0, 5, 0, 0, 0, 0, -10,
            -20, -10, -10, -5, -5, -10, -10, -20,
        )
        private val KING_MIDDLEGAME = intArrayOf(
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -20, -30, -30, -40, -40, -30, -30, -20,
            -10, -20, -20, -20, -20, -20, -20, -10,
            20, 20, 0, 0, 0, 0, 20, 20,
            20, 30, 10, 0, 0, 10, 30, 20,
        )
        private val KING_ENDGAME = intArrayOf(
            -50, -40, -30, -20, -20, -30, -40, -50,
            -30, -20, -10, 0, 0, -10, -20, -30,
            -30, -10, 20, 30, 30, 20, -10, -30,
            -30, -10, 30, 40, 40, 30, -10, -30,
            -30, -10, 30, 40, 40, 30, -10, -30,
            -30, -10, 20, 30, 30, 20, -10, -30,
            -30, -30, 0, 0, 0, 0, -30, -30,
            -50, -30, -30, -30, -30, -30, -30, -50,
        )
        private val TABLES = arrayOf(IntArray(0), PAWN, KNIGHT, BISHOP, ROOK, QUEEN, KING_MIDDLEGAME)
    }
}

// MARK: - Search

private class BotSearch(
    private val position: BotPosition,
    private val settings: ChessBotEngine.Settings,
    private val drawish: List<ChessMove>,
) {
    private var deadline = Long.MAX_VALUE
    private var nodes = 0
    private var aborted = false
    /** Depth 1 always finishes; only deeper iterations may be cut by the clock. */
    private var canAbort = false

    private class Scored(val move: BotMove, val score: Int)

    fun run(): BotMove? {
        var rootMoves = position.legalMoves()
        if (rootMoves.isEmpty()) return null
        deadline = System.currentTimeMillis() + (settings.timeLimitSeconds * 1000).toLong()
        // With noise every root move needs its true score (a full window), not just a bound.
        val exact = settings.noise > 0
        var lastScored: List<Scored> = emptyList()

        for (depth in 1..max(1, settings.depth)) {
            canAbort = depth > 1
            val scored = ArrayList<Scored>()
            var alpha = -INFINITY
            for (move in rootMoves) {
                val score = if (isDrawish(move)) {
                    0
                } else {
                    position.make(move)
                    val s = -search(depth - 1, -INFINITY, if (exact) INFINITY else -alpha, 1)
                    position.unmake()
                    s
                }
                if (aborted) break
                scored.add(Scored(move, score))
                alpha = max(alpha, score)
            }
            if (aborted) break
            scored.sortByDescending { it.score }
            lastScored = scored
            // Searched best first next time round, which is what makes deepening cheap.
            rootMoves = scored.map { it.move }
            val best = scored.firstOrNull()
            if (best != null && abs(best.score) >= MATE - 100) break
            if (System.currentTimeMillis() > deadline) break
        }

        if (lastScored.isEmpty()) return null
        if (settings.noise <= 0) return lastScored[0].move
        return lastScored
            .map { Scored(it.move, it.score + Random.nextInt(-settings.noise, settings.noise + 1)) }
            .maxByOrNull { it.score }
            ?.move
    }

    private fun isDrawish(move: BotMove): Boolean = drawish.isNotEmpty() && move.chessMove in drawish

    private fun tick() {
        nodes += 1
        if (canAbort && (nodes and 1023) == 0 && System.currentTimeMillis() > deadline) aborted = true
    }

    private fun search(depthIn: Int, alphaIn: Int, beta: Int, ply: Int): Int {
        tick()
        if (aborted) return 0
        val inCheck = position.inCheck
        // Checks are searched one ply deeper, so a mate just past the horizon is still seen.
        val depth = if (inCheck && ply < 12) depthIn + 1 else depthIn
        if (depth <= 0) {
            return if (settings.quiescence) quiesce(alphaIn, beta, 0) else position.evaluate()
        }
        var alpha = alphaIn
        var legal = 0
        for (move in position.moves(capturesOnly = false)) {
            position.make(move)
            if (position.moverInCheck) {
                position.unmake()
                continue
            }
            legal += 1
            val score = -search(depth - 1, -beta, -alpha, ply + 1)
            position.unmake()
            if (aborted) return 0
            if (score >= beta) return score
            if (score > alpha) alpha = score
        }
        if (legal == 0) return if (inCheck) -MATE + ply else 0
        return alpha
    }

    private fun quiesce(alphaIn: Int, beta: Int, ply: Int): Int {
        tick()
        if (aborted) return 0
        val standPat = position.evaluate()
        if (standPat >= beta) return standPat
        var alpha = max(alphaIn, standPat)
        if (ply >= 6) return alpha
        for (move in position.moves(capturesOnly = true)) {
            position.make(move)
            if (position.moverInCheck) {
                position.unmake()
                continue
            }
            val score = -quiesce(-beta, -alpha, ply + 1)
            position.unmake()
            if (aborted) return 0
            if (score >= beta) return score
            if (score > alpha) alpha = score
        }
        return alpha
    }

    companion object {
        private const val MATE = 100_000
        private const val INFINITY = 1_000_000
    }
}
