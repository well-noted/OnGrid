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
import com.ongrid.app.data.local.MIGRATION_3_4
import com.ongrid.app.data.repository.ConversationRepository
import com.ongrid.app.data.repository.McpRepository
import com.ongrid.app.data.repository.OllamaRepository
import com.ongrid.app.data.repository.ServerRepository
import com.ongrid.app.data.repository.SkillRepository
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
    val request: OllamaChatRequest
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
}

class OnGridApplication : Application() {

    private var startedActivityCount = 0

    /** True while at least one Activity is in the started (visible) state. */
    val isAppForegrounded: Boolean get() = startedActivityCount > 0

    override fun onCreate() {
        super.onCreate()
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

    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "ongrid.db")
            .addMigrations(MIGRATION_3_4)
            .fallbackToDestructiveMigration()
            .build()
    }
    val conversationRepository: ConversationRepository by lazy { ConversationRepository(database) }
    val serverRepository: ServerRepository by lazy { ServerRepository(database, this) }
    val skillRepository: SkillRepository by lazy { SkillRepository(database.skillDao()) }

    /** Set by [ChatViewModel] before starting [ChatForegroundService]; consumed by the service. */
    @Volatile var pendingChatRequest: PendingChatRequest? = null

    /**
     * Events produced by [ChatForegroundService] and consumed by [ChatViewModel].
     * Unlimited capacity so the service never blocks even if the UI is momentarily paused.
     */
    val chatServiceChannel = Channel<ChatServiceEvent>(Channel.UNLIMITED)
}
