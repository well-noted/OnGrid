package com.ongrid.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ongrid.app.OnGridApplication
import com.ongrid.app.ui.screens.ChatScreen
import com.ongrid.app.ui.screens.ConversationListScreen
import com.ongrid.app.ui.screens.AgentScreen
import com.ongrid.app.ui.screens.AllAgentsScreen
import com.ongrid.app.ui.screens.DiscoveryScreen
import com.ongrid.app.ui.screens.McpServerScreen
import com.ongrid.app.ui.screens.ProjectDetailScreen
import com.ongrid.app.ui.screens.SettingsScreen
import com.ongrid.app.ui.screens.ShareTargetScreen
import com.ongrid.app.viewmodel.ChatViewModel
import com.ongrid.app.viewmodel.AgentViewModel
import com.ongrid.app.viewmodel.ConversationListViewModel
import com.ongrid.app.viewmodel.DiscoveryViewModel
import com.ongrid.app.viewmodel.McpViewModel
import com.ongrid.app.viewmodel.ServerSetupState

object Routes {
    const val CONVERSATIONS = "conversations"
    const val DISCOVERY = "discovery?autoScan={autoScan}"
    const val CHAT_NEW = "chat/new/{serverHost}/{serverPort}/{modelName}"
    const val CHAT_EXISTING = "chat/existing/{conversationId}"
    const val MCP_SERVERS = "mcp_servers"
    const val SETTINGS = "settings"
    const val PROJECT_DETAIL = "project/{projectId}"
    const val AGENT_DETAIL = "agent/{agentId}"
    const val ALL_AGENTS = "all_agents"

    fun discoveryRoute(autoScan: Boolean = false) = "discovery?autoScan=$autoScan"
    fun chatNewRoute(serverHost: String, serverPort: Int, modelName: String) =
        "chat/new/${serverHost}/${serverPort}/${java.net.URLEncoder.encode(modelName, "UTF-8")}"
    fun chatExistingRoute(conversationId: String) = "chat/existing/$conversationId"
    fun projectDetailRoute(projectId: String) = "project/$projectId"
    fun agentDetailRoute(agentId: String) = "agent/$agentId"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val chatViewModel: ChatViewModel = viewModel()
    val mcpViewModel: McpViewModel = viewModel()
    val conversationListViewModel: ConversationListViewModel = viewModel()
    val agentViewModel: AgentViewModel = viewModel()

    // Share target handling
    val context = LocalContext.current
    val app = context.applicationContext as OnGridApplication
    var pendingShare by remember { mutableStateOf(app.pendingSharedContent) }
    val activeAgents by agentViewModel.activeAgents.collectAsState()

    // Show share target sheet when there's pending content without a targetAgentId
    if (pendingShare != null && pendingShare?.targetAgentId == null) {
        ShareTargetScreen(
            sharedContent = pendingShare!!,
            activeAgents = activeAgents,
            onSelectAgent = { agentId, prefillText ->
                app.pendingSharedContent = null
                pendingShare = null
                chatViewModel.setPrefillText(prefillText)
                val state = conversationListViewModel.serverSetupState.value
                if (state is ServerSetupState.Ready) {
                    val last = state.lastUsed
                    if (last.serverHost != null && last.modelName != null) {
                        val server = com.ongrid.app.data.model.OllamaServer(
                            host = last.serverHost,
                            port = last.serverPort
                        )
                        chatViewModel.initNewConversation(server, last.modelName)
                        chatViewModel.loadTools()
                        chatViewModel.setAgent(agentId)
                        navController.navigate(Routes.chatNewRoute(server.host, server.port, last.modelName))
                    }
                }
            },
            onNewConversation = { prefillText ->
                app.pendingSharedContent = null
                pendingShare = null
                chatViewModel.setPrefillText(prefillText)
                val state = conversationListViewModel.serverSetupState.value
                if (state is ServerSetupState.Ready) {
                    val last = state.lastUsed
                    if (last.serverHost != null && last.modelName != null) {
                        val server = com.ongrid.app.data.model.OllamaServer(
                            host = last.serverHost,
                            port = last.serverPort
                        )
                        chatViewModel.initNewConversation(server, last.modelName)
                        chatViewModel.loadTools()
                        navController.navigate(Routes.chatNewRoute(server.host, server.port, last.modelName))
                    }
                }
            },
            onDismiss = {
                app.pendingSharedContent = null
                pendingShare = null
            }
        )
    }

    // Handle direct-agent share (from shortcut with targetAgentId)
    LaunchedEffect(Unit) {
        val direct = app.pendingSharedContent
        if (direct?.targetAgentId != null) {
            app.pendingSharedContent = null
            pendingShare = null
            val state = conversationListViewModel.serverSetupState.value
            if (state is ServerSetupState.Ready) {
                val last = state.lastUsed
                if (last.serverHost != null && last.modelName != null) {
                    val server = com.ongrid.app.data.model.OllamaServer(
                        host = last.serverHost,
                        port = last.serverPort
                    )
                    chatViewModel.initNewConversation(server, last.modelName)
                    chatViewModel.loadTools()
                    chatViewModel.setAgent(direct.targetAgentId)
                    chatViewModel.setPrefillText(buildDirectSharePrefill(direct))
                    navController.navigate(
                        Routes.chatNewRoute(server.host, server.port, last.modelName)
                    )
                }
            }
        }
    }

