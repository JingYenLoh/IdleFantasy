package com.fantasyidler.simulator

import com.fantasyidler.data.json.HerbloreRecipe

/** Resolve once at session start using unboosted levels, before equipment or prestige. */
object PotionEffects {
    const val MAGIC_DAMAGE_PERCENT = "magic_damage_percent"

    fun resolve(recipe: HerbloreRecipe, levels: Map<String, Int>, enhanced: Boolean = false): Map<String, Int> {
        val multiplier = if (enhanced) 2 else 1
        return buildMap {
            recipe.effects.forEach { (skill, flat) ->
                val level = (levels[skill] ?: 1).coerceAtLeast(1)
                put(skill, (flat + level * recipe.effectPercent / 100) * multiplier)
            }
            if (recipe.magicDamagePercent > 0) put(MAGIC_DAMAGE_PERCENT, recipe.magicDamagePercent * multiplier)
        }
    }

    fun magicMaxHit(base: Int, percent: Int): Int =
        (base.coerceAtLeast(1).toLong() * (100 + percent.coerceAtLeast(0)) / 100).toInt().coerceAtLeast(1)
}
