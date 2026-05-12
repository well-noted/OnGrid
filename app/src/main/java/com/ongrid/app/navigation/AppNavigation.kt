package com.ongrid.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ongrid.app.ui.screens.ChatScreen
import com.ongrid.app.ui.screens.ConversationListScreen
import com.ongrid.app.ui.screens.DiscoveryScreen
import com.ongrid.app.ui.screens.McpServerScreen
import com.ongrid.app.ui.screens.SettingsScreen
import com.ongrid.app.viewmodel.ChatViewModel
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

    fun discoveryRoute(autoScan: Boolean = false) = "discovery?autoScan=$autoScan"
    fun chatNewRoute(serverHost: String, serverPort: Int, modelName: String) =
        "chat/new/${serverHost}/${serverPort}/${java.net.URLEncoder.encode(modelName, "UTF-8")}"
    fun chatExistingRoute(conversationId: String) = "chat/existing/$conversationId"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val chatViewModel: ChatViewModel = viewModel()
    val mcpViewModel: McpViewModel = viewModel()
    val conversationListViewModel: ConversationListViewModel = viewModel()

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
    }
}