    NavHost(navController = navController, startDestination = Routes.CONVERSATIONS) {

        composable(Routes.CONVERSATIONS) {
            val serverSetupState by conversationListViewModel.serverSetupState.collectAsState()

            // On first launch (no saved servers) redirect to Discovery and auto-scan.
            LaunchedEffect(serverSetupState) {
                if (serverSetupState is ServerSetupState.NoServers) {
                    navController.navigate(Routes.discoveryRoute(autoScan = true)) {
                        popUpTo(Routes.CONVERSATIONS) { inclusive = false }
                    }
                }
            }

            when (serverSetupState) {
                ServerSetupState.Loading, ServerSetupState.NoServers -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is ServerSetupState.Ready -> {
                    ConversationListScreen(
                        viewModel = conversationListViewModel,
                        onOpenConversation = { conversationId ->
                            chatViewModel.resumeConversation(conversationId)
                            navController.navigate(Routes.chatExistingRoute(conversationId))
                        },
                        onNewChat = { server, modelName ->
                            chatViewModel.initNewConversation(server, modelName)
                            chatViewModel.loadTools()
                            navController.navigate(
                                Routes.chatNewRoute(server.host, server.port, modelName)
                            )
                        },
                        onManageServers = {
                            navController.navigate(Routes.discoveryRoute(autoScan = false))
                        },
                        onOpenSettings = {
                            navController.navigate(Routes.SETTINGS)
                        },
                        onOpenProject = { projectId ->
                            navController.navigate(Routes.projectDetailRoute(projectId))
                        },
                        agentViewModel = agentViewModel,
                        onOpenAgent = { agentId ->
                            navController.navigate(Routes.agentDetailRoute(agentId))
                        },
                        onShowAllAgents = {
                            navController.navigate(Routes.ALL_AGENTS)
                        }
                    )
                }
            }
        }

        composable(
            route = Routes.DISCOVERY,
            arguments = listOf(
                navArgument("autoScan") { type = NavType.BoolType; defaultValue = false }
            )
        ) { backStack ->
            val autoScan = backStack.arguments?.getBoolean("autoScan") ?: false
            val discoveryViewModel: DiscoveryViewModel = viewModel()

            LaunchedEffect(autoScan) {
                if (autoScan) discoveryViewModel.startScan()
            }

            DiscoveryScreen(
                viewModel = discoveryViewModel,
                onNavigateBack = { navController.popBackStack() },
                onOpenMcpServers = { navController.navigate(Routes.MCP_SERVERS) }
            )
        }

        composable(
            route = Routes.CHAT_NEW,
            arguments = listOf(
                navArgument("serverHost") { type = NavType.StringType },
                navArgument("serverPort") { type = NavType.IntType },
                navArgument("modelName") { type = NavType.StringType }
            )
        ) {
            ChatScreen(
                viewModel = chatViewModel,
                onNavigateBack = { navController.popBackStack() },
                onOpenMcpServers = { navController.navigate(Routes.MCP_SERVERS) }
            )
        }

        composable(
            route = Routes.CHAT_EXISTING,
            arguments = listOf(
                navArgument("conversationId") { type = NavType.StringType }
            )
        ) {
            ChatScreen(
                viewModel = chatViewModel,
                onNavigateBack = { navController.popBackStack() },
                onOpenMcpServers = { navController.navigate(Routes.MCP_SERVERS) }
            )
        }

        composable(Routes.MCP_SERVERS) {
            McpServerScreen(
                viewModel = mcpViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.PROJECT_DETAIL,
            arguments = listOf(
                navArgument("projectId") { type = NavType.StringType }
            )
        ) { backStack ->
            val projectId = backStack.arguments?.getString("projectId") ?: return@composable
            ProjectDetailScreen(
                projectId = projectId,
                viewModel = conversationListViewModel,
                onNavigateBack = { navController.popBackStack() },
                onOpenConversation = { conversationId ->
                    conversationListViewModel.selectProject(null)
                    chatViewModel.resumeConversation(conversationId)
                    navController.navigate(Routes.chatExistingRoute(conversationId))
                },
                onNewChat = { server, modelName ->
                    chatViewModel.initNewConversation(server, modelName)
                    chatViewModel.loadTools()
                    chatViewModel.setProjectForConversation(projectId)
                    navController.navigate(Routes.chatNewRoute(server.host, server.port, modelName))
                }
            )
        }

        composable(
            route = Routes.AGENT_DETAIL,
            arguments = listOf(
                navArgument("agentId") { type = NavType.StringType }
            )
        ) { backStack ->
            val agentId = backStack.arguments?.getString("agentId") ?: return@composable
            val allSkills by agentViewModel.allSkills.collectAsState()
            AgentScreen(
                agentId = agentId,
                viewModel = agentViewModel,
                availableSkills = allSkills,
                availableToolNames = emptyList(),
                onNavigateBack = { navController.popBackStack() },
                onTalkToAgent = { aId, server, modelName ->
                    chatViewModel.initNewConversation(server, modelName)
                    chatViewModel.loadTools()
                    chatViewModel.setAgent(aId)
                    navController.navigate(Routes.chatNewRoute(server.host, server.port, modelName))
                },
                onOpenConversation = { conversationId ->
                    chatViewModel.resumeConversation(conversationId)
                    navController.navigate(Routes.chatExistingRoute(conversationId))
                }
            )
        }

        composable(Routes.ALL_AGENTS) {
            AllAgentsScreen(
                viewModel = agentViewModel,
                onNavigateBack = { navController.popBackStack() },
                onOpenAgent = { agentId ->
                    navController.navigate(Routes.agentDetailRoute(agentId))
                },
                onCreateAgent = {
                    // Navigate back to conversations where agent rail has the create sheet
                    navController.popBackStack()
                }
            )
        }
    }
}

private fun buildDirectSharePrefill(content: com.ongrid.app.PendingSharedContent): String {
    val text = content.text
    val isUrl = text.startsWith("http://") || text.startsWith("https://")
    return if (isUrl) "I'd like to discuss this: $text" else text
}

