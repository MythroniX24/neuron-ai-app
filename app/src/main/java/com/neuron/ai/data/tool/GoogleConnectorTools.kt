package com.neuron.ai.data.tool

import com.neuron.ai.core.agent.RiskLevel
import com.neuron.ai.core.agent.Tool
import com.neuron.ai.core.agent.ToolResult
import com.neuron.ai.core.integration.google.GoogleConnectorRepository
import com.neuron.ai.core.permissions.Capability
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Google connector tools for the agent: Drive, Gmail, Sheets and Docs.
 *
 * These are THIN proxies — every Google REST call is executed by the connector
 * Worker, never by the app. Each tool just forwards its arguments (plus the
 * chosen account) to `POST /google/<action>` and returns the Worker's summary.
 *
 * Risk model: acting on a connected Google account requires [Capability.GOOGLE]
 * (never default-granted). Irreversible actions — sending mail and deleting
 * Drive files — are [RiskLevel.DESTRUCTIVE], so the executor re-prompts the
 * user every single time, and the tool then sends `confirm = true` because the
 * backend refuses those two actions without an explicit confirmation.
 */
object GoogleConnectorTools {

    private val json = Json { ignoreUnknownKeys = true }

    fun all(repository: GoogleConnectorRepository): List<Tool> = listOf(
        // ---- Drive ---------------------------------------------------------
        action(
            repository, "google.drive.list", "drive.list", "Listing Drive files",
            "Searches the connected Google Drive. Returns matching file names, ids, mime types " +
                "and modified times. Pass rawQuery=true to send the query straight to Drive's " +
                "search syntax (e.g. \"mimeType='application/pdf'\").",
            schema(
                "\"query\":{\"type\":\"string\",\"description\":\"Search text or raw Drive query\"}," +
                    "\"pageSize\":{\"type\":\"integer\"},\"rawQuery\":{\"type\":\"boolean\"}",
                emptyList()
            )
        ),
        action(
            repository, "google.drive.metadata", "drive.metadata", "Reading Drive metadata",
            "Returns metadata for one Drive file: name, mime type, size, owners, parents and " +
                "web link. Use it before reading content when the id came from a search.",
            schema("\"fileId\":{\"type\":\"string\"}", listOf("fileId"))
        ),
        action(
            repository, "google.drive.read", "drive.read", "Reading a Drive file",
            "Reads the text content of a Drive file (Docs/Sheets are exported to text; " +
                "plain-text-like files are downloaded). Content is bounded by maxChars.",
            schema(
                "\"fileId\":{\"type\":\"string\"},\"maxChars\":{\"type\":\"integer\"}",
                listOf("fileId")
            )
        ),
        action(
            repository, "google.drive.upload", "drive.upload", "Uploading to Drive",
            "Creates a new file in Drive with the given text content and mime type " +
                "(default text/plain). Optionally places it inside parentId (a folder id).",
            schema(
                "\"name\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}," +
                    "\"mimeType\":{\"type\":\"string\"},\"parentId\":{\"type\":\"string\"}",
                listOf("name", "content")
            )
        ),
        action(
            repository, "google.drive.folder", "drive.folder", "Creating a Drive folder",
            "Creates a folder in Drive, optionally inside another folder (parentId).",
            schema(
                "\"name\":{\"type\":\"string\"},\"parentId\":{\"type\":\"string\"}",
                listOf("name")
            )
        ),
        action(
            repository, "google.drive.rename", "drive.rename", "Renaming a Drive file",
            "Renames an existing Drive file or folder.",
            schema(
                "\"fileId\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}",
                listOf("fileId", "name")
            )
        ),
        action(
            repository, "google.drive.move", "drive.move", "Moving a Drive file",
            "Moves a Drive file into addParent (folder id) and, when removeParent is given, " +
                "removes it from that old folder.",
            schema(
                "\"fileId\":{\"type\":\"string\"},\"addParent\":{\"type\":\"string\"}," +
                    "\"removeParent\":{\"type\":\"string\"}",
                listOf("fileId", "addParent")
            )
        ),
        action(
            repository, "google.drive.delete", "drive.delete", "Deleting a Drive file",
            "PERMANENTLY deletes a Drive file by id. Always ask the user first and only call " +
                "this after they explicitly approve.",
            schema("\"fileId\":{\"type\":\"string\"}", listOf("fileId")),
            risk = RiskLevel.DESTRUCTIVE
        ),

        // ---- Gmail ---------------------------------------------------------
        action(
            repository, "google.gmail.search", "gmail.search", "Searching Gmail",
            "Searches the connected Gmail mailbox using Gmail's query syntax " +
                "(e.g. \"from:someone@example.com newer_than:7d\"). Returns matching message " +
                "ids, senders, subjects and snippets.",
            schema(
                "\"query\":{\"type\":\"string\"},\"maxResults\":{\"type\":\"integer\"}",
                listOf("query")
            )
        ),
        action(
            repository, "google.gmail.read", "gmail.read", "Reading an email",
            "Reads one Gmail message: headers, recipients and the plain-text body.",
            schema("\"messageId\":{\"type\":\"string\"}", listOf("messageId"))
        ),
        action(
            repository, "google.gmail.thread", "gmail.thread", "Reading a Gmail thread",
            "Reads a whole Gmail thread so a conversation can be followed from start to finish.",
            schema("\"threadId\":{\"type\":\"string\"}", listOf("threadId"))
        ),
        action(
            repository, "google.gmail.draft", "gmail.draft", "Saving a Gmail draft",
            "Creates a draft (not sent). Prefer this over sending when the user has not " +
                "explicitly asked for the mail to go out.",
            schema(
                "\"to\":{\"type\":\"string\"},\"subject\":{\"type\":\"string\"}," +
                    "\"body\":{\"type\":\"string\"}",
                listOf("to")
            )
        ),
        action(
            repository, "google.gmail.send", "gmail.send", "Sending an email",
            "SENDS an email immediately. This cannot be undone — ask the user, show them the " +
                "exact recipient, subject and body, and only call this after they approve.",
            schema(
                "\"to\":{\"type\":\"string\"},\"subject\":{\"type\":\"string\"}," +
                    "\"body\":{\"type\":\"string\"}",
                listOf("to")
            ),
            risk = RiskLevel.DESTRUCTIVE
        ),

        // ---- Sheets --------------------------------------------------------
        action(
            repository, "google.sheets.read", "sheets.read", "Reading a spreadsheet",
            "Reads a range from a Google Sheet (A1 notation, e.g. \"Sheet1!A1:D20\").",
            schema(
                "\"spreadsheetId\":{\"type\":\"string\"},\"range\":{\"type\":\"string\"}",
                listOf("spreadsheetId", "range")
            )
        ),
        action(
            repository, "google.sheets.metadata", "sheets.metadata", "Reading sheet structure",
            "Lists a spreadsheet's sheets (tab names and ids) and its title.",
            schema("\"spreadsheetId\":{\"type\":\"string\"}", listOf("spreadsheetId"))
        ),
        action(
            repository, "google.sheets.create", "sheets.create", "Creating a spreadsheet",
            "Creates a new Google Spreadsheet with the given title.",
            schema("\"title\":{\"type\":\"string\"}", listOf("title"))
        ),
        action(
            repository, "google.sheets.update", "sheets.update", "Writing to a spreadsheet",
            "Overwrites a range with a 2-D array of values (values = array of rows).",
            schema(
                "\"spreadsheetId\":{\"type\":\"string\"},\"range\":{\"type\":\"string\"}," +
                    "\"values\":{\"type\":\"array\",\"items\":{\"type\":\"array\"}}",
                listOf("spreadsheetId", "range", "values")
            )
        ),
        action(
            repository, "google.sheets.append", "sheets.append", "Appending rows",
            "Appends rows after the existing data in a range (values = array of rows).",
            schema(
                "\"spreadsheetId\":{\"type\":\"string\"},\"range\":{\"type\":\"string\"}," +
                    "\"values\":{\"type\":\"array\",\"items\":{\"type\":\"array\"}}",
                listOf("spreadsheetId", "range", "values")
            )
        ),

        // ---- Docs ----------------------------------------------------------
        action(
            repository, "google.docs.create", "docs.create", "Creating a document",
            "Creates a new Google Doc with the given title.",
            schema("\"title\":{\"type\":\"string\"}", listOf("title"))
        ),
        action(
            repository, "google.docs.read", "docs.read", "Reading a document",
            "Reads the plain text of a Google Doc.",
            schema("\"documentId\":{\"type\":\"string\"}", listOf("documentId"))
        ),
        action(
            repository, "google.docs.append", "docs.append", "Appending to a document",
            "Appends text to the end of a Google Doc.",
            schema(
                "\"documentId\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"}",
                listOf("documentId", "text")
            )
        ),
        action(
            repository, "google.docs.replace", "docs.replace", "Replacing document text",
            "Replaces every occurrence of find with replace inside a Google Doc.",
            schema(
                "\"documentId\":{\"type\":\"string\"},\"find\":{\"type\":\"string\"}," +
                    "\"replace\":{\"type\":\"string\"}",
                listOf("documentId", "find")
            )
        ),
        // ---- Slides -------------------------------------------------------
        action(
            repository, "google.slides.get", "slides.get", "Reading Google Slides",
            "Retrieves presentation details, slide count, slide titles, and text elements " +
                "from a Google Slides presentation by presentationId.",
            schema("\"presentationId\":{\"type\":\"string\"}", listOf("presentationId"))
        ),
        action(
            repository, "google.slides.create", "slides.create", "Creating presentation",
            "Creates a new Google Slides presentation with a given title. Returns the presentation ID and web link.",
            schema("\"title\":{\"type\":\"string\"}", listOf("title"))
        ),
        action(
            repository, "google.slides.addSlide", "slides.addSlide", "Adding slide",
            "Appends a new slide with a title and optional body text into a Google Slides presentation.",
            schema(
                "\"presentationId\":{\"type\":\"string\"},\"title\":{\"type\":\"string\"}," +
                    "\"body\":{\"type\":\"string\"}",
                listOf("presentationId", "title")
            )
        ),
        // ---- Calendar -----------------------------------------------------
        action(
            repository, "google.calendar.list", "calendar.list", "Listing calendar events",
            "Lists upcoming events on the user's primary Google Calendar. Optional filters: " +
                "query (search text), timeMin (ISO-8601 start date/time), timeMax, maxResults.",
            schema(
                "\"query\":{\"type\":\"string\"},\"timeMin\":{\"type\":\"string\"}," +
                    "\"timeMax\":{\"type\":\"string\"},\"maxResults\":{\"type\":\"integer\"}",
                emptyList()
            )
        ),
        action(
            repository, "google.calendar.create", "calendar.create", "Creating calendar event",
            "Creates a new event on Google Calendar. Requires summary (title), startTime and endTime " +
                "(ISO-8601 strings, e.g. '2026-11-20T14:00:00Z'). Optional description and location.",
            schema(
                "\"summary\":{\"type\":\"string\"},\"startTime\":{\"type\":\"string\"}," +
                    "\"endTime\":{\"type\":\"string\"},\"description\":{\"type\":\"string\"}," +
                    "\"location\":{\"type\":\"string\"}",
                listOf("summary", "startTime", "endTime")
            )
        ),
        action(
            repository, "google.calendar.delete", "calendar.delete", "Deleting calendar event",
            "PERMANENTLY deletes an event from Google Calendar by eventId. Always ask user confirmation first.",
            schema("\"eventId\":{\"type\":\"string\"}", listOf("eventId")),
            risk = RiskLevel.DESTRUCTIVE
        )
    )

    private fun action(
        repository: GoogleConnectorRepository,
        id: String,
        action: String,
        title: String,
        description: String,
        parameters: String,
        risk: RiskLevel = RiskLevel.ELEVATED
    ): Tool = GoogleActionTool(repository, id, action, title, description, parameters, risk)

    /** Schema builder that adds the shared optional `accountId` argument. */
    private fun schema(properties: String, required: List<String>): String {
        val props = if (properties.isBlank()) {
            """"accountId":{"type":"string"}"""
        } else {
            "$properties," + """"accountId":{"type":"string"}"""
        }
        val req = required.joinToString(",") { "\"$it\"" }
        return """{"type":"object","properties":{$props},"required":[$req]}"""
    }
}

