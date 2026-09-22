package com.neuron.ai.ui

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.neuron.ai.di.AppContainer
import com.neuron.ai.ui.chat.ChatScreen
import com.neuron.ai.ui.conversations.ConversationsScreen
import com.neuron.ai.ui.drawer.AppDrawer
import com.neuron.ai.ui.home.HomeScreen
import com.neuron.ai.ui.navigation.Routes
import com.neuron.ai.ui.permissions.PermissionDialogHost
import com.neuron.ai.ui.providers.ProvidersScreen
import com.neuron.ai.ui.providers.ProviderEditScreen
import com.neuron.ai.ui.settings.SettingsScreen
import com.neuron.ai.ui.tasks.TasksScreen
import kotlinx.coroutines.launch

/**
 * App shell: a hamburger drawer wraps the whole NavHost so every screen gets
 * the sidebar. The drawer's chat list always reflects the conversation repo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeuronApp(container: AppContainer) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val openDrawer: () -> Unit = {
        scope.launch { drawerState.open() }
    }
    val closeDrawerAnd: (Runnable) -> Unit = { action ->
        scope.launch {
            drawerState.close()
            action.run()
        }
    }

    val drawerContent: @Composable () -> Unit = {
        AppDrawer(
            container = container,
            onOpenChat = { conversationId ->
                closeDrawerAnd(Runnable { navController.navigate(Routes.chat(conversationId)) })
            },
            onNewChat = {
                closeDrawerAnd(Runnable { navController.navigate(Routes.HOME) })
            },
            onOpenSettings = {
                closeDrawerAnd(Runnable { navController.navigate(Routes.SETTINGS) })
            }
        )
    }

    val backStackEntry by navController.currentBackStackEntryAsState()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { drawerContent() }
    ) {
        PermissionDialogHost(container.permissionManager) {
            NavHost(
                navController = navController,
                startDestination = Routes.HOME
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        container = container,
                        onOpenMenu = openDrawer,
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
                        onOpenMenu = openDrawer,
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
}
