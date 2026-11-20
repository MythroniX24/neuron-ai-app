package com.neuron.ai.data.tool

import com.neuron.ai.core.agent.RiskLevel
import com.neuron.ai.core.agent.Tool
import com.neuron.ai.core.agent.ToolResult
import com.neuron.ai.core.integration.github.GitHubRepository
import com.neuron.ai.core.permissions.Capability
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * GitHub connector tools for the agent: repository search, README reading,
 * repository metadata, issue listing/creation, pull request inspection, and file reads.
 */
object GitHubConnectorTools {

    private val json = Json { ignoreUnknownKeys = true }

    fun all(repository: GitHubRepository): List<Tool> = listOf(
        UserGetTool(repository),
        RepoSearchTool(repository),
        RepoGetTool(repository),
        RepoReadmeTool(repository),
        IssuesListTool(repository),
        IssuesCreateTool(repository),
        PullsListTool(repository),
        FileGetTool(repository)
    )

    private fun arg(argumentsJson: String, key: String): String? = runCatching {
        json.parseToJsonElement(argumentsJson).jsonObject[key]?.jsonPrimitive?.content
    }.getOrNull()

    private fun intArg(argumentsJson: String, key: String): Int? = runCatching {
        json.parseToJsonElement(argumentsJson).jsonObject[key]?.jsonPrimitive?.intOrNull
    }.getOrNull()

    // 1. User profile
    private class UserGetTool(private val repo: GitHubRepository) : Tool {
        override val id = "github.user.get"
        override val title = "Viewing GitHub profile"
        override val description = "Gets profile info for the authenticated GitHub user (login, name, public repos, bio, html link)."
        override val requiredCapabilities = setOf(Capability.GITHUB)
        override val riskLevel = RiskLevel.SAFE
        override val timeoutMs = 20_000L
        override val parametersSchemaJson = """{"type":"object","properties":{}}"""

        override suspend fun execute(argumentsJson: String): ToolResult {
            val result = repo.execute("user")
            return result.fold(
                onSuccess = { ToolResult.Success(it) },
                onFailure = { ToolResult.Failure(it.message ?: "Failed to get user profile") }
            )
        }
    }

    // 2. Search repos
    private class RepoSearchTool(private val repo: GitHubRepository) : Tool {
        override val id = "github.repo.search"
        override val title = "Searching GitHub repositories"
        override val description = "Searches repositories on GitHub by query (keywords, language, topic) with optional sorting."
        override val requiredCapabilities = setOf(Capability.GITHUB)
        override val riskLevel = RiskLevel.SAFE
        override val timeoutMs = 30_000L
        override val parametersSchemaJson = """
            {
              "type": "object",
              "properties": {
                "query": { "type": "string", "description": "Search query, e.g. 'compose multiplatform stars:>100'" },
                "sort": { "type": "string", "enum": ["stars", "forks", "updated"] },
                "maxResults": { "type": "integer" }
              },
              "required": ["query"]
            }
        """.trimIndent()

        override suspend fun execute(argumentsJson: String): ToolResult {
            val query = arg(argumentsJson, "query") ?: return ToolResult.Failure("Missing query")
            val sort = arg(argumentsJson, "sort")
            val maxResults = intArg(argumentsJson, "maxResults")?.coerceIn(1, 20) ?: 5

            val encodedQ = URLEncoder.encode(query, "UTF-8")
            val endpoint = buildString {
                append("search/repositories?q=").append(encodedQ)
                if (!sort.isNullOrBlank()) append("&sort=").append(sort)
                append("&per_page=").append(maxResults)
            }

            return repo.execute(endpoint).fold(
                onSuccess = { body ->
                    val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                    val items = root?.get("items")?.jsonArray
                    if (items.isNullOrEmpty()) return@fold ToolResult.Success("No repositories found for '$query'.")

                    val sb = StringBuilder()
                    sb.append("FOUND ${items.size} REPOSITORIES:\n")
                    items.forEachIndexed { idx, itm ->
                        val obj = itm.jsonObject
                        val fullName = obj["full_name"]?.jsonPrimitive?.content ?: "Unknown"
                        val desc = obj["description"]?.jsonPrimitive?.content ?: "No description"
                        val stars = obj["stargazers_count"]?.jsonPrimitive?.content ?: "0"
                        val forks = obj["forks_count"]?.jsonPrimitive?.content ?: "0"
                        val url = obj["html_url"]?.jsonPrimitive?.content ?: ""
                        sb.append("[${idx + 1}] $fullName (★ $stars, ⑂ $forks)\n    $url\n    $desc\n")
                    }
                    ToolResult.Success(sb.toString())
                },
                onFailure = { ToolResult.Failure(it.message ?: "Failed to search repositories") }
            )
        }
    }

