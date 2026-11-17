package com.neuron.ai.ui.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neuron.ai.core.integration.google.GoogleScopes
import com.neuron.ai.data.google.GoogleConsent
import com.neuron.ai.di.AppContainer
import com.neuron.ai.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Connected Apps: link Google accounts for Drive, Gmail, Sheets and Docs.
 *
 * The app runs Google consent only to obtain a server auth code; the connector
 * Worker exchanges it and keeps the refresh token encrypted. Disconnecting here
 * revokes the grant on the backend and drops the local session.
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
    val message by viewModel.message.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            viewModel.report("Google sign-in was cancelled.")
        } else {
            when (val consent = container.googleSignInHelper.resultFrom(data)) {
                is GoogleConsent.Authorized -> viewModel.connect(consent.serverAuthCode)
                is GoogleConsent.ConsentRequired -> viewModel.report(
                    "Google still needs your consent. Tap Connect again."
                )
                is GoogleConsent.Failed -> viewModel.report(consent.message)
            }
        }
    }

    val startConsent: () -> Unit = {
        scope.launch {
            when (val consent = container.googleSignInHelper.start()) {
                is GoogleConsent.Authorized -> viewModel.connect(consent.serverAuthCode)
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
            title = { Text("Connected Apps", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg)
        ) {
            Text(
                "Google",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "Drive, Gmail, Sheets and Docs as agent tools. Google calls run on the " +
                    "connector backend; this phone only keeps a short-lived session token — " +
                    "the refresh token never leaves the server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs)
            )

            if (!viewModel.configured) {
                Text(
                    "This build has no connector backend URL, so Google accounts cannot be " +
                        "linked. Build with -PneuronBackendUrl=<worker url> and try again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Spacing.md)
                )
            } else {
                if (accounts.isEmpty()) {
                    Text(
                        "No Google account is connected yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = Spacing.md)
                    )
                } else {
                    accounts.forEach { account ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = Spacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    account.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                account.email?.let { email ->
                                    Text(
                                        email,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    "${account.scopes.size} permissions granted",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = { viewModel.disconnect(account.id) },
                                enabled = !busy
                            ) {
                                Text("Disconnect", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = startConsent, enabled = !busy) {
                        Text(if (accounts.isEmpty()) "Connect Google" else "Add another account")
                    }
                    if (accounts.isNotEmpty()) {
                        OutlinedButton(onClick = { viewModel.refresh() }, enabled = !busy) {
                            Text("Refresh")
                        }
                    }
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(start = Spacing.xs),
                            strokeWidth = Spacing.xs / 2
                        )
                    }
                }
            }

            message?.let { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.md)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.lg))

            Text(
                "Permissions requested",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            GoogleScopes.LABELS.forEach { (_, label) ->
                Text(
                    "• $label",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
            Text(
                "Sending email and deleting Drive files always ask for confirmation again, " +
                    "even when the Google permission is already granted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.md)
            )
        }
    }
}
