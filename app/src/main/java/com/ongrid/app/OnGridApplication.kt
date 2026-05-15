package com.ongrid.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.room.Room
import com.ongrid.app.data.local.AppDatabase
import com.ongrid.app.data.model.ChatMessage
import com.ongrid.app.data.model.OllamaChatRequest
import com.ongrid.app.data.network.OllamaApi
import com.ongrid.app.data.network.McpApi
import com.ongrid.app.data.network.NetworkScanner
import com.ongrid.app.data.network.WebSearchApi
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ongrid.app.data.local.MIGRATION_3_4
import com.ongrid.app.data.local.MIGRATION_4_5
import com.ongrid.app.data.local.MIGRATION_5_6
import com.ongrid.app.data.local.MIGRATION_6_7
import com.ongrid.app.data.local.MIGRATION_7_8
import com.ongrid.app.data.local.MIGRATION_8_9
import com.ongrid.app.data.local.MIGRATION_10_11
import com.ongrid.app.data.local.MIGRATION_11_12
import com.ongrid.app.data.local.MIGRATION_12_13
import com.ongrid.app.data.local.MIGRATION_13_14
import com.ongrid.app.data.local.MIGRATION_14_15
import com.ongrid.app.data.local.MIGRATION_15_16
import com.ongrid.app.data.local.MIGRATION_9_10
import com.ongrid.app.data.repository.AgentConversationRepository
import com.ongrid.app.data.repository.AgentRepository
import com.ongrid.app.data.repository.ConversationRepository
import com.ongrid.app.data.repository.EmbeddingRepository
import com.ongrid.app.data.repository.FormMemoryRepository
import com.ongrid.app.data.repository.McpRepository
import com.ongrid.app.data.repository.OllamaRepository
import com.ongrid.app.data.repository.ServerRepository
import com.ongrid.app.data.repository.SettingsRepository
import com.ongrid.app.data.repository.SkillActivationRepository
import com.ongrid.app.data.repository.SkillRepository
import com.ongrid.app.data.repository.AgentRoomRepository
import com.ongrid.app.data.repository.DreamScheduleRepository
import com.ongrid.app.data.repository.UtilityAgentRepository
import com.ongrid.app.data.repository.WebFetchRepository
import com.ongrid.app.data.repository.WebSearchRepository
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Holds the parameters for a chat request that is handed off to [ChatForegroundService].
 * Stored in the Application so the service (same process) can read it without parcelling.
 */
data class PendingChatRequest(
    val assistantMsgId: String,
    val baseUrl: String,
    val request: OllamaChatRequest,
    /** Agent ID used to group notifications by conversation. */
    val agentId: String? = null,
    /** Agent display name for the MessagingStyle Person. */
    val agentName: String? = null,
    /** Current mood prepended as a tonal hint in the reply notification subtext. */
    val agentMood: String? = null,
    /** Conversation ID passed to built-in tools that write to the database (e.g. form_memory). */
    val conversationId: String? = null,
    /**
     * Skills available for the agent to activate via `use_skill`.  Keyed by skill name;
     * value is (skillId, skillContent).  Empty outside agent mode.
     */
    val availableSkillMap: Map<String, Pair<String, String>> = emptyMap(),
    /** Non-null when the user @mentioned another agent in this message. */
    val mentionedAgentId: String? = null,
    /** Display name matching [mentionedAgentId]. */
    val mentionedAgentName: String? = null
)

/**
 * Events emitted by [ChatForegroundService] and consumed by [ChatViewModel].
 *
 * Using a [Channel] (rather than a StateFlow/SharedFlow) gives us reliable FIFO delivery with
 * no replay concerns — the service is the sole producer and the ViewModel is the sole consumer.
 */
sealed class ChatServiceEvent {
    /** An incremental content update for a streaming assistant message. */
    data class Token(val msgId: String, val content: String) : ChatServiceEvent()
    /** Incremental update for the thinking/reasoning content preceding the assistant's answer. */
    data class ThinkingToken(val msgId: String, val thinking: String) : ChatServiceEvent()
    /** A complete [ChatMessage] that should be appended to the conversation (tool results and
     *  follow-up assistant placeholders created by the service during tool-call handling). */
    data class AppendMessage(val message: ChatMessage) : ChatServiceEvent()
    /** Clears the streaming cursor on an intermediate assistant message without ending the turn. */
    data class FinalizeMessage(val msgId: String, val content: String) : ChatServiceEvent()
    /** The entire conversational turn is finished (or failed). */
    data class TurnComplete(
        val msgId: String,
        val lastContent: String,
        val error: String? = null,
        val thinkingContent: String? = null
    ) : ChatServiceEvent()
    /** Token usage reported by the final streaming chunk (done = true). */
    data class TokenUsage(val promptTokens: Int, val generatedTokens: Int) : ChatServiceEvent()
    /** Emitted after a successful `form_memory` tool call so the ViewModel can reload memories. */
    data class MemoryFormed(val agentId: String) : ChatServiceEvent()
    /** Emitted after a successful `use_skill` tool call so the ViewModel can mark the skill active. */
    data class SkillActivated(val skillId: String) : ChatServiceEvent()
}

