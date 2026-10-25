package com.fantasyidler.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fantasyidler.data.model.EquipSlot
import com.fantasyidler.data.model.PlayerFlags
import com.fantasyidler.data.model.Skills
import com.fantasyidler.simulator.HeirloomStats
import com.fantasyidler.simulator.PotionEffects
import com.fantasyidler.simulator.XpTable
import com.fantasyidler.util.craftDurationEfficiency
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class HerbloreIntegrationTest {
    private val data by lazy {
        GameDataRepository(ApplicationProvider.getApplicationContext<Context>(), Json { ignoreUnknownKeys = true })
    }

    @Test fun `metal tools have matching smithing recipes and herblore equip gates`() {
        for (tier in listOf("bronze", "iron", "steel", "mithril", "adamantite", "runite")) {
            val key = "${tier}_mortar"
            val equipment = data.equipment.getValue(key)
            val recipe = data.smithingRecipes.getValue(key)
            assertEquals(EquipSlot.MORTAR, equipment.slot)
            assertEquals(mapOf("${tier}_bar" to 2), recipe.materials)
            assertEquals(mapOf(Skills.HERBLORE to recipe.levelRequired), equipment.requirements)
        }
        assertTrue(EquipSlot.MORTAR in EquipSlot.TOOL_SLOTS)
    }

    @Test fun `runite tool gives expected XP and speed while old recipes get tier efficiency`() {
        val equipped = mapOf(EquipSlot.MORTAR to "runite_mortar")
        val levels = mapOf(Skills.HERBLORE to 99)
        val eff = data.craftDurationEfficiency(Skills.HERBLORE, "super_magic_potion", equipped, levels)
        assertEquals(2.25f, eff, .0001f)
        assertEquals(900.0, data.herbloreRecipes.getValue("super_magic_potion").xpPerItem * eff, .001)
        assertEquals(17777, (40000 / eff).toInt())
        assertEquals(5.0625f, data.craftDurationEfficiency(Skills.HERBLORE, "attack_brew", equipped, levels), .0001f)
        assertEquals(1f, data.craftDurationEfficiency(Skills.HERBLORE, "super_magic_potion", emptyMap(), levels), .0001f)
    }

    @Test fun `heirloom scales with item XP and herblore and mirrors the correct slot`() {
        val tool = data.equipment.getValue("heirloom_mortar")
        assertEquals(EquipSlot.MORTAR, HeirloomStats.slotForSkill(Skills.HERBLORE))
        assertEquals(1.25f, HeirloomStats.resolve(tool, 0, 99).herbloreEfficiency!!, .0001f)
        assertEquals(4.5f, HeirloomStats.resolve(tool, XpTable.xpForLevel(99), 85).herbloreEfficiency!!, .0001f)
        assertTrue(HeirloomStats.resolve(tool, XpTable.xpForLevel(99), 1).herbloreEfficiency!! < 1.30f)
        val drop = data.bosses.getValue("brood_empress").rareDrops.single { it.item == "heirloom_mortar" }
        assertEquals(.01, drop.chance, .00001)
        assertTrue("heirloom_mortar" in HeirloomStats.ownedHeirloomKeys(data.equipment, mapOf("heirloom_mortar" to 1)))
    }

    @Test fun `new dwarven mortar does not revoke divine eligibility or become unobtainable`() {
        val daily = DailyQuestRepository(data)
        val oldSet = daily.dwarvenDropPool.filter { it != "dwarven_mortar" }.toSet()
        val pool = daily.dailyEquipmentPool(oldSet)
        assertTrue("dwarven_mortar" in pool)
        assertTrue(pool.containsAll(WeeklyQuestRepository.DIVINE_DROP_POOL))
        assertFalse(daily.dailyEquipmentPool(emptySet()).any { it.startsWith("divine_") })
        assertTrue(daily.dailyEquipmentPool(oldSet + pool).isEmpty())
    }

    @Test fun `enhanced substitutes count once toward base and super guild requests`() {
        val items = mapOf("enhanced_overload_potion" to 10, "super_strength_potion" to 2)
        assertEquals(12, GuildRepository.countForTarget(items, "strength_potion"))
        assertEquals(12, GuildRepository.countForTarget(items, "super_strength_potion"))
        assertEquals(10, GuildRepository.countForTarget(items, "overload_potion"))
        assertEquals(0, GuildRepository.countForTarget(items, "attack_brew"))
    }

    @Test fun `prestige potency never becomes extra magic damage percent`() {
        val effects = data.potionEffects("enhanced_overload_potion", Skills.ALL.associateWith { 99 })
        val flags = PlayerFlags(prestigeNodes = mapOf(Skills.HERBLORE to listOf("herblore_potency_1", "herblore_potency_2", "herblore_potency_3")))
        val result = BoostRepository(data).boostedPotionEffects(flags, effects)
        assertEquals(51, result["attack"])
        assertEquals(20, result[PotionEffects.MAGIC_DAMAGE_PERCENT])
        assertTrue(data.potionEffects("missing", emptyMap()).isEmpty())
    }
}
