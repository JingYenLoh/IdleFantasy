package com.fantasyidler.simulator

import com.fantasyidler.data.model.SlayerTask
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SlayerSettlementTest {
    private val dragon = SlayerTask("dragon", 14, xpPerKill = 140, taskPoints = 14)
    private val demon = SlayerTask("demon", 20, xpPerKill = 110, taskPoints = 12)

    @Test fun `quota caps completion but not session XP or guild kills`() {
        val result = SlayerSettlement.calculate(dragon, emptyList(), mapOf("dragon" to 160))
        assertEquals(22_400L, result.baseXp)
        assertEquals(160, result.taskKills)
        assertEquals(listOf(dragon.copy(killsCompleted = 14)), result.completedTasks)
        assertNull(result.activeTask)
        assertTrue(result.foretoldTasks.isEmpty())
    }

    @Test fun `partial task carries progress into the next session without losing overflow XP`() {
        val first = SlayerSettlement.calculate(dragon, listOf(demon), mapOf("dragon" to 10))
        assertEquals(1_400L, first.baseXp)
        assertEquals(dragon.copy(killsCompleted = 10), first.activeTask)
        assertTrue(first.completedTasks.isEmpty())
        val second = SlayerSettlement.calculate(first.activeTask, first.foretoldTasks, mapOf("dragon" to 160))
        assertEquals(22_400L, second.baseXp)
        assertEquals(1, second.completedTasks.size)
        assertEquals(demon, second.activeTask)
    }

    @Test fun `exact quota completes once and subsequent sessions need a new assignment`() {
        val first = SlayerSettlement.calculate(dragon, emptyList(), mapOf("dragon" to 14))
        assertEquals(1_960L, first.baseXp)
        val second = SlayerSettlement.calculate(first.activeTask, first.foretoldTasks, mapOf("dragon" to 160))
        assertEquals(0L, second.baseXp)
        assertEquals(0, second.taskKills)
        assertTrue(second.completedTasks.isEmpty())
    }

    @Test fun `no active assignment does not activate foretold tasks implicitly`() {
        val result = SlayerSettlement.calculate(null, listOf(dragon), mapOf("dragon" to 160), multiTask = true)
        assertEquals(0L, result.baseXp)
        assertEquals(listOf(dragon), result.foretoldTasks)
    }

    @Test fun `empty unrelated and nonpositive kills do not change tasks`() {
        for (kills in listOf(emptyMap(), mapOf("goblin" to 100), mapOf("dragon" to 0), mapOf("dragon" to -1))) {
            val result = SlayerSettlement.calculate(dragon, listOf(demon), kills)
            assertEquals(0L, result.baseXp)
            assertEquals(0, result.taskKills)
            assertEquals(dragon, result.activeTask)
            assertEquals(listOf(demon), result.foretoldTasks)
        }
    }

    @Test fun `different next target waits until the next session regardless of map order`() {
        val a = SlayerSettlement.calculate(dragon, listOf(demon), linkedMapOf("dragon" to 160, "demon" to 100))
        val b = SlayerSettlement.calculate(dragon, listOf(demon), linkedMapOf("demon" to 100, "dragon" to 160))
        assertEquals(a, b)
        assertEquals(22_400L, a.baseXp)
        assertEquals(160, a.taskKills)
        assertEquals(demon, a.activeTask)
        assertEquals(1, a.completedTasks.size)
    }

    @Test fun `consecutive duplicate assignments use a shared kill budget and pay XP once`() {
        val result = SlayerSettlement.calculate(dragon, listOf(dragon, demon), mapOf("dragon" to 160))
        assertEquals(22_400L, result.baseXp)
        assertEquals(160, result.taskKills)
        assertEquals(2, result.completedTasks.size)
        assertEquals(28, result.completedTasks.sumOf { it.taskPoints })
        assertEquals(demon, result.activeTask)
        val partial = SlayerSettlement.calculate(dragon, listOf(dragon), mapOf("dragon" to 20))
        assertEquals(2_800L, partial.baseXp)
        assertEquals(1, partial.completedTasks.size)
        assertEquals(dragon.copy(killsCompleted = 6), partial.activeTask)
    }

    @Test fun `normal foretell cannot jump a different enemy to complete a later duplicate`() {
        val result = SlayerSettlement.calculate(dragon, listOf(demon, dragon), mapOf("dragon" to 160))
        assertEquals(22_400L, result.baseXp)
        assertEquals(1, result.completedTasks.size)
        assertEquals(demon, result.activeTask)
        assertEquals(listOf(dragon), result.foretoldTasks)
    }

    @Test fun `multi task credits each eligible enemy once including nonconsecutive duplicates`() {
        val kills = linkedMapOf("dragon" to 160, "demon" to 100, "goblin" to 500)
        val result = SlayerSettlement.calculate(dragon, listOf(demon, dragon), kills, multiTask = true)
        assertEquals(result, SlayerSettlement.calculate(dragon, listOf(demon, dragon), kills.entries.reversed().associate { it.toPair() }, true))
        assertEquals(33_400L, result.baseXp)
        assertEquals(260, result.taskKills)
        assertEquals(3, result.completedTasks.size)
        assertNull(result.activeTask)
    }

    @Test fun `multi task can finish a foretold assignment while the active one is untouched`() {
        val result = SlayerSettlement.calculate(dragon, listOf(demon, dragon), mapOf("demon" to 100), true)
        assertEquals(11_000L, result.baseXp)
        assertEquals(listOf(demon.copy(killsCompleted = 20)), result.completedTasks)
        assertEquals(dragon, result.activeTask)
        assertEquals(listOf(dragon), result.foretoldTasks)
    }

    @Test fun `multi task keeps partially filled duplicate assignments in order`() {
        val result = SlayerSettlement.calculate(dragon, listOf(demon, dragon), mapOf("dragon" to 20, "demon" to 3), true)
        assertEquals(3_130L, result.baseXp)
        assertEquals(demon.copy(killsCompleted = 3), result.activeTask)
        assertEquals(listOf(dragon.copy(killsCompleted = 6)), result.foretoldTasks)
        assertEquals(1, result.completedTasks.size)
    }

    @Test fun `legacy stored rates use first eligible assignment rather than stacking rates`() {
        val restored = Json.decodeFromString<SlayerTask>("""{"enemy_key":"dragon","target_kills":14,"xp_per_kill":135,"task_points":14}""")
        val result = SlayerSettlement.calculate(restored, listOf(dragon), mapOf("dragon" to 160))
        assertEquals(21_600L, result.baseXp)
        assertEquals(2, result.completedTasks.size)
    }

    @Test fun `XP arithmetic does not overflow Int and progress stays bounded`() {
        val result = SlayerSettlement.calculate(dragon, listOf(demon), mapOf("dragon" to Int.MAX_VALUE, "demon" to Int.MAX_VALUE), true)
        assertEquals(Int.MAX_VALUE.toLong() * 250, result.baseXp)
        assertEquals(Int.MAX_VALUE, result.taskKills)
        assertTrue(result.completedTasks.all { it.killsCompleted == it.targetKills })
    }

    @Test fun `invalid already completed task cannot award completion rewards again`() {
        val result = SlayerSettlement.calculate(dragon.copy(killsCompleted = 14), emptyList(), mapOf("dragon" to 160))
        assertEquals(0L, result.baseXp)
        assertTrue(result.completedTasks.isEmpty())
    }
}