class OnGridApplication : Application() {

    private var startedActivityCount = 0

    /** True while at least one Activity is in the started (visible) state. */
    val isAppForegrounded: Boolean get() = startedActivityCount > 0

    override fun onCreate() {
        super.onCreate()
        // Schedule periodic Dream Cycle (every 6 hours, only when battery is not low)
        val dreamRequest = PeriodicWorkRequestBuilder<DreamWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "dream_cycle",
            ExistingPeriodicWorkPolicy.KEEP,
            dreamRequest
        )

        // Restore TIME_OF_DAY alarms after reboot / fresh install
        dreamScheduleManager.syncAll()
        agentRoomScheduleManager.syncAll()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { startedActivityCount++ }
            override fun onActivityStopped(a: Activity) { startedActivityCount-- }
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityResumed(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
    }

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val ollamaApi: OllamaApi by lazy { OllamaApi(httpClient) }
    val mcpApi: McpApi by lazy { McpApi(httpClient) }
    val networkScanner: NetworkScanner by lazy { NetworkScanner(httpClient) }
    val webSearchApi: WebSearchApi by lazy { WebSearchApi(httpClient) }
    val ollamaRepository: OllamaRepository by lazy { OllamaRepository(ollamaApi) }
    val mcpRepository: McpRepository by lazy { McpRepository(mcpApi, this) }
    val webSearchRepository: WebSearchRepository by lazy { WebSearchRepository(webSearchApi) }
    val webFetchRepository by lazy { WebFetchRepository(httpClient) }
    val formMemoryRepository: FormMemoryRepository by lazy { FormMemoryRepository(database.agentMemoryDao()) }
    val skillActivationRepository: SkillActivationRepository by lazy { SkillActivationRepository() }
    val agentConversationRepository: AgentConversationRepository by lazy { AgentConversationRepository() }

    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "ongrid.db")
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16)
            .fallbackToDestructiveMigration()
            .build()
    }
    val conversationRepository: ConversationRepository by lazy { ConversationRepository(database) }
    val serverRepository: ServerRepository by lazy { ServerRepository(database, this) }
    val skillRepository: SkillRepository by lazy { SkillRepository(database.skillDao()) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val utilityAgentRepository: UtilityAgentRepository by lazy { UtilityAgentRepository(ollamaApi) }
    val agentRepository: AgentRepository by lazy {
        AgentRepository(database.agentDao(), database.agentMemoryDao(), database.dreamLogDao())
    }
    val embeddingRepository: EmbeddingRepository by lazy {
        EmbeddingRepository(database.conversationEmbeddingDao(), ollamaApi)
    }
    val dreamScheduleRepository: DreamScheduleRepository by lazy {
        DreamScheduleRepository(database.dreamScheduleDao())
    }
    val dreamScheduleManager: DreamScheduleManager by lazy { DreamScheduleManager(this) }
    val agentShortcutManager: AgentShortcutManager by lazy { AgentShortcutManager(this) }
    val agentRoomRepository: AgentRoomRepository by lazy {
        AgentRoomRepository(database.agentRoomDao(), database.roomMemoryDao())
    }
    val agentRoomScheduleManager: AgentRoomScheduleManager by lazy { AgentRoomScheduleManager(this) }

    /** Set by [ChatViewModel] before starting [ChatForegroundService]; consumed by the service. */
    @Volatile var pendingChatRequest: PendingChatRequest? = null

    /** Set when the app is launched via ACTION_SEND; consumed by the share target flow. */
    @Volatile var pendingSharedContent: PendingSharedContent? = null

    /**
     * Events produced by [ChatForegroundService] and consumed by [ChatViewModel].
     * Unlimited capacity so the service never blocks even if the UI is momentarily paused.
     */
    val chatServiceChannel = Channel<ChatServiceEvent>(Channel.UNLIMITED)

    /**
     * Live terminal-feed lines emitted by [DreamWorker] and consumed by the UI overlay.
     * Rendezvous channel — lines are dropped if no collector is active (OK for a live feed).
     */
    val dreamLogChannel = kotlinx.coroutines.channels.Channel<String>(64)
}
