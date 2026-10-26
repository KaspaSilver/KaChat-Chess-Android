package com.kachat.app.util

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * "Play Chess" 1:1 chat feature - four JSON envelopes embedded directly in the plaintext message
 * body, same convention as [MessageReply]/[VoiceMessage]/[ImageMessage] (no wire-protocol
 * change). All four share one `gameId` (a UUID minted by the inviter) - [ChessGameSummary]
 * derives the current board by scanning a conversation's messages for that `gameId` and replaying
 * them through [ChessEngine], the same way [MessageReply.replyToId] is resolved client-side
 * against the in-memory message list rather than a database relationship.
 */
/** `@SerializedName` pins the wire value to lowercase "white"/"black" - Gson's default enum
 *  serialization uses the constant name verbatim ("WHITE"/"BLACK"), but iOS's Swift `Codable`
 *  enum encodes its raw String value, which defaults to the lowercase case name. Without this,
 *  every chess_invite sent from one platform silently failed to parse on the other (caught by
 *  parseOrNull's broad catch, falling through to plain-text rendering) - since the invite is the
 *  mandatory first message of every game, that broke the whole game, not just the color choice. */
enum class ChessInviteColor {
    @SerializedName("white") WHITE,
    @SerializedName("black") BLACK
}

data class ChessInviteContent(
    val type: String = "chess_invite",
    val gameId: String,
    val inviterColor: ChessInviteColor,
    /** Optional time control (e.g. 3/2, 2/1, 1/1) - both null means an untimed casual game,
     *  which is also what every pre-timer client sends and how they read these two absent keys
     *  (Gson ignores unknown fields on parse and omits null fields on encode, so the wire stays
     *  byte-compatible with legacy invites). Shared wire spec with iOS - field names must match. */
    val tcMinutes: Int? = null,
    val tcIncSeconds: Int? = null
)

data class ChessResponseContent(
    val type: String = "chess_response",
    val gameId: String,
    val accepted: Boolean
)

data class ChessMoveContent(
    val type: String = "chess_move",
    val gameId: String,
    val from: String,
    val to: String,
    val promotion: String?,
    /** Timed games only: the mover's remaining clock in ms AFTER this move, increment already
     *  added. Each side's authoritative remaining time is the clockMs of their own most recent
     *  move. Null/absent on untimed games and on moves from pre-timer clients. */
    val clockMs: Long? = null
)

data class ChessResignContent(
    val type: String = "chess_resign",
    val gameId: String,
    /** "timeout" when the player flagged (clock hit zero) - new clients render "lost on time",
     *  old clients ignore the field and show a plain resignation. Null for a manual resign. */
    val reason: String? = null
)

/** Any one of the four chess envelope shapes, parsed generically. */
sealed class ChessEnvelope {
    abstract val gameId: String

    data class Invite(val content: ChessInviteContent) : ChessEnvelope() {
        override val gameId: String get() = content.gameId
    }
    data class Response(val content: ChessResponseContent) : ChessEnvelope() {
        override val gameId: String get() = content.gameId
    }
    data class Move(val content: ChessMoveContent) : ChessEnvelope() {
        override val gameId: String get() = content.gameId
    }
    data class Resign(val content: ChessResignContent) : ChessEnvelope() {
        override val gameId: String get() = content.gameId
    }
}

/** Same conventions as [MessageReply]: a plain JSON envelope embedded directly as message
 *  content, with a `{`-prefix + size guard before attempting a full parse, since this runs on
 *  every visible message row alongside reply/image/voice parsing. */
object ChessMessage {
    private val gson = Gson()

    private data class TypeOnly(val type: String)

    fun encode(content: ChessInviteContent): String = gson.toJson(content)
    fun encode(content: ChessResponseContent): String = gson.toJson(content)
    fun encode(content: ChessMoveContent): String = gson.toJson(content)
    fun encode(content: ChessResignContent): String = gson.toJson(content)

    fun parseOrNull(text: String?): ChessEnvelope? {
        if (text.isNullOrBlank() || text.length > 100_000 || text.trimStart().firstOrNull() != '{') return null
        return try {
            val typeOnly = gson.fromJson(text, TypeOnly::class.java) ?: return null
            when (typeOnly.type) {
                "chess_invite" -> gson.fromJson(text, ChessInviteContent::class.java)?.let { ChessEnvelope.Invite(it) }
                "chess_response" -> gson.fromJson(text, ChessResponseContent::class.java)?.let { ChessEnvelope.Response(it) }
                "chess_move" -> gson.fromJson(text, ChessMoveContent::class.java)?.let { ChessEnvelope.Move(it) }
                "chess_resign" -> gson.fromJson(text, ChessResignContent::class.java)?.let { ChessEnvelope.Resign(it) }
                else -> null
            }
        } catch (e: Exception) {
            // Same broad catch as MessageReply.parseOrNull - Gson's reflection deserialization
            // doesn't honor Kotlin non-null defaults for absent JSON keys.
            null
        }
    }
}

