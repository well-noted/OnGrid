package com.ongrid.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ongrid.app.ui.screens.ChatScreen
import com.ongrid.app.ui.screens.DiscoveryScreen
import com.ongrid.app.ui.screens.McpServerScreen
import com.ongrid.app.viewmodel.ChatViewModel
import com.ongrid.app.viewmodel.DiscoveryViewModel
import com.ongrid.app.viewmodel.McpViewModel

object Routes {
    const val DISCOVERY = "discovery"
    const val CHAT = "chat/{serverHost}/{serverPort}/{modelName}"
    const val MCP_SERVERS = "mcp_servers"

    fun chatRoute(serverHost: String, serverPort: Int, modelName: String) =
        "chat/${serverHost}/${serverPort}/${java.net.URLEncoder.encode(modelName, java.nio.charset.StandardCharsets.UTF_8.name())}"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val chatViewModel: ChatViewModel = viewModel()
    val mcpViewModel: McpViewModel = viewModel()

    NavHost(navController = navController, startDestination = Routes.DISCOVERY) {

        composable(Routes.DISCOVERY) {
            val discoveryViewModel: DiscoveryViewModel = viewModel()
            DiscoveryScreen(
                viewModel = discoveryViewModel,
                onServerModelSelected = { server, modelName ->
                    chatViewModel.currentServer = server
                    chatViewModel.currentModel = modelName
                    chatViewModel.loadTools()
                    navController.navigate(
                        Routes.chatRoute(server.host, server.port, modelName)
                    )
                },
                onOpenMcpServers = {
                    navController.navigate(Routes.MCP_SERVERS)
                }
            )
        }

        composable(
            route = Routes.CHAT,
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

        composable(Routes.MCP_SERVERS) {
            McpServerScreen(
                viewModel = mcpViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
