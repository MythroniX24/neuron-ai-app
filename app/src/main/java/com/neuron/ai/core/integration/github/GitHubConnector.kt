package com.neuron.ai.core.integration.github

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * A GitHub account linked to this app via Personal Access Token.
 */
@Serializable
data class GitHubAccount(
    val login: String,
    val name: String? = null,
    val avatarUrl: String? = null,
    val htmlUrl: String? = null,
    val publicRepos: Int = 0,
    val connectedAt: Long = System.currentTimeMillis()
)

/**
 * Result of requesting a device code from GitHub OAuth Device Authorization Grant.
 */
@Serializable
data class GitHubDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresIn: Int = 900,
    val interval: Int = 5
)

/**
 * Interface for GitHub API interactions and token lifecycle.
 */
interface GitHubRepository {
    val account: StateFlow<GitHubAccount?>
    val isConnected: Boolean get() = account.value != null

    suspend fun connect(token: String): Result<GitHubAccount>
    suspend fun disconnect(): Result<Unit>
    suspend fun getAccount(): GitHubAccount?
    suspend fun execute(endpoint: String, method: String = "GET", bodyJson: String? = null): Result<String>

    suspend fun startDeviceFlow(): Result<GitHubDeviceCode>
    suspend fun pollDeviceFlow(deviceCode: String, intervalSeconds: Int): Result<GitHubAccount>
}
