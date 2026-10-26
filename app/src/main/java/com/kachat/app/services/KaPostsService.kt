package com.kachat.app.services

import android.util.Log
import com.kachat.app.util.KaPostsProtocol
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaMessageSigner
import com.kachat.app.util.Secp256k1
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import retrofit2.HttpException
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton

// MARK: - Wire models (verbatim K field names; identity fields deliberately ignored - KNS owns
// ALL identity via the pubkey -> Kaspa address bridge, so userNickname/avatar fields never map)

/** Embedded reference the indexer attaches to quote posts. */
data class KQuoteRef(
    val referencedContentId: String?,
    val referencedMessage: String?,
    val referencedSenderPubkey: String?,
) {
    val decodedMessage: String? get() = referencedMessage?.let { KaPostsProtocol.decodeB64(it) }
}

data class KPost(
    val id: String,
    val userPublicKey: String,
    val postContent: String,
    val timestamp: Long,
    val repliesCount: Int?,
    val quotesCount: Int?,
    val upVotesCount: Int?,
    val downVotesCount: Int?,
    /** How many times it was tipped (KAPOSTS_INDEXER.md 5.11); absent on an older indexer. */
    val tipsCount: Int? = null,
    val parentPostId: String?,
    val mentionedPubkeys: List<String>?,
    val isUpvoted: Boolean?,
    val isDownvoted: Boolean?,
    val blockedUser: Boolean?,
    val contentType: String?,
    val isQuote: Boolean?,
    val quote: KQuoteRef?,
    /** Set by the indexer when the text shown is an accepted edit (ms). [postContent] is then
     *  the edited text; [timestamp] stays the original's. */
    val editedAt: Long? = null,
    /** Present when this post is a poll (KAPOSTS_INDEXER.md section 5.9): the options, the
     *  counts so far, this reader's own vote and when voting closes. */
    val poll: KPoll? = null,
) {
    /** Base64 -> plain text (K encodes all content fields). */
    val decodedContent: String? get() = KaPostsProtocol.decodeB64(postContent)
}

/**
 * The indexer's poll object (KAPOSTS_INDEXER.md section 5.9). [options] are base64, like every
 * content field the API returns. [myVote] is the requesting pubkey's own option index, or null.
 */
data class KPoll(
    val options: List<String>?,
    val counts: List<Int>?,
    val total: Int?,
    val closesAt: Long?,
    val myVote: Int?,
) {
    val decodedOptions: List<String>
        get() = options.orEmpty().map { KaPostsProtocol.decodeB64(it) ?: "" }
}

/** `GET /get-poll` - one poll's current numbers, without reloading a feed. */
data class KPollResponse(
    val id: String?,
    val options: List<String>?,
    val counts: List<Int>?,
    val total: Int?,
    val closesAt: Long?,
    val myVote: Int?,
) {
    fun toPoll() = KPoll(options = options, counts = counts, total = total, closesAt = closesAt, myVote = myVote)
}

data class KPagination(val hasMore: Boolean?, val nextCursor: String?, val prevCursor: String?)

data class KPostsResponse(val posts: List<KPost>?, val pagination: KPagination?)

data class KRepliesResponse(val replies: List<KPost>?, val pagination: KPagination?)

/** `get-post?id=` - one post. */
data class KPostResponse(val post: KPost?)

/** `get-thread?id=` - the post plus its ancestors, ROOT FIRST and excluding the post itself. */
data class KThreadResponse(val ancestors: List<KPost>?, val post: KPost?)

data class KNotification(
    val id: String,
    val userPublicKey: String,
    val postContent: String?,
    val timestamp: Long,
    val contentType: String?,
    val voteType: String?,
    val contentId: String?,
    /** contentType "tip" only: what the tip paid you, in sompi (contentId = the tipped post). */
    val tipAmountSompi: Long? = null,
) {
    val decodedContent: String? get() = postContent?.let { KaPostsProtocol.decodeB64(it) }
}

/** One tip of a post: who, how much, when (`get-post-tippers`, KAPOSTS_INDEXER.md 5.11). */
data class KTip(
    /** The tip's txid. */
    val id: String,
    val userPublicKey: String,
    val amountSompi: Long,
    val timestamp: Long,
)

data class KTippersResponse(val tippers: List<KTip>?, val pagination: KPagination?)

data class KNotificationsResponse(val notifications: List<KNotification>?, val pagination: KPagination?)

/** One actor row from get-post-engagement (KaChat indexer fork). */
data class KEngagementEntry(
    val actorPubkey: String,
    val actionTxId: String,
    val timestamp: Long,
    val kind: String, // upvote | downvote | repost | quote
)

data class KEngagementResponse(val engagement: List<KEngagementEntry>?, val pagination: KPagination?)

data class KFollowUser(val userPublicKey: String, val timestamp: Long?)

/**
 * The users-list endpoints wrap their items under "posts" (verified live against the fork) -
 * keep the other plausible keys as fallbacks in case deployments differ.
 */
data class KFollowListResponse(
    val posts: List<KFollowUser>?,
    val users: List<KFollowUser>?,
    val following: List<KFollowUser>?,
    val followers: List<KFollowUser>?,
    val pagination: KPagination? = null,
) {
    val items: List<KFollowUser> get() = posts ?: users ?: following ?: followers ?: emptyList()
}

