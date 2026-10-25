#!/usr/bin/env python3
"""Reproducible Herblore balance worksheet; no pets, prestige, capes or town boosts.

Run from any directory. Output is JSON so results can be saved and compared.
Brewing rates assume Agility 99 (40 seconds before tools). Harvest rates use
five patches, Runite Hoe, Redwood Ashes, and one harvest per day for slow crops.
Implemented plan: smithable Bronze–Runite mortars plus Dwarven/Divine quest
rewards and a 1/100 Brood Empress heirloom; recipe XP receives a smaller supply
premium because tools multiply both XP and speed. Guild boards require fewer
potions, ranks 7–8 pay competitive XP, and enhanced Overload scales to +48 at
level 99 (15%, reduced from the initially proposed 20%). Farming timers/yields
and potion consumption stay unchanged.

Combat rows are expected damage per attack from the game's hit/max-hit formulas,
not a complete fight simulation (food, mercenaries and survival are excluded).
"""
import json
import math
import itertools
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'app/src/main/assets/data'

# Pre-rebalance values, retained for reproducible before/after comparisons.
OLD_XP = dict(zip([
    'attack_brew', 'defense_brew', 'onion_brew', 'carrot_brew', 'strength_brew',
    'attack_potion', 'defense_potion', 'strength_potion', 'ranging_potion',
    'magic_potion', 'super_strength_potion', 'super_attack_potion',
    'super_defense_potion', 'super_ranging_potion', 'super_magic_potion', 'overload_potion',
], [10, 15, 20, 30, 30, 25, 38, 55, 60, 75, 105, 125, 145, 180, 210, 350]))

TIERS = [1, 15, 30, 55, 70, 85]


def load(name):
    return json.loads((DATA / name).read_text())


def efficiency(level, base=2.25, tool_level=85):
    tier = lambda n: sum(n >= t for t in TIERS) - 1
    return base * (1 + .25 * max(0, tier(tool_level) - tier(level)))


def hit_chance(attack, defence):
    return min(.95, max(.10, 1 - defence / (2 * attack)
                       if attack > defence else attack / (2 * defence)))


def combat(level, gear, defence, style, bonus, magic_pct=0):
    max_hit = (math.floor(gear * (100 + magic_pct) / 100) if style == 'magic'
               else 1 + (level + bonus + gear) * (gear + 64) // 640)
    return max_hit / 2 * hit_chance(level + bonus + gear, defence)


def report():
    recipes, crops = load('recipes/herblore.json'), load('crops.json')
    rows = []
    for key, recipe in sorted(recipes.items(), key=lambda pair: pair[1]['level_required']):
        eff = efficiency(recipe['level_required'])
        crop_key, count = next((k, v) for k, v in recipe['materials'].items() if k in crops)
        crop = crops[crop_key]
        # Kotlin roundToInt rounds positive ties upward.
        harvest = sum(math.floor(n * 2.25 * 2.5 + .5) for n in
                      range(crop['yield_min'], crop['yield_max'] + 1)) / (crop['yield_max'] - crop['yield_min'] + 1)
        potions = 5 * harvest / count
        xp = recipe['xp_per_item']
        rows.append(dict(recipe=key, level=recipe['level_required'], efficiency=eff,
                         old_xp_hour=OLD_XP[key] * 90,
                         new_xp_hour=round(xp * eff ** 2 * 90),
                         heirloom_xp_hour=round(xp * efficiency(recipe['level_required'], 4.5) ** 2 * 90),
                         potions_per_harvest=round(potions, 1), growth_hours=crop['growth_time_hours'],
                         old_xp_harvest=round(potions * OLD_XP[key]),
                         new_xp_harvest=round(potions * xp * eff)))
    elder_source = (ROOT / 'app/src/main/kotlin/com/fantasyidler/data/model/ElderContent.kt').read_text()
    elder_recipes = set(re.findall(r'"([^"]+)"', elder_source.split('val SMITHING_RECIPES = setOf(')[1].split(')')[0]))
    comparisons = []
    for level in [30, 55, 85, 99]:
        for skill in ['herblore', 'smithing', 'crafting']:
            rs = load(f'recipes/{skill}.json')
            # Mainland equipment only, excluding elder recipes.
            eligible = [(k, r) for k, r in rs.items() if r['level_required'] <= level
                        and k not in elder_recipes]
            tool_level = max(t for t in TIERS if t <= level)
            base = 1 + TIERS.index(tool_level) * .25
            def rate(pair):
                k, r = pair
                xp = r['xp_per_item']
                eff = efficiency(r['level_required'], base, tool_level) if skill != 'crafting' else 1
                return xp * eff ** 2 * 90
            k, r = max(eligible, key=rate)
            comparisons.append(dict(level=level, skill=skill, best_recipe=k, xp_hour=round(rate((k, r)))))
    fights = []
    for level, gear, defence in [(30, 20, 60), (60, 80, 150), (99, 80, 200), (99, 200, 300)]:
        for style in ['melee', 'ranged', 'magic']:
            old = 20
            recipe = recipes['overload_potion']
            new = 2 * (recipe['effects']['attack'] + level * recipe['effect_percent'] // 100)
            base = combat(level, gear, defence, style, 0)
            fights.append(dict(level=level, gear=gear, enemy_defence=defence, style=style,
                               old_enhanced_overload_gain_pct=round((combat(level, gear, defence, style, old) / base - 1) * 100, 1),
                               new_enhanced_overload_gain_pct=round((combat(level, gear, defence, style, new, 20) / base - 1) * 100, 1)))
    # Every top-rank daily requests Overloads; one batch advances all four.
    # Enumerate every possible 4-of-7 board, taking MAX quantity, never SUM.
    quests = [q for q in load('guild_daily_quests.json') if q['guild'] == 'herblore' and q['guild_level_min'] == 9]
    old_amounts = [25, 42, 68, 33, 40, 65, 100]
    old_rewards = [60000, 80000, 100000, 70000, 96000, 124000, 147000]
    boards = list(itertools.combinations(range(len(quests)), 4))
    guild = dict(boards=len(boards),
        old_mean_potions=sum(max(old_amounts[i] for i in board) for board in boards) / len(boards),
        new_mean_potions=sum(max(quests[i]['amount'] for i in board) for board in boards) / len(boards),
        old_mean_reward=sum(sum(old_rewards[i] for i in board) for board in boards) / len(boards),
        new_mean_reward=sum(sum(quests[i]['rewards']['xp'] for i in board) for board in boards) / len(boards))
    lotus = crops['void_lotus']
    yields = [math.floor(n * 2.25 * 2.5 + .5) for n in range(lotus['yield_min'], lotus['yield_max'] + 1)]
    harvests = [sum(patches) for patches in itertools.product(yields, repeat=5)]
    for label, amounts in [('old', old_amounts), ('new', [q['amount'] for q in quests])]:
        completed = sum(harvest >= max(amounts[i] for i in board) for board in boards for harvest in harvests)
        guild[f'{label}_one_harvest_board_completion_pct'] = round(100 * completed / (len(boards) * len(harvests)), 2)
    return dict(brewing=rows, same_level_comparison=comparisons, expected_combat=fights, overload_daily_boards=guild)


if __name__ == '__main__':
    print(json.dumps(report(), indent=2))