/** One Google action as an agent tool; the Worker does the actual API call. */
private class GoogleActionTool(
    private val repository: GoogleConnectorRepository,
    override val id: String,
    private val action: String,
    override val title: String,
    override val description: String,
    override val parametersSchemaJson: String,
    override val riskLevel: RiskLevel
) : Tool {

    override val requiredCapabilities = setOf(Capability.GOOGLE)
    override val timeoutMs = 45_000L

    override suspend fun execute(argumentsJson: String): ToolResult {
        if (!repository.configured) {
            return ToolResult.Failure(
                "Google connectors are not configured in this build (missing backend URL)."
            )
        }
        val arguments = runCatching {
            Json.parseToJsonElement(argumentsJson).jsonObject
        }.getOrElse {
            return ToolResult.Failure("Arguments must be a JSON object.")
        }

        // The account selector is a routing hint, not a Google API argument.
        val account = arguments["accountId"]?.jsonPrimitive?.contentOrNull
        val forwarded = arguments.filterKeys { it != "accountId" }.toMutableMap()
        if (riskLevel == RiskLevel.DESTRUCTIVE) {
            // Reaching here means the executor already re-prompted the user
            // (DESTRUCTIVE tools ask every time), so the approval exists.
            forwarded["confirm"] = JsonPrimitive(true)
        }

        val result = repository.execute(action, JsonObject(forwarded), account)
        return result.fold(
            onSuccess = { outcome ->
                ToolResult.Success(outcome.text ?: outcome.payloadJson)
            },
            onFailure = { error ->
                ToolResult.Failure(error.message ?: "Google request failed.")
            }
        )
    }
}
