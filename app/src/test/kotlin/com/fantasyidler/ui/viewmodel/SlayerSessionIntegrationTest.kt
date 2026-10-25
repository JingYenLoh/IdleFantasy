package com.fantasyidler.ui.viewmodel

import android.content.Context
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fantasyidler.data.db.AppDatabase
import com.fantasyidler.data.model.EquipSlot
import com.fantasyidler.data.model.QuestProgress
import com.fantasyidler.data.model.SessionFrame
import com.fantasyidler.data.model.SkillSession
import com.fantasyidler.data.model.Skills
import com.fantasyidler.data.model.SlayerTask
import com.fantasyidler.repository.BackupScheduler
import com.fantasyidler.repository.BoostRepository
import com.fantasyidler.repository.BuffNotificationScheduler
import com.fantasyidler.repository.DailyQuestRepository
import com.fantasyidler.repository.FarmingRepository
import com.fantasyidler.repository.GameDataRepository
import com.fantasyidler.repository.GlobalStateRepository
import com.fantasyidler.repository.GuildRepository
import com.fantasyidler.repository.MercenaryRepository
import com.fantasyidler.repository.MonumentRepository
import com.fantasyidler.repository.PlayerRepository
import com.fantasyidler.repository.QuestRepository
import com.fantasyidler.repository.QueuedSessionStarter
import com.fantasyidler.repository.SaveSlotRepository
import com.fantasyidler.repository.SeasonalEventRepository
import com.fantasyidler.repository.SessionRepository
import com.fantasyidler.repository.SlayerRepository
import com.fantasyidler.repository.TitleRepository
import com.fantasyidler.repository.TownRepository
import com.fantasyidler.repository.WeeklyQuestRepository
import com.fantasyidler.repository.WorkerQueuedSessionStarter
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Exercises Slayer settlement through the real Room repositories and reward collectors. */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [34])
class SlayerSessionIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var playerRepo: PlayerRepository
    private lateinit var sessionRepo: SessionRepository
    private lateinit var gameData: GameDataRepository
    private lateinit var queuedStarter: QueuedSessionStarter
    private lateinit var workerStarter: WorkerQueuedSessionStarter
    private lateinit var viewModel: HomeViewModel
    private lateinit var towerViewModel: TowerViewModel
    private lateinit var slayerRepo: SlayerRepository
    private val dragon = SlayerTask("dragon", 14, xpPerKill = 140, taskPoints = 14)
    private val demon = SlayerTask("demon", 20, xpPerKill = 110, taskPoints = 12)
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        gameData = GameDataRepository(context, json)
        val boostRepo = BoostRepository(gameData)
        val dailyRepo = DailyQuestRepository(gameData)
        val weeklyRepo = WeeklyQuestRepository(gameData)
        val buffScheduler = BuffNotificationScheduler(context)
        playerRepo = PlayerRepository(
            db.playerDao(),
            db.questProgressDao(),
            db.farmingPatchDao(),
            json,
            dailyRepo,
            weeklyRepo,
            buffScheduler,
            gameData,
            boostRepo,
            db,
        )
        sessionRepo = SessionRepository(
            db.skillSessionDao(),
            context,
            json,
            gameData,
            db.playerDao(),
            playerRepo,
        )
        val questRepo = QuestRepository(db.questProgressDao(), gameData)
        val townRepo = TownRepository(gameData, playerRepo, questRepo, boostRepo)
        val mercRepo = MercenaryRepository(playerRepo, gameData)
        val queuedStarter = QueuedSessionStarter(
            boostRepo, context, playerRepo, sessionRepo, townRepo, gameData, mercRepo, json,
        ).also { this.queuedStarter = it }
        val workerStarter = WorkerQueuedSessionStarter(
            boostRepo, playerRepo, sessionRepo, gameData, json,
        ).also { this.workerStarter = it }
        val guildRepo = GuildRepository(
            playerRepo, db.questProgressDao(), gameData, Provider { townRepo },
        )
        val slayerRepo = SlayerRepository(boostRepo, playerRepo, questRepo, gameData, guildRepo).also { this.slayerRepo = it }
        val monumentRepo = MonumentRepository(playerRepo, buffScheduler)
        val seasonalRepo = SeasonalEventRepository(playerRepo, gameData, dailyRepo, context)
        val titleRepo = TitleRepository(playerRepo, gameData, db.questProgressDao(), guildRepo)
        val globalStateRepo = GlobalStateRepository(db.globalStateDao())
        val farmingRepo = FarmingRepository(
            context, db, db.farmingPatchDao(), playerRepo, gameData,
            seasonalRepo, globalStateRepo, json, boostRepo,
        )
        val backupScheduler = BackupScheduler(context, sessionRepo, globalStateRepo)
        val saveSlotRepo = SaveSlotRepository(
            context, playerRepo, sessionRepo, questRepo, farmingRepo, guildRepo,
            globalStateRepo, queuedStarter, workerStarter, backupScheduler, buffScheduler, json,
        )
        viewModel = HomeViewModel(
            boostRepo, context, playerRepo, sessionRepo, gameData, questRepo, guildRepo,
            townRepo, queuedStarter, workerStarter, slayerRepo, monumentRepo, seasonalRepo,
            titleRepo, saveSlotRepo, json,
        )
        towerViewModel = TowerViewModel(boostRepo, context, playerRepo, sessionRepo, gameData,
            queuedStarter, questRepo, guildRepo, slayerRepo, townRepo, saveSlotRepo, json)
        runBlocking { playerRepo.getOrCreatePlayer() }
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        towerViewModel.viewModelScope.cancel()
        db.close()
    }

    /**
     * viewModelScope runs on Robolectric's Main, and @Test methods also run on Main:
     * blocking it with runBlocking wedges the dispatcher (issue #2013 repro lesson).
     * So the test body runs on a background thread while THIS (main) thread pumps
     * the main looper until the body finishes. future.get rethrows assertion errors.
     */
    private fun driveFromBackground(timeoutSec: Long = 120, block: suspend () -> Unit) {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit {
                runBlocking {
                    val observer = launch { viewModel.uiState.collect {} }
                    try { block() } finally { observer.cancel() }
                }
            }
            val main = Shadows.shadowOf(Looper.getMainLooper())
            val deadline = System.currentTimeMillis() + timeoutSec * 1000L
            while (!future.isDone) {
                main.idle()
                if (System.currentTimeMillis() > deadline) {
                    future.cancel(true)
                    throw AssertionError("timed out driving the main looper")
                }
                Thread.sleep(25)
            }
            future.get()
        } finally {
            executor.shutdownNow()
        }
    }

    /** viewModelScope runs on Main (pumped by [driveFromBackground]): idle it until [condition] holds. */
    private suspend fun awaitMainCondition(what: String, condition: suspend () -> Boolean) {
        withTimeout(30_000L) {
            while (!condition()) delay(50)
        }
    }

    private suspend fun assign(task: SlayerTask = dragon, foretold: List<SlayerTask> = emptyList()) {
        val now = System.currentTimeMillis()
        playerRepo.updateFlagsAtomically { it.copy(
            activeSlayerTask = task, foretelledTasks = foretold,
            weeklyQuestIds = listOf("weekly_slayer_novice"), weeklyQuestGeneratedAt = now,
            weeklyQuestNextResetAt = now + 86_400_000,
            guildDailyIds = listOf("slayer_daily_1a", "slayer_daily_1b"), guildDailyGeneratedAt = now,
            guildDailyNextResetAt = now + 86_400_000,
        ) }
    }

    private suspend fun insertCombat(
        id: String,
        kills: Map<String, Int>,
        kind: String = "combat",
        order: Int = 0,
        died: Boolean = false,
        elder: Boolean = false,
        levelAtStart: Int = 0,
    ) {
        val now = System.currentTimeMillis()
        // Two frames ensure the collector credits the whole session, not just one frame.
        val frames = (1..2).map { minute -> SessionFrame(
            minute = minute, xpGain = 0, xpBefore = 0, xpAfter = 0, levelBefore = 1, levelAfter = 1,
            killsByEnemy = kills.mapValues { (_, count) -> if (minute == 1) count / 2 else count - count / 2 },
            died = died && minute == 2,
        ) }
        sessionRepo.insertSession(SkillSession(
            sessionId = id, skillName = kind, startedAt = now - 7_200_000 + order * 100_000,
            endsAt = now - 3_600_000 + order * 100_000,
            frames = json.encodeToString(ListSerializer(SessionFrame.serializer()), frames), completed = true,
            activityKey = if (kind == "tower") "tower_floor_1" else if (elder) "abyssal_depths" else "dragon_lair",
            isElderSession = elder, levelAtStart = levelAtStart,
        ))
    }

    private suspend fun collectHome() {
        viewModel.collectSession()
        awaitMainCondition("home rewards collected") {
            sessionRepo.getAllCompletedSessions().isEmpty() && !viewModel.uiState.value.isCollecting
        }
    }

    private suspend fun xp() = playerRepo.getSkillXp()[Skills.SLAYER] ?: 0L
    private suspend fun progress(id: String) = db.questProgressDao().getQuestProgress(id)?.progress ?: 0

    @Test fun `full session XP persists while points and completion objectives advance once`() = driveFromBackground {
        assign()
        db.questProgressDao().upsert(QuestProgress("slayer_guild_1", 3, completed = true))
        playerRepo.updateFlagsAtomically { it.copy(guildDailyTierCounts = mapOf("slayer:0" to 2)) }
        insertCombat("overflow", mapOf("dragon" to 160, "demon" to 100))
        collectHome()
        assertEquals(22_400L, xp())
        val flags = playerRepo.getFlags()
        assertEquals(14, flags.slayerPoints)
        assertNull(flags.activeSlayerTask)
        assertEquals(1, progress("slayer_1"))
        assertEquals(160, progress("slayer_guild_2"))
        assertEquals(1, flags.weeklyQuestProgress["weekly_slayer_novice"])
        assertEquals(15, flags.guildDailyProgress["slayer_daily_1a"])
        assertEquals(1, flags.guildDailyProgress["slayer_daily_1b"])
        // Re-entering the public collector after successful collection cannot pay again.
        viewModel.collectSession()
        delay(100)
        assertEquals(22_400L, xp())
        assertEquals(14, playerRepo.getFlags().slayerPoints)
        assertEquals(1, progress("slayer_1"))
    }

    @Test fun `offline backlog equals individual collection and advances targets only between sessions`() = driveFromBackground {
        assign(foretold = listOf(demon))
        // Deliberately insert the newer session first: the DAO must supply chronological order.
        insertCombat("second", linkedMapOf("dragon" to 160, "demon" to 100), order = 1)
        insertCombat("first", linkedMapOf("demon" to 100, "dragon" to 160))
        collectHome()
        val batchXp = xp()
        val batchPoints = playerRepo.getFlags().slayerPoints
        assertEquals(33_400L, batchXp)
        assertEquals(26, batchPoints)
        assertEquals(2, progress("slayer_1"))
        assign(foretold = listOf(demon))
        insertCombat("individual1", linkedMapOf("demon" to 100, "dragon" to 160))
        collectHome()
        assertEquals(demon, playerRepo.getFlags().activeSlayerTask)
        insertCombat("individual2", linkedMapOf("dragon" to 160, "demon" to 100))
        collectHome()
        assertEquals(batchXp * 2, xp())
        assertEquals(batchPoints * 2, playerRepo.getFlags().slayerPoints)
    }

    @Test fun `task overflow does not leak into additional sessions without an assignment`() = driveFromBackground {
        assign()
        insertCombat("first", mapOf("dragon" to 160))
        insertCombat("second", mapOf("dragon" to 160), order = 1)
        collectHome()
        assertEquals(22_400L, xp())
        assertEquals(14, playerRepo.getFlags().slayerPoints)
        assertEquals(1, progress("slayer_1"))
    }

    @Test fun `partial saved task carries forward across sessions`() = driveFromBackground {
        // The pre-existing serialized task shape needs no new fields or save migration.
        val restored = json.decodeFromString<SlayerTask>("""{"enemy_key":"dragon","target_kills":14,"kills_completed":10,"xp_per_kill":140,"task_points":14}""")
        assign(restored)
        insertCombat("partial", mapOf("dragon" to 2))
        collectHome()
        assertEquals(280L, xp())
        assertEquals(12, playerRepo.getFlags().activeSlayerTask!!.killsCompleted)
        assertEquals(0, playerRepo.getFlags().slayerPoints)
        insertCombat("finish", mapOf("dragon" to 160))
        collectHome()
        assertEquals(22_680L, xp())
        assertEquals(14, playerRepo.getFlags().slayerPoints)
    }

    @Test fun `multi task awards overflow for all eligible species without duplicating XP`() = driveFromBackground {
        assign(foretold = listOf(demon, dragon))
        playerRepo.updateFlagsAtomically { it.copy(prestigeNodes = mapOf(Skills.SLAYER to listOf("slayer_foresight_4"))) }
        insertCombat("multi", mapOf("dragon" to 160, "demon" to 100))
        collectHome()
        assertEquals(33_400L, xp())
        assertEquals(40, playerRepo.getFlags().slayerPoints)
        assertNull(playerRepo.getFlags().activeSlayerTask)
        assertEquals(3, progress("slayer_1"))
    }

    @Test fun `home and tower screen award identical Slayer rewards including tower multiplier`() = driveFromBackground {
        playerRepo.updateFlagsAtomically { it.copy(towerXpBonusPct = 25) }
        assign(foretold = listOf(demon))
        insertCombat("homeTower", linkedMapOf("demon" to 100, "dragon" to 160), kind = "tower")
        collectHome()
        val firstXp = xp()
        assertEquals(28_000L, firstXp)
        assertEquals(demon, playerRepo.getFlags().activeSlayerTask)
        assign(foretold = listOf(demon))
        insertCombat("towerScreen", linkedMapOf("dragon" to 160, "demon" to 100), kind = "tower")
        towerViewModel.collectFloor()
        awaitMainCondition("tower rewards collected") { sessionRepo.getAllCompletedSessions().isEmpty() }
        assertEquals(firstXp * 2, xp())
        assertEquals(28, playerRepo.getFlags().slayerPoints)
        assertEquals(demon, playerRepo.getFlags().activeSlayerTask)
    }

    @Test fun `cape purchased boost and prestige apply once while ironman ignores paid boost and cape`() = driveFromBackground {
        playerRepo.addItem("slayer_cape", 1)
        playerRepo.updateEquipped(mapOf(EquipSlot.CAPE to "slayer_cape"))
        playerRepo.updateFlagsAtomically { it.copy(
            xpBoostExpiresAt = System.currentTimeMillis() + 86_400_000,
            skillPrestige = mapOf(Skills.SLAYER to 1),
            prestigeNodes = mapOf(Skills.SLAYER to listOf("slayer_bounty_1")),
        ) }
        assign()
        insertCombat("boosted", mapOf("dragon" to 160))
        collectHome()
        assertEquals(51_744L, xp()) // 22,400 * 1.1 cape * 1.05 prestige * 2 paid boost
        assertEquals(15, playerRepo.getFlags().slayerPoints)
        playerRepo.updateFlagsAtomically { it.copy(ironman = true) }
        assign()
        insertCombat("ironman", mapOf("dragon" to 160))
        collectHome()
        assertEquals(51_744L + 23_520L, xp()) // earned prestige remains active
        assertEquals(30, playerRepo.getFlags().slayerPoints)
    }

    @Test fun `prestige voids XP but retains task completion and guild kill progress`() = driveFromBackground {
        assign()
        insertCombat("voided", mapOf("dragon" to 160), levelAtStart = 999)
        collectHome()
        assertEquals(0L, xp())
        assertEquals(14, playerRepo.getFlags().slayerPoints)
        assertEquals(1, progress("slayer_1"))
    }

    @Test fun `dungeon death retains Slayer credit while tower death does not as before`() = driveFromBackground {
        assign()
        insertCombat("deadDungeon", mapOf("dragon" to 160), died = true)
        collectHome()
        assertEquals(22_400L, xp())
        assign()
        insertCombat("deadTower", mapOf("dragon" to 160), kind = "tower", died = true)
        collectHome()
        assertEquals(22_400L, xp())
        assertEquals(dragon, playerRepo.getFlags().activeSlayerTask)
        insertCombat("deadTowerScreen", mapOf("dragon" to 160), kind = "tower", died = true)
        towerViewModel.collectFloor()
        awaitMainCondition("tower death collected") { sessionRepo.getAllCompletedSessions().isEmpty() }
        assertEquals(22_400L, xp())
        assertEquals(dragon, playerRepo.getFlags().activeSlayerTask)
    }

    @Test fun `elder combat does not advance mainland Slayer`() = driveFromBackground {
        assign()
        insertCombat("elder", mapOf("dragon" to 160), elder = true)
        collectHome()
        assertEquals(0L, xp())
        assertEquals(0, playerRepo.getFlags().slayerPoints)
        assertEquals(dragon, playerRepo.getFlags().activeSlayerTask)
    }

    @Test fun `settlement waits for player lock and finishes without reentry deadlock`() = driveFromBackground {
        coroutineScope {
            assign()
            val acquired = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = launch { playerRepo.withLock { acquired.complete(Unit); release.await() } }
            try {
                withTimeout(3_000) { acquired.await() }
                assertNull(withTimeoutOrNull(100) { slayerRepo.recordSessionKills(mapOf("dragon" to 160)) })
            } finally {
                release.complete(Unit)
                holder.join()
            }
            assertEquals(dragon, playerRepo.getFlags().activeSlayerTask)
            assertEquals(22_400L, withTimeout(5_000) { slayerRepo.recordSessionKills(mapOf("dragon" to 160)) })
            assertEquals(14, playerRepo.getFlags().slayerPoints)
        }
    }
}
