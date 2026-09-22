package com.neuron.ai.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.neuron.ai.di.AppContainer
import com.neuron.ai.ui.chat.ChatScreen
import com.neuron.ai.ui.home.HomeScreen
import com.neuron.ai.ui.navigation.Routes
import com.neuron.ai.ui.settings.SettingsScreen

/**
 * Root UI shell. Owns the nav host; screens never navigate each other
 * directly — they receive lambdas, keeping previews and tests simple.
 */
@Composable
fun NeuronApp(container: AppContainer) {
    val navController = rememberNavController()

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
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
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
                onBack = { navController.popBackStack() }
            )
        }
    }
}