    // 3. Get repo
    private class RepoGetTool(private val repo: GitHubRepository) : Tool {
        override val id = "github.repo.get"
        override val title = "Getting repository info"
        override val description = "Fetches detailed metadata about a GitHub repository (description, stars, forks, default branch, open issues)."
        override val requiredCapabilities = setOf(Capability.GITHUB)
        override val riskLevel = RiskLevel.SAFE
        override val timeoutMs = 20_000L
        override val parametersSchemaJson = """
            {
              "type": "object",
              "properties": {
                "owner": { "type": "string" },
                "repo": { "type": "string" }
              },
              "required": ["owner", "repo"]
            }
        """.trimIndent()

        override suspend fun execute(argumentsJson: String): ToolResult {
            val owner = arg(argumentsJson, "owner") ?: return ToolResult.Failure("Missing owner")
            val repoName = arg(argumentsJson, "repo") ?: return ToolResult.Failure("Missing repo")

            return repo.execute("repos/$owner/$repoName").fold(
                onSuccess = { body ->
                    val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                        ?: return@fold ToolResult.Success(body)
                    val fullName = obj["full_name"]?.jsonPrimitive?.content ?: "$owner/$repoName"
                    val desc = obj["description"]?.jsonPrimitive?.content ?: ""
                    val stars = obj["stargazers_count"]?.jsonPrimitive?.content ?: "0"
                    val forks = obj["forks_count"]?.jsonPrimitive?.content ?: "0"
                    val issues = obj["open_issues_count"]?.jsonPrimitive?.content ?: "0"
                    val branch = obj["default_branch"]?.jsonPrimitive?.content ?: "main"
                    val url = obj["html_url"]?.jsonPrimitive?.content ?: ""
                    ToolResult.Success(
                        "REPOSITORY: $fullName\nURL: $url\nDescription: $desc\nDefault Branch: $branch\nStars: $stars | Forks: $forks | Open Issues: $issues"
                    )
                },
                onFailure = { ToolResult.Failure(it.message ?: "Failed to fetch repository") }
            )
        }
    }

    // 4. Repo README
    private class RepoReadmeTool(private val repo: GitHubRepository) : Tool {
        override val id = "github.repo.readme"
        override val title = "Reading repository README"
        override val description = "Fetches and reads the README file of any GitHub repository (clean markdown format)."
        override val requiredCapabilities = setOf(Capability.GITHUB)
        override val riskLevel = RiskLevel.SAFE
        override val timeoutMs = 30_000L
        override val parametersSchemaJson = """
            {
              "type": "object",
              "properties": {
                "owner": { "type": "string" },
                "repo": { "type": "string" }
              },
              "required": ["owner", "repo"]
            }
        """.trimIndent()

        override suspend fun execute(argumentsJson: String): ToolResult {
            val owner = arg(argumentsJson, "owner") ?: return ToolResult.Failure("Missing owner")
            val repoName = arg(argumentsJson, "repo") ?: return ToolResult.Failure("Missing repo")

            return repo.execute("repos/$owner/$repoName/readme").fold(
                onSuccess = { body ->
                    val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                    val downloadUrl = obj?.get("download_url")?.jsonPrimitive?.content
                    if (!downloadUrl.isNullOrBlank()) {
                        val contentResult = repo.execute(downloadUrl)
                        contentResult.fold(
                            onSuccess = { rawContent ->
                                ToolResult.Success("README for $owner/$repoName:\n\n${rawContent.take(12_000)}")
                            },
                            onFailure = { ToolResult.Failure(it.message ?: "Failed to download README") }
                        )
                    } else {
                        ToolResult.Success(body.take(12_000))
                    }
                },
                onFailure = { ToolResult.Failure(it.message ?: "Failed to fetch README") }
            )
        }
    }

