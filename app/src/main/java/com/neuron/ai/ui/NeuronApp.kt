package com.neuron.ai.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.neuron.ai.di.AppContainer
import com.neuron.ai.ui.chat.ChatScreen
import com.neuron.ai.ui.conversations.ConversationsScreen
import com.neuron.ai.ui.home.HomeScreen
import com.neuron.ai.ui.navigation.Routes
import com.neuron.ai.ui.permissions.PermissionDialogHost
import com.neuron.ai.ui.providers.ProvidersScreen
import com.neuron.ai.ui.providers.ProviderEditScreen
import com.neuron.ai.ui.settings.SettingsScreen
import com.neuron.ai.ui.tasks.TasksScreen

@Composable
fun NeuronApp(container: AppContainer) {
    val navController = rememberNavController()

    PermissionDialogHost(container.permissionManager) {
        NavHost(
            navController = navController,
            startDestination = Routes.HOME
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    container = container,
                    onOpenChat = { conversationId ->
                        navController.navigate(Routes.chat(conversationId))
                    },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenConversations = { navController.navigate(Routes.CONVERSATIONS) }
                )
            }

            composable(Routes.CHAT) { entry ->
                val conversationId =
                    entry.arguments?.getString("conversationId").orEmpty()
                ChatScreen(
                    container = container,
                    conversationId = conversationId,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                    onOpenProviders = { navController.navigate(Routes.PROVIDERS) },
                    onOpenConversations = { navController.navigate(Routes.CONVERSATIONS) },
                    onOpenTasks = { navController.navigate(Routes.TASKS) }
                )
            }

            composable(Routes.CONVERSATIONS) {
                ConversationsScreen(
                    container = container,
                    onOpenChat = { navController.navigate(Routes.chat(it)) },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Routes.PROVIDERS) {
                ProvidersScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                    onEditProvider = { navController.navigate(Routes.providerEdit(it)) },
                    onAddProvider = { navController.navigate(Routes.providerEdit(null)) }
                )
            }

            composable(
                route = Routes.PROVIDER_EDIT,
                arguments = listOf(navArgument("providerId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                })
            ) { entry ->
                ProviderEditScreen(
                    container = container,
                    providerId = entry.arguments?.getString("providerId"),
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Routes.TASKS) {
                TasksScreen(
                    container = container,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
