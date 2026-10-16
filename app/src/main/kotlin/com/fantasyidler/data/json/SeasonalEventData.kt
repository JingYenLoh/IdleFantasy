package com.fantasyidler.data.json

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * A yearly event template from seasonal_events.json. [id] in the JSON is the series key
 * (e.g. "gloomharvest"); SeasonalCalendar stamps out each year's run with id
 * "<series>_<year>" and fills in [series], [year], [startMs] and [endMs].
 */
@Serializable
data class SeasonalEventData(
    val id: String,
    @SerialName("display_name") val displayName: String,
    /** "spring" | "summer" | "autumn" | "winter" (meteorological, UTC). */
    val season: String,
    /** First year this event runs; earlier years never produce a run. */
    @SerialName("first_year") val firstYear: Int = 2026,
    @Transient val series: String = "",
    @Transient val year: Int = 0,
    @Transient val startMs: Long = 0L,
    @Transient val endMs: Long = 0L,
    @SerialName("token_goal") val tokenGoal: Int,
    /** Subset of "bounty" | "expedition" | "boss" | "minigame". */
    val pillars: List<String>,
    /** Emoji icon displayed for this event's quest indicators (e.g. "☀️" or "🎃"); defaults to "🎯". */
    @SerialName("icon_emoji") val iconEmoji: String = "🎯",
    @SerialName("bounty_tasks") val bountyTasks: List<SeasonalBountyTaskData> = emptyList(),
    /** How long a Bounty Board slot waits after a claim before a new task rotates in. */
    @SerialName("bounty_rotation_ms") val bountyRotationMs: Long = 3_600_000L,
    @SerialName("expedition_dungeon_key") val expeditionDungeonKey: String? = null,
    /** Multi-dungeon events list every expedition here; single-dungeon events may use [expeditionDungeonKey] instead. */
    @SerialName("expedition_dungeon_keys") val expeditionDungeonKeys: List<String> = emptyList(),
    @SerialName("boss_key") val bossKey: String? = null,
    val minigame: SeasonalMinigameConfig? = null,
    @SerialName("reward_tiers") val rewardTiers: List<SeasonalRewardTierData> = emptyList(),
    /** Coin-priced offers shown in the event's Night Market section; empty = no market. */
    @SerialName("night_market") val nightMarket: List<NightMarketOfferData> = emptyList(),
    /** English fallback for the earned banner's text; "%1$d" is replaced with the run's year. */
    @SerialName("banner_text") val bannerText: String,
    /** Drawable resource name (e.g. "banner_summer") shown for this event on the Home card and Profile Banners tab. */
    @SerialName("banner_icon") val bannerIcon: String? = null,
) {
    fun isActiveAt(nowMs: Long): Boolean = nowMs >= startMs && nowMs < endMs

    /** Every expedition dungeon key for this event, whichever field the JSON used. */
    fun expeditionKeys(): List<String> = expeditionDungeonKeys.ifEmpty { listOfNotNull(expeditionDungeonKey) }
}

/**
 * A single Bounty Board task. [type] is "gather" | "craft" | "kill" | "turn_in", matched the same
 * way guild quests are. "turn_in" tasks track no progress — the player donates [amount] of
 * [target] straight from their inventory when claiming.
 */
@Serializable
data class SeasonalBountyTaskData(
    val id: String,
    val type: String,
    val target: String,
    val amount: Int,
    @SerialName("display_name") val displayName: String,
    /** Short "where to do this" hint shown under the task, e.g. "Woodcutting" or "The Sunspire Expedition". */
    val hint: String,
    /** Skills constant this task is done in (e.g. "woodcutting", "herblore"); null for "kill" tasks, which route via the expedition dungeon instead. */
    val skill: String? = null,
)

/**
 * A seasonal minigame. [type] picks the game:
 *
 * "whack" (default): a reflex game — over [rounds] rounds, an ember lights up in a random
 * hole (of [holeCount]) for [visibleMs] and the player must tap it before it goes out.
 * Landing at least [hitsRequired] hits wins a token.
 *
 * "sequence": a Simon-style memory game — each round the lanterns flash a sequence one step
 * longer ([visibleMs] per flash) and the player must tap it back in order. One wrong tap ends
 * the run; completing [hitsRequired] of [rounds] rounds wins a token.
 *
 * Either way falling short is a real failure. Easy mode ([visibleMsEasy]/[cooldownMsEasy])
 * trades a more forgiving speed for a longer cooldown between attempts; defaults to the
 * normal values if not set in data.
 */
@Serializable
data class SeasonalMinigameConfig(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val rounds: Int,
    @SerialName("hole_count") val holeCount: Int,
    @SerialName("hits_required") val hitsRequired: Int,
    @SerialName("visible_ms") val visibleMs: Long,
    @SerialName("cooldown_ms") val cooldownMs: Long,
    @SerialName("visible_ms_easy") val visibleMsEasy: Long = visibleMs,
    @SerialName("cooldown_ms_easy") val cooldownMsEasy: Long = cooldownMs,
    /** Emoji shown in a lit hole/lantern, e.g. "🔥" for the summer bonfire skin. */
    val emoji: String = "🔥",
    /** "whack" | "sequence". */
    val type: String = "whack",
)

/**
 * One reward tier on the event's token track. All payload fields are optional so older
 * events (banner-only) keep deserializing; the final tier's banner + title stay on the
 * automatic award path in SeasonalEventRepository and carry no payload here.
 */
@Serializable
data class SeasonalRewardTierData(
    val tokens: Int,
    val description: String,
    val coins: Long = 0,
    val items: Map<String, Int> = emptyMap(),
    @SerialName("pet_id") val petId: String? = null,
    /** Grants the 48h XP boost (same effect the marketplace sells) for free. */
    @SerialName("xp_boost") val xpBoost: Boolean = false,
)

/**
 * A coin-priced Night Market offer: either an item bundle ([items]) or a utility [effect]
 * ("skip_bounty_cooldowns" | "skip_minigame_cooldown"). [limit] caps purchases per event;
 * null/absent = repeatable.
 */
@Serializable
data class NightMarketOfferData(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("coin_cost") val coinCost: Long,
    val limit: Int? = null,
    val items: Map<String, Int> = emptyMap(),
    val effect: String? = null,
)
