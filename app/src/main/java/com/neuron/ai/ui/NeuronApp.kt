package com.neuron.ai.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
                closeDrawerAnd(Runnable {
                    // Drop the old HOME entry (and anything above it) so a new
                    // chat starts fresh — no stacked screens, a brand-new
                    // conversation id each time.
                    navController.popBackStack(Routes.HOME, inclusive = true)
                    navController.navigate(Routes.HOME) { launchSingleTop = true }
                })
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
                startDestination = Routes.HOME,
                enterTransition = {
                    fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 10 }
                },
                exitTransition = { fadeOut(tween(160)) },
                popEnterTransition = { fadeIn(tween(220)) },
                popExitTransition = {
                    fadeOut(tween(160)) + slideOutHorizontally(tween(260)) { it / 10 }
                }
            ) {
                composable(Routes.HOME) {
                    // Unified chat surface: "Home" IS the chat screen. A fresh
                    // conversation id is minted per visit; the ViewModel lazily
                    // creates the backing conversation with THIS id on first
                    // send or capability change. One screen — the composer (+
                    // icon included) is identical from the very first frame.
                    val newChatId = remember { java.util.UUID.randomUUID().toString() }
                    ChatScreen(
                        container = container,
                        conversationId = newChatId,
                        onOpenMenu = openDrawer,
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
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        onOpenConversations = { navController.navigate(Routes.CONVERSATIONS) }
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