data class KUserDetails(
    val userPublicKey: String?,
    val followersCount: Int?,
    val followingCount: Int?,
    val followedUser: Boolean?,
)

/** REST read API of the K social indexer (Settings > Connection Settings > KaPost Indexer). */
interface KaPostApi {
    @GET("get-posts-watching")
    suspend fun getPostsWatching(
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 50,
        @Query("before") before: String? = null,
    ): KPostsResponse

    @GET("get-contents-following")
    suspend fun getContentsFollowing(
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 50,
        @Query("before") before: String? = null,
    ): KPostsResponse

    @GET("get-posts")
    suspend fun getUserPosts(
        @Query("user") user: String,
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 50,
        @Query("includeReplies") includeReplies: String? = null,
        @Query("before") before: String? = null,
    ): KPostsResponse

    @GET("get-replies")
    suspend fun getReplies(
        @Query("post") postId: String,
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 100,
        @Query("before") before: String? = null,
    ): KRepliesResponse

    /** One post by id, any age, any author - what makes a reply's context reachable at all. */
    @GET("get-post")
    suspend fun getPost(
        @Query("id") id: String,
        @Query("requesterPubkey") requesterPubkey: String,
    ): KPostResponse

    /** One poll's options, counts and the requester's own vote (section 5.9). */
    @GET("get-poll")
    suspend fun getPoll(
        @Query("postId") postId: String,
        @Query("requesterPubkey") requesterPubkey: String,
    ): KPollResponse

    /** A post plus its ancestor chain, root first, done server-side in one request. */
    @GET("get-thread")
    suspend fun getThread(
        @Query("id") id: String,
        @Query("requesterPubkey") requesterPubkey: String,
    ): KThreadResponse

    /** get-replies dual mode: `user` instead of `post` returns all replies MADE BY that user. */
    @GET("get-replies")
    suspend fun getUserReplies(
        @Query("user") user: String,
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 50,
        @Query("before") before: String? = null,
    ): KRepliesResponse

    @GET("get-notifications")
    suspend fun getNotifications(
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 100,
        @Query("before") before: String? = null,
    ): KNotificationsResponse

    /** Who tipped a post, newest first (KAPOSTS_INDEXER.md 5.11). */
    @GET("get-post-tippers")
    suspend fun getPostTippers(
        @Query("postId") postId: String,
        @Query("limit") limit: Int = 50,
        @Query("before") before: String? = null,
    ): KTippersResponse

    @GET("get-post-engagement")
    suspend fun getPostEngagement(
        @Query("postId") postId: String,
        @Query("type") type: String = "all",
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 100,
        @Query("before") before: String? = null,
    ): KEngagementResponse

    /**
     * The follow-list endpoints are not documented as cursored; `before` is sent anyway so a
     * deployment that supports it paginates, and one that ignores it simply replays page one -
     * which the caller detects (no new ids) and treats as "end of list" instead of looping.
     */
    @GET("get-users-following")
    suspend fun getUsersFollowing(
        @Query("userPubkey") userPubkey: String,
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 100,
        @Query("before") before: String? = null,
    ): KFollowListResponse

    @GET("get-users-followers")
    suspend fun getUsersFollowers(
        @Query("userPubkey") userPubkey: String,
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 100,
        @Query("before") before: String? = null,
    ): KFollowListResponse

    @GET("get-user-details")
    suspend fun getUserDetails(
        @Query("user") user: String,
        @Query("requesterPubkey") requesterPubkey: String,
    ): KUserDetails

    /** Hands a signed transaction to the indexer to submit at its time (section 5.10). */
    @POST("schedule-post")
    suspend fun schedulePost(@Body body: KSchedulePostRequest): KScheduleResponse

    /** This wallet's scheduled posts, as the indexer sees them. */
    @GET("scheduled-posts")
    suspend fun getScheduledPosts(@Query("pubkey") pubkey: String): KScheduledPostsResponse

    /** Takes back a post the indexer has not submitted yet. */
    @POST("cancel-scheduled-post")
    suspend fun cancelScheduledPost(@Body body: KCancelScheduledRequest): KScheduleResponse
}

/** `POST /schedule-post` (section 5.10). [transaction] is the Kaspa REST `POST /transactions`
 *  body verbatim, which is what the indexer submits on the phone's behalf. */
data class KSchedulePostRequest(
    val pubkey: String,
    val txId: String,
    val notBefore: Long,
    val signature: String,
    val transaction: KRestTransactionEnvelope,
)

data class KRestTransactionEnvelope(val transaction: RawTransaction)

data class KCancelScheduledRequest(val pubkey: String, val txId: String, val signature: String)

data class KScheduleResponse(val txId: String?, val notBefore: Long?, val status: String?)

/** One row of `GET /scheduled-posts`. */
data class KScheduledPost(
    val txId: String,
    val notBefore: Long?,
    val status: String?,
    val submittedAt: Long?,
    val error: String?,
    val postContent: String?,
)

data class KScheduledPostsResponse(val posts: List<KScheduledPost>?)

