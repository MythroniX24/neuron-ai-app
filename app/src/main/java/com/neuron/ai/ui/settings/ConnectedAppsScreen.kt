package com.neuron.ai.ui.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.neuron.ai.R
import com.neuron.ai.core.integration.github.GitHubAccount
import com.neuron.ai.core.integration.google.GoogleAccount
import com.neuron.ai.core.integration.google.GoogleService
import com.neuron.ai.core.integration.google.canUse
import com.neuron.ai.core.integration.google.connectedServices
import com.neuron.ai.data.google.GoogleConsent
import com.neuron.ai.di.AppContainer
import com.neuron.ai.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Connectors: Drive, Gmail, Docs and Sheets as agent tools.
 *
 * Each service is its own card with its own connect button, so a person can
 * add only Gmail, or Gmail and Drive, and can link several Google accounts to
 * one service. Every Google API call runs on the connector Worker; this phone
 * keeps only a short-lived backend session token.
 *
 * The diagnostics card exists because a Google consent failure is otherwise a
 * single opaque sentence: it shows the installed APK's signing SHA-1 (the
 * usual cause), the client id and backend in use, the last Google status code
 * and a live backend probe.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedAppsScreen(
    container: AppContainer,
    onBack: () -> Unit
) {
    val viewModel: ConnectedAppsViewModel =
        viewModel(factory = ConnectedAppsViewModelFactory(container))
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val busyService by viewModel.busyService.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val messageIsError by viewModel.messageIsError.collectAsStateWithLifecycle()
    val health by viewModel.health.collectAsStateWithLifecycle()
    val gitHubAccount by viewModel.gitHubAccount.collectAsStateWithLifecycle()
    val gitHubAuthState by viewModel.gitHubAuthState.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    var pendingService by remember { mutableStateOf<GoogleService?>(null) }
    var confirmDisconnect by remember { mutableStateOf<GoogleAccount?>(null) }
    var diagnosticsOpen by remember { mutableStateOf(false) }
    var showGitHubDialog by remember { mutableStateOf(false) }
    var gitHubTokenInput by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            // A consent screen that closes itself ("problem communicating with
            // Google servers") also arrives as RESULT_CANCELED — Google only
            // reports the real code for a resolvable failure. Both are recorded
            // as sign-in errors so the report shows the last thing that happened.
            val sha1 = viewModel.signingSha1()
            // Google reports a configuration failure as a plain cancel, so the
            // message lists the checks in the order that actually causes it.
            val text = "Google returned no authorization code (result " +
                "${result.resultCode}). If you cancelled the dialog yourself, ignore this. " +
                "Otherwise check, in order:\n\n" +
                "1. Android OAuth client — Google Cloud → Credentials: an Android client " +
                "for package ${viewModel.packageName()} with SHA-1 $sha1 (shown below).\n" +
                "2. OAuth consent screen — Google Auth Platform → Audience: add this Google " +
                "account as a test user while the app is in Testing, and list the Drive, " +
                "Gmail, Docs and Sheets scopes under Data access.\n" +
                "3. Google Play services: update it in the Play Store.\n" +
                "4. Reinstall the latest APK — a different signing key is a different app " +
                "to Google."
            container.googleSignInHelper.noteFailure(text)
            viewModel.report(text)
        } else {
            when (val consent = container.googleSignInHelper.resultFrom(data)) {
                is GoogleConsent.Authorized -> viewModel.connect(consent.serverAuthCode, pendingService)
                is GoogleConsent.ConsentRequired -> viewModel.report(
                    "Google still needs your consent. Tap Connect again."
                )
                is GoogleConsent.Failed -> viewModel.report(consent.message)
            }
        }
    }

    val startConsent: (GoogleService) -> Unit = { service ->
        pendingService = service
        viewModel.markStarting(service)
        scope.launch {
            val scopes = GoogleService.requestScopes(service, viewModel.grantedScopes())
            when (val consent = container.googleSignInHelper.start(scopes)) {
                is GoogleConsent.Authorized -> viewModel.connect(consent.serverAuthCode, service)
                is GoogleConsent.ConsentRequired -> launcher.launch(
                    IntentSenderRequest.Builder(consent.pendingIntent.intentSender).build()
                )
                is GoogleConsent.Failed -> viewModel.report(consent.message)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        TopAppBar(
            title = {
                Column {
                    Text("Connectors", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Google workspace tools",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = { viewModel.refresh() }, enabled = !busy && accounts.isNotEmpty()) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh accounts")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.lg)
        ) {
            IntroCard(accountCount = accounts.size)

            if (!viewModel.configured) {
                NoticeCard(
                    title = "Connector backend missing",
                    body = "This build has no connector backend URL, so Google accounts cannot " +
                        "be linked. Build with -PneuronBackendUrl=<worker url>.",
                    isError = true
                )
            }

            GoogleService.entries.forEach { service ->
                Spacer(Modifier.height(Spacing.md))
                ServiceCard(
                    service = service,
                    accounts = accounts,
                    busy = busy,
                    busyService = busyService,
                    onConnect = { startConsent(service) },
                    onDisconnect = { confirmDisconnect = it }
                )
            }

            Spacer(Modifier.height(Spacing.lg))
            Text(
                text = "Developer Connectors",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = Spacing.xs)
            )
            Spacer(Modifier.height(Spacing.sm))
            GitHubServiceCard(
                account = gitHubAccount,
                busy = busy || gitHubAuthState !is ConnectedAppsViewModel.GitHubAuthState.Idle,
                onSignIn = { viewModel.startGitHubDeviceAuth() },
                onConnectWithToken = { showGitHubDialog = true },
                onDisconnect = { viewModel.disconnectGitHub() }
            )

            message?.let { text ->
                Spacer(Modifier.height(Spacing.md))
                NoticeCard(
                    title = if (messageIsError) "Could not connect" else "Done",
                    body = text,
                    isError = messageIsError
                )
                if (messageIsError) {
                    // The signing fingerprint is the single fact Google needs;
                    // keep it next to the failure instead of hiding it in the
                    // diagnostics card.
                    val snapshot = viewModel.diagnosticsSnapshot()
                    Spacer(Modifier.height(Spacing.sm))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ) {
                        Column(Modifier.padding(Spacing.md)) {
                            Text(
                                text = "APK Google must match",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "package: ${snapshot.build.packageName}\n" +
                                    "SHA-1: ${snapshot.build.signingSha1 ?: "unavailable"}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            )
                            OutlinedButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(viewModel.diagnosticsText()))
                                },
                                modifier = Modifier.padding(top = Spacing.sm)
                            ) { Text("Copy full report") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.lg))
            DiagnosticsCard(
                open = diagnosticsOpen,
                onToggle = { diagnosticsOpen = !diagnosticsOpen },
                viewModel = viewModel,
                health = health,
                onCheckBackend = { viewModel.checkBackend() },
                onCopy = { clipboard.setText(AnnotatedString(viewModel.diagnosticsText())) }
            )

            Spacer(Modifier.height(Spacing.lg))
            Text(
                text = "Sending mail and deleting Drive files always ask for confirmation again, " +
                    "even when the Google permission is already granted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    confirmDisconnect?.let { account ->
        AlertDialog(
            shape = RoundedCornerShape(18.dp),
            onDismissRequest = { confirmDisconnect = null },
            title = { Text("Disconnect ${account.label}?") },
            text = {
                Text(
                    "This revokes the Google grant for Gmail, Drive, Docs, Sheets, Slides and Calendar on this " +
                        "account. Other linked accounts stay connected."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.disconnect(account.id)
                    confirmDisconnect = null
                }) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = null }) { Text("Cancel") }
            }
        )
    }

    if (showGitHubDialog) {
        val context = LocalContext.current
        AlertDialog(
            shape = RoundedCornerShape(18.dp),
            onDismissRequest = { showGitHubDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Image(
                        painter = painterResource(R.drawable.ic_brand_github),
                        contentDescription = "GitHub",
                        modifier = Modifier.size(28.dp)
                    )
                    Text("Connect GitHub Account")
                }
            },
            text = {
                Column {
                    Text(
                        text = "Enter a GitHub Personal Access Token (classic or fine-grained) to allow the agent to search repositories, inspect issues, read READMEs and code.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Spacing.md))
                    OutlinedTextField(
                        value = gitHubTokenInput,
                        onValueChange = { gitHubTokenInput = it },
                        label = { Text("Personal Access Token") },
                        placeholder = { Text("ghp_... or github_pat_...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    TextButton(
                        onClick = {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/settings/tokens/new?scopes=repo,read:user&description=Neuron-AI")
                            )
                            context.startActivity(intent)
                        }
                    ) {
                        Text("Generate new token on GitHub ↗", style = MaterialTheme.typography.labelMedium)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showGitHubDialog = false
                        viewModel.connectGitHub(gitHubTokenInput)
                        gitHubTokenInput = ""
                    },
                    enabled = gitHubTokenInput.isNotBlank()
                ) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showGitHubDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    when (val state = gitHubAuthState) {
        is ConnectedAppsViewModel.GitHubAuthState.RequestingCode -> {
            AlertDialog(
                shape = RoundedCornerShape(18.dp),
                onDismissRequest = { viewModel.dismissGitHubAuthDialog() },
                title = { Text("Connecting to GitHub...") },
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text("Requesting authorization code from GitHub...")
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissGitHubAuthDialog() }) { Text("Cancel") }
                }
            )
        }
        is ConnectedAppsViewModel.GitHubAuthState.AwaitingUser -> {
            val context = LocalContext.current
            AlertDialog(
                shape = RoundedCornerShape(18.dp),
                onDismissRequest = { viewModel.dismissGitHubAuthDialog() },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Image(
                            painter = painterResource(R.drawable.ic_brand_github),
                            contentDescription = "GitHub",
                            modifier = Modifier.size(28.dp)
                        )
                        Text("Authorize on GitHub")
                    }
                },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Enter this one-time code on GitHub to authorize Neuron AI:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.md))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                            modifier = Modifier.clickable {
                                clipboard.setText(AnnotatedString(state.userCode))
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = state.userCode,
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 2.sp
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Icon(
                                    Icons.Outlined.ContentCopy,
                                    contentDescription = "Copy code",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = "Tap code to copy",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.md))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                text = "Waiting for approval in browser...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            clipboard.setText(AnnotatedString(state.userCode))
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(state.verificationUri))
                            context.startActivity(intent)
                        }
                    ) {
                        Text("Copy & Open GitHub ↗")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissGitHubAuthDialog() }) {
                        Text("Cancel")
                    }
                }
            )
        }
        is ConnectedAppsViewModel.GitHubAuthState.Error -> {
            AlertDialog(
                shape = RoundedCornerShape(18.dp),
                onDismissRequest = { viewModel.dismissGitHubAuthDialog() },
                title = { Text("GitHub Sign In Failed") },
                text = { Text(state.message) },
                confirmButton = {
                    Button(onClick = { viewModel.startGitHubDeviceAuth() }) {
                        Text("Retry")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissGitHubAuthDialog() }) {
                        Text("Close")
                    }
                }
            )
        }
        is ConnectedAppsViewModel.GitHubAuthState.Success -> {
            // Dismiss dialog automatically
            viewModel.dismissGitHubAuthDialog()
        }
        ConnectedAppsViewModel.GitHubAuthState.Idle -> {
            // Nothing to show
        }
    }
}

