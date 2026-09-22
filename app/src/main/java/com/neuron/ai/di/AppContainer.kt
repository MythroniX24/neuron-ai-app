package com.neuron.ai.di

import android.content.Context
import com.neuron.ai.core.conversation.ConversationRepository
import com.neuron.ai.core.coroutines.DefaultDispatcherProvider
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.log.AndroidLogger
import com.neuron.ai.core.log.Logger
import com.neuron.ai.core.security.SecureCredentialStore
import com.neuron.ai.core.security.SecureCredentialStoreFactory
import com.neuron.ai.core.settings.SettingsRepository
import com.neuron.ai.core.settings.SettingsRepositoryImpl
import com.neuron.ai.core.agent.ToolRegistry
import com.neuron.ai.data.conversation.InMemoryConversationRepository
import com.neuron.ai.data.tool.InMemoryToolRegistry

/**
 * Phase 0 dependency container. Hand-rolled on purpose: one explicit place
 * where every collaborator is created, zero reflection, trivially inspectable.
 * If the graph grows beyond this, migrate to Hilt without changing call sites.
 */
class AppContainer(context: Context) {

    val logger: Logger = AndroidLogger()

    val dispatchers: DispatcherProvider = DefaultDispatcherProvider()

    val secureCredentials: SecureCredentialStore =
        SecureCredentialStoreFactory.create(context, logger)

    val settingsRepository: SettingsRepository =
        SettingsRepositoryImpl(context, dispatchers)

    val conversationRepository: ConversationRepository =
        InMemoryConversationRepository()

    val toolRegistry: ToolRegistry = InMemoryToolRegistry()
}
