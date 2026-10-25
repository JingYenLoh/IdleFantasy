package com.fantasyidler.simulator

import com.fantasyidler.data.json.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.random.Random

class HerbloreCombatTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun asset(path: String): String = listOf("src/main/assets/data/$path", "app/src/main/assets/data/$path")
        .map(::File).first { it.exists() }.readText()
    private val recipes = json.decodeFromString<Map<String, HerbloreRecipe>>(asset("recipes/herblore.json"))
    private val levels = listOf("attack", "strength", "defense", "ranged", "magic").associateWith { 99 }

    @Test fun `scaling floors each base skill separately before enhancement`() {
        val effect = PotionEffects.resolve(recipes.getValue("overload_potion"), levels + ("magic" to 50), true)
        assertEquals(48, effect["attack"])
        assertEquals(34, effect["magic"])
        assertEquals(20, effect[PotionEffects.MAGIC_DAMAGE_PERCENT])
        assertEquals(24, PotionEffects.resolve(recipes.getValue("overload_potion"), levels)["strength"])
        assertEquals(mapOf("attack" to 6), PotionEffects.resolve(recipes.getValue("attack_brew"), levels, true))
        assertEquals(5, PotionEffects.resolve(recipes.getValue("attack_potion"), mapOf("attack" to 1))["attack"])
    }

    @Test fun `legacy recipes retain flat effects without new fields`() {
        val recipe = json.decodeFromString<HerbloreRecipe>("""{
            "display_name":"Legacy", "level_required":1, "materials":{},
            "xp_per_item":10, "effects":{"attack":3}
        }""")
        assertEquals(mapOf("attack" to 3), PotionEffects.resolve(recipe, levels))
        assertEquals(mapOf("attack" to 6), PotionEffects.resolve(recipe, levels, true))
    }

    @Test fun `magic damage rounds once and leaves unboosted spells unchanged`() {
        assertEquals(40, PotionEffects.magicMaxHit(40, 0))
        assertEquals(48, PotionEffects.magicMaxHit(40, 20))
        assertEquals(11, PotionEffects.magicMaxHit(10, 12))
        assertEquals(1, PotionEffects.magicMaxHit(0, 20))
    }

    private fun dungeonRun(style: String, gear: Int, bonus: Map<String, Int>, seed: Int): List<com.fantasyidler.data.model.SessionFrame> {
        val enemy = EnemyData(
            name = "target", displayName = "Target", hp = 10_000_000,
            combatStats = EnemyCombatStats(1, 0, 1, 0, 0),
            defensiveStats = EnemyDefensiveStats(300, 300, 300, 300),
            xpDrops = mapOf("combat" to 20),
        )
        return CombatSimulator.simulateDungeon(
            dungeon = DungeonData("range", "Range", "", 1, 1.0, listOf(EnemySpawn("target", 1))),
            enemies = mapOf("target" to enemy),
            playerAttack = 99, playerStrength = 99, playerDefence = 99, playerHp = 999,
            weaponAttackBonus = gear, weaponStrengthBonus = gear,
            combatStyle = style, playerRanged = 99, playerMagic = 99,
            rangedGearStrengthBonus = gear, spellMaxHit = 80,
            availableArrows = mapOf("arrow" to Int.MAX_VALUE), arrowStrengthBonuses = mapOf("arrow" to 0),
            potionBonuses = bonus, random = Random(seed),
        ).frames
    }

    @Test fun `seeded endgame combat gains remain meaningful across all three styles`() {
        val old = levels.mapValues { 20 }
        val enhanced = PotionEffects.resolve(recipes.getValue("overload_potion"), levels, true)
        for (style in listOf("attack", "ranged", "magic")) {
            fun damage(bonus: Map<String, Int>): Long = (0 until 100).sumOf { seed ->
                dungeonRun(style, 200, bonus, seed).sumOf { frame -> frame.playerHits.sum().toLong() }
            }
            val baseDamage = damage(emptyMap())
            val oldDamage = damage(old)
            val newDamage = damage(enhanced)
            val gain = newDamage.toDouble() / baseDamage - 1
            println("Herblore simulation: style=$style runs=100 oldGain=${oldDamage.toDouble() / baseDamage - 1} newGain=$gain")
            assertTrue("$style should improve over the old Overload", newDamage > oldDamage)
            assertTrue("$style endgame damage gain outside 20–45%: $gain", gain in .20.. .45)
        }
    }

    @Test fun `magic potion damage is applied and attributed in dungeons and bosses`() {
        val bonus = PotionEffects.resolve(recipes.getValue("overload_potion"), levels, true)
        val dungeon = dungeonRun("magic", 200, bonus, 1).first().statsAtStart
        assertEquals(96, dungeon["str"])
        assertEquals(16, dungeon["str_potion"])
        val boss = json.decodeFromString<Map<String, BossData>>(asset("raid_bosses.json")).getValue("king_black_dragon")
            .copy(hp = 10_000_000, durationMinutes = 1)
        val frames = CombatSimulator.simulateBoss(
            boss = boss, bossKey = boss.id, playerAttack = 99, playerStrength = 99,
            playerDefence = 999, playerHp = 999, weaponAttackBonus = 200, weaponStrBonus = 0,
            combatStyle = "magic", playerMagic = 147, spellMaxHit = 80,
            potionMagicDamagePercent = 20, random = Random(1),
        )
        assertEquals(96, frames.first().statsAtStart["str"])
        assertEquals(16, frames.first().statsAtStart["str_potion"])
        assertTrue(frames.flatMap { it.playerHits }.all { it <= 96 })
    }
}