@Composable
private fun IntroCard(accountCount: Int) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.ic_google_g),
                    contentDescription = null,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = if (accountCount == 0) "Connect your Google account"
                    else "$accountCount account${if (accountCount == 1) "" else "s"} connected",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "Pick only the services you need. Google calls run on the connector " +
                    "backend; this phone stores a short-lived session token and never sees a " +
                    "Google refresh token.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** One connector: icon, what it does, the accounts that can use it, connect. */
@Composable
private fun ServiceCard(
    service: GoogleService,
    accounts: List<GoogleAccount>,
    busy: Boolean,
    busyService: GoogleService?,
    onConnect: () -> Unit,
    onDisconnect: (GoogleAccount) -> Unit
) {
    val linked = accounts.filter { it.canUse(service) }
    val connecting = busyService == service
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    // Image, not Icon: Material's Icon tints whatever painter it
                    // is given with the current content colour, which flattens
                    // the brand logo to one flat colour.
                    Image(
                        painter = painterResource(service.iconRes()),
                        contentDescription = service.label,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = service.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = service.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (linked.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = linked.size.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (linked.isEmpty()) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = "Not connected yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                linked.forEach { account ->
                    AccountRow(account = account, onDisconnect = { onDisconnect(account) })
                }
            }

            Spacer(Modifier.height(Spacing.sm))
            Button(
                onClick = onConnect,
                enabled = !busy && !connecting
            ) {
                if (connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(Spacing.sm))
                }
                Text(
                    if (linked.isEmpty()) "Connect ${service.label}"
                    else "Add another account"
                )
            }
        }
    }
}