    // 5. Issues list
    private class IssuesListTool(private val repo: GitHubRepository) : Tool {
        override val id = "github.issues.list"
        override val title = "Listing repository issues"
        override val description = "Lists issues on a repository with title, number, author, labels, and state."
        override val requiredCapabilities = setOf(Capability.GITHUB)
        override val riskLevel = RiskLevel.SAFE
        override val timeoutMs = 25_000L
        override val parametersSchemaJson = """
            {
              "type": "object",
              "properties": {
                "owner": { "type": "string" },
                "repo": { "type": "string" },
                "state": { "type": "string", "enum": ["open", "closed", "all"] },
                "maxResults": { "type": "integer" }
              },
              "required": ["owner", "repo"]
            }
        """.trimIndent()

        override suspend fun execute(argumentsJson: String): ToolResult {
            val owner = arg(argumentsJson, "owner") ?: return ToolResult.Failure("Missing owner")
            val repoName = arg(argumentsJson, "repo") ?: return ToolResult.Failure("Missing repo")
            val state = arg(argumentsJson, "state") ?: "open"
            val maxResults = intArg(argumentsJson, "maxResults")?.coerceIn(1, 30) ?: 10

            return repo.execute("repos/$owner/$repoName/issues?state=$state&per_page=$maxResults").fold(
                onSuccess = { body ->
                    val array = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull()
                    if (array.isNullOrEmpty()) return@fold ToolResult.Success("No issues found for $owner/$repoName ($state).")

                    val sb = StringBuilder()
                    sb.append("ISSUES FOR $owner/$repoName ($state):\n")
                    array.forEach { item ->
                        val obj = item.jsonObject
                        val number = obj["number"]?.jsonPrimitive?.content ?: "?"
                        val title = obj["title"]?.jsonPrimitive?.content ?: "Untitled"
                        val user = obj["user"]?.jsonObject?.get("login")?.jsonPrimitive?.content ?: "unknown"
                        val issueState = obj["state"]?.jsonPrimitive?.content ?: ""
                        val htmlUrl = obj["html_url"]?.jsonPrimitive?.content ?: ""
                        sb.append("#$number [$issueState] $title by @$user\n  $htmlUrl\n")
                    }
                    ToolResult.Success(sb.toString())
                },
                onFailure = { ToolResult.Failure(it.message ?: "Failed to list issues") }
            )
        }
    }

    // 6. Issues create
    private class IssuesCreateTool(private val repo: GitHubRepository) : Tool {
        override val id = "github.issues.create"
        override val title = "Creating GitHub issue"
        override val description = "Creates a new issue in a GitHub repository."
        override val requiredCapabilities = setOf(Capability.GITHUB)
        override val riskLevel = RiskLevel.ELEVATED
        override val timeoutMs = 25_000L
        override val parametersSchemaJson = """
            {
              "type": "object",
              "properties": {
                "owner": { "type": "string" },
                "repo": { "type": "string" },
                "title": { "type": "string" },
                "body": { "type": "string" }
              },
              "required": ["owner", "repo", "title"]
            }
        """.trimIndent()

        override suspend fun execute(argumentsJson: String): ToolResult {
            val owner = arg(argumentsJson, "owner") ?: return ToolResult.Failure("Missing owner")
            val repoName = arg(argumentsJson, "repo") ?: return ToolResult.Failure("Missing repo")
            val title = arg(argumentsJson, "title") ?: return ToolResult.Failure("Missing title")
            val body = arg(argumentsJson, "body").orEmpty()

            val payload = JsonObject(
                mapOf(
                    "title" to JsonPrimitive(title),
                    "body" to JsonPrimitive(body)
                )
            ).toString()

            return repo.execute("repos/$owner/$repoName/issues", method = "POST", bodyJson = payload).fold(
                onSuccess = { res ->
                    val obj = runCatching { json.parseToJsonElement(res).jsonObject }.getOrNull()
                    val number = obj?.get("number")?.jsonPrimitive?.content ?: ""
                    val url = obj?.get("html_url")?.jsonPrimitive?.content ?: ""
                    ToolResult.Success("Issue #$number created successfully:\n$url")
                },
                onFailure = { ToolResult.Failure(it.message ?: "Failed to create issue") }
            )
        }
    }

