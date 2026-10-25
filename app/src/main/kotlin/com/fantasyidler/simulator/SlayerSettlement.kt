package com.fantasyidler.simulator

import com.fantasyidler.data.model.SlayerTask

/** Calculates one collected session against a fixed set of assignments. */
object SlayerSettlement {
    data class Result(
        val baseXp: Long,
        val taskKills: Int,
        val completedTasks: List<SlayerTask>,
        val activeTask: SlayerTask?,
        val foretoldTasks: List<SlayerTask>,
    )

    fun calculate(
        activeTask: SlayerTask?,
        foretoldTasks: List<SlayerTask>,
        killsByEnemy: Map<String, Int>,
        multiTask: Boolean = false,
    ): Result {
        val unchanged = Result(0L, 0, emptyList(), activeTask, foretoldTasks)
        if (activeTask == null) return unchanged
        val chain = listOf(activeTask) + foretoldTasks
        // Normal Foretell can finish consecutive same-enemy tasks, but cannot activate a
        // different target halfway through this session. Foresight permits any queued target.
        val eligibleCount = if (multiTask) chain.size else chain.takeWhile { it.enemyKey == activeTask.enemyKey }.size
        val eligible = chain.take(eligibleCount).filterNot { it.isComplete }
        val rates = eligible.distinctBy { it.enemyKey }.associate { it.enemyKey to it.xpPerKill }
        val qualifyingKills = killsByEnemy.filter { (enemy, count) -> enemy in rates && count > 0 }
        if (qualifyingKills.isEmpty()) return unchanged

        // One XP award per kill, even with duplicate assignments. The first eligible
        // assignment supplies the stored rate; quotas only limit progress and points.
        val xp = qualifyingKills.entries.sumOf { (enemy, count) -> count.toLong() * rates.getValue(enemy) }
        val remainingKills = qualifyingKills.toMutableMap()
        val completed = mutableListOf<SlayerTask>()
        val open = mutableListOf<SlayerTask>()
        for ((index, task) in chain.withIndex()) {
            val canProgress = index < eligibleCount
            val available = if (canProgress && !task.isComplete) remainingKills[task.enemyKey] ?: 0 else 0
            val added = minOf(available, (task.targetKills - task.killsCompleted).coerceAtLeast(0))
            val updated = task.copy(killsCompleted = task.killsCompleted + added)
            remainingKills[task.enemyKey] = (remainingKills[task.enemyKey] ?: 0) - added
            if (added > 0 && updated.isComplete) completed += updated else open += updated
        }
        return Result(
            baseXp = xp,
            taskKills = qualifyingKills.values.sumOf { it.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            completedTasks = completed,
            activeTask = open.firstOrNull(),
            foretoldTasks = open.drop(1),
        )
    }
}