@Composable
private fun AccountRow(account: GoogleAccount, onDisconnect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AccountAvatar(account, size = 30.dp)
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                account.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val services = account.connectedServices().joinToString(", ") { it.label }
            Text(
                text = services.ifBlank { "Google account" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = onDisconnect) {
            Text("Disconnect", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun AccountAvatar(account: GoogleAccount, size: androidx.compose.ui.unit.Dp) {
    val initial = account.displayName.trim().firstOrNull()?.uppercase() ?: "G"
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        account.picture?.takeIf { it.isNotBlank() }?.let { picture ->
            AsyncImage(
                model = picture,
                contentDescription = account.displayName,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
            )
        }
    }
}

@Composable
private fun NoticeCard(title: String, body: String, isError: Boolean) {
    val container = if (isError) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
    }
    Surface(shape = RoundedCornerShape(16.dp), color = container) {
        Column(Modifier.padding(Spacing.md)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Everything needed to explain a failed connect. Collapsed by default so the
 * screen stays calm; the copy button produces a report that can be pasted
 * anywhere without logcat.
 */
@Composable
private fun DiagnosticsCard(
    open: Boolean,
    onToggle: () -> Unit,
    viewModel: ConnectedAppsViewModel,
    health: com.neuron.ai.data.google.BackendHealth?,
    onCheckBackend: () -> Unit,
    onCopy: () -> Unit
) {
    val snapshot = viewModel.diagnosticsSnapshot()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.BugReport,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = "Diagnostics",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (open) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = open) {
                Column(Modifier.padding(top = Spacing.sm)) {
                    DiagLine("Build", "${snapshot.build.versionName} (${snapshot.build.versionCode})")
                    DiagLine("Package", snapshot.build.packageName)
                    DiagLine(
                        "Signing SHA-1",
                        snapshot.build.signingSha1 ?: "unavailable",
                        monospace = true
                    )
                    DiagLine("Web client", snapshot.webClientId, monospace = true)
                    DiagLine("Backend", snapshot.backendUrl, monospace = true)
                    DiagLine("Configured", if (snapshot.configured) "yes" else "NO")
                    DiagLine("Accounts", snapshot.accounts.size.toString())
                    snapshot.lastSignInError?.let { error ->
                        DiagLine("Last sign-in error", error, error = true)
                    }
                    snapshot.lastConnectError?.let { error ->
                        DiagLine("Last connect error", error, error = true)
                    }
                    snapshot.lastBackendError?.let { error ->
                        DiagLine("Last backend error", error, error = true)
                    }

                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = "Google Sign-In matching is by package name + signing " +
                            "certificate. If consent fails with code 10, add the SHA-1 above " +
                            "to the Android OAuth client for ${snapshot.build.packageName}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(Spacing.sm))
                    health?.let { result ->
                        DiagLine(
                            "Backend probe",
                            if (result.ok) "OK ${result.status} in ${result.latencyMs}ms"
                            else "FAILED ${result.status} in ${result.latencyMs}ms — ${result.detail}",
                            error = !result.ok
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        OutlinedButton(onClick = onCheckBackend) { Text("Test backend") }
                        OutlinedButton(onClick = onCopy) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Copy report")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagLine(
    key: String,
    value: String,
    monospace: Boolean = false,
    error: Boolean = false
) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = if (monospace) FontFamily.Monospace else null,
                fontSize = 11.sp
            ),
            color = if (error) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Official product logos (downloaded brand PNGs), not hand-drawn vectors.
 * They carry their own colours, so they are drawn with [Image] — an `Icon`
 * would tint them with `LocalContentColor` and the colour would disappear.
 */
private fun GoogleService.iconRes(): Int = when (this) {
    GoogleService.GMAIL -> R.drawable.ic_brand_gmail
    GoogleService.DRIVE -> R.drawable.ic_brand_drive
    GoogleService.DOCS -> R.drawable.ic_brand_docs
    GoogleService.SHEETS -> R.drawable.ic_brand_sheets
    GoogleService.SLIDES -> R.drawable.ic_brand_slides
    GoogleService.CALENDAR -> R.drawable.ic_brand_calendar
}

@Composable
private fun GitHubServiceCard(
    account: GitHubAccount?,
    busy: Boolean,
    onSignIn: () -> Unit,
    onConnectWithToken: () -> Unit,
    onDisconnect: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_brand_github),
                        contentDescription = "GitHub",
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "GitHub",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Search repos, read code, view and create issues & pull requests",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (account != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "1",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.md))

            if (account == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = onConnectWithToken,
                        enabled = !busy,
                        contentPadding = PaddingValues(horizontal = Spacing.xs, vertical = Spacing.xs)
                    ) {
                        Text("Use Token", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(
                        onClick = onSignIn,
                        enabled = !busy,
                        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.xs)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_brand_github),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text("Sign in with GitHub")
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "@${account.login}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        account.name?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = onDisconnect,
                        enabled = !busy,
                        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.xs)
                    ) {
                        Text("Disconnect")
                    }
                }
            }
        }
    }
}