    // 7. Pull requests list
    private class PullsListTool(private val repo: GitHubRepository) : Tool {
        override val id = "github.pulls.list"
        override val title = "Listing Pull Requests"
        override val description = "Lists pull requests on a repository with title, number, author, and branch."
        override val requiredCapabilities = setOf(Capability.GITHUB)
        override val riskLevel = RiskLevel.SAFE
        override val timeoutMs = 25_000L
        override val parametersSchemaJson = """
            {
              "type": "object",
              "properties": {
                "owner": { "type": "string" },
                "repo": { "type": "string" },
                "state": { "type": "string", "enum": ["open", "closed", "all"] },
                "maxResults": { "type": "integer" }
              },
              "required": ["owner", "repo"]
            }
        """.trimIndent()

        override suspend fun execute(argumentsJson: String): ToolResult {
            val owner = arg(argumentsJson, "owner") ?: return ToolResult.Failure("Missing owner")
            val repoName = arg(argumentsJson, "repo") ?: return ToolResult.Failure("Missing repo")
            val state = arg(argumentsJson, "state") ?: "open"
            val maxResults = intArg(argumentsJson, "maxResults")?.coerceIn(1, 30) ?: 10

            return repo.execute("repos/$owner/$repoName/pulls?state=$state&per_page=$maxResults").fold(
                onSuccess = { body ->
                    val array = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull()
                    if (array.isNullOrEmpty()) return@fold ToolResult.Success("No pull requests found for $owner/$repoName ($state).")

                    val sb = StringBuilder()
                    sb.append("PULL REQUESTS FOR $owner/$repoName ($state):\n")
                    array.forEach { item ->
                        val obj = item.jsonObject
                        val number = obj["number"]?.jsonPrimitive?.content ?: "?"
                        val title = obj["title"]?.jsonPrimitive?.content ?: "Untitled"
                        val user = obj["user"]?.jsonObject?.get("login")?.jsonPrimitive?.content ?: "unknown"
                        val htmlUrl = obj["html_url"]?.jsonPrimitive?.content ?: ""
                        sb.append("PR #$number: $title by @$user\n  $htmlUrl\n")
                    }
                    ToolResult.Success(sb.toString())
                },
                onFailure = { ToolResult.Failure(it.message ?: "Failed to list pull requests") }
            )
        }
    }

    // 8. File get
    private class FileGetTool(private val repo: GitHubRepository) : Tool {
        override val id = "github.file.get"
        override val title = "Reading file from GitHub"
        override val description = "Fetches content of a file from a repository at an optional ref/branch."
        override val requiredCapabilities = setOf(Capability.GITHUB)
        override val riskLevel = RiskLevel.SAFE
        override val timeoutMs = 25_000L
        override val parametersSchemaJson = """
            {
              "type": "object",
              "properties": {
                "owner": { "type": "string" },
                "repo": { "type": "string" },
                "path": { "type": "string" },
                "ref": { "type": "string" }
              },
              "required": ["owner", "repo", "path"]
            }
        """.trimIndent()

        override suspend fun execute(argumentsJson: String): ToolResult {
            val owner = arg(argumentsJson, "owner") ?: return ToolResult.Failure("Missing owner")
            val repoName = arg(argumentsJson, "repo") ?: return ToolResult.Failure("Missing repo")
            val path = arg(argumentsJson, "path")?.trimStart('/') ?: return ToolResult.Failure("Missing path")
            val ref = arg(argumentsJson, "ref")

            val endpoint = buildString {
                append("repos/").append(owner).append("/").append(repoName).append("/contents/").append(path)
                if (!ref.isNullOrBlank()) append("?ref=").append(ref)
            }

            return repo.execute(endpoint).fold(
                onSuccess = { body ->
                    val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                    val downloadUrl = obj?.get("download_url")?.jsonPrimitive?.content
                    if (!downloadUrl.isNullOrBlank()) {
                        repo.execute(downloadUrl).fold(
                            onSuccess = { rawContent ->
                                ToolResult.Success("FILE $path ($owner/$repoName):\n\n${rawContent.take(15_000)}")
                            },
                            onFailure = { ToolResult.Failure(it.message ?: "Failed to download file content") }
                        )
                    } else {
                        ToolResult.Success(body.take(15_000))
                    }
                },
                onFailure = { ToolResult.Failure(it.message ?: "Failed to fetch file") }
            )
        }
    }
}
