/*
 * Copyright (c) 2026, Renaud Allard <renaud@allard.it>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
 * THE POSSIBILITY OF SUCH DAMAGES.
 */

package it.allard.regexphone.ui

import android.app.role.RoleManager
import android.content.Context
import android.content.res.Resources
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.allard.regexphone.R
import it.allard.regexphone.data.Rule
import it.allard.regexphone.data.RuleAction
import it.allard.regexphone.data.RuleIO
import it.allard.regexphone.data.RuleRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesListScreen(
    onAddRule: () -> Unit,
    onEditRule: (Long) -> Unit,
) {
    val ctx = LocalContext.current
    val rules by RuleRepository.rules.collectAsStateWithLifecycle()
    val storageWarning by RuleRepository.storageWarning.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var menuOpen by remember { mutableStateOf(false) }
    var pendingImportText by rememberSaveable(stateSaver = SafeImportTextSaver) {
        mutableStateOf<String?>(null)
    }
    var pendingImportCount by rememberSaveable { mutableIntStateOf(0) }
    var pendingImportDropped by rememberSaveable { mutableIntStateOf(0) }

    val exportLauncher = rememberLauncherForActivityResult(CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    // "wt" truncates the document; plain "w" leaves trailing
                    // bytes on providers that do not truncate, corrupting the
                    // export.
                    val stream = ctx.contentResolver.openOutputStream(uri, "wt")
                        ?: error("openOutputStream returned null")
                    stream.bufferedWriter().use { it.write(RuleRepository.exportJson()) }
                }.isSuccess
            }
            snackbar.showSnackbar(
                if (ok) ctx.resources.getQuantityString(R.plurals.export_done, rules.size, rules.size)
                else ctx.getString(R.string.export_failed)
            )
        }
    }

    val importLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // SAF documents can be backed by slow or remote providers, so
            // keep the read and the parse off the main thread.
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { readUtf8WithLimit(it, MAX_IMPORT_BYTES) }
                }
            }
            if (result.exceptionOrNull() is FileTooLargeException) {
                snackbar.showSnackbar(ctx.getString(R.string.import_too_large))
                return@launch
            }
            val text = result.getOrNull()
            if (text == null) {
                snackbar.showSnackbar(ctx.getString(R.string.import_read_failed))
                return@launch
            }
            withContext(Dispatchers.Default) { RuleIO.decodeWithSummary(text) }
                .onSuccess { outcome ->
                    when {
                        outcome.rules.size > MAX_IMPORT_RULES -> {
                            snackbar.showSnackbar(
                                ctx.getString(R.string.import_too_many, MAX_IMPORT_RULES)
                            )
                        }
                        outcome.rules.isEmpty() -> {
                            snackbar.showSnackbar(
                                if (outcome.dropped > 0) {
                                    ctx.getString(R.string.import_nothing_invalid, outcome.dropped)
                                } else {
                                    ctx.getString(R.string.import_nothing)
                                }
                            )
                        }
                        rules.isEmpty() -> {
                            val ok = RuleRepository.importRules(outcome.rules, replace = true)
                            snackbar.showSnackbar(
                                if (ok) {
                                    importSummary(
                                        ctx.resources, R.plurals.import_imported,
                                        outcome.rules.size, outcome.dropped,
                                    )
                                } else {
                                    ctx.getString(R.string.import_save_failed)
                                }
                            )
                        }
                        else -> {
                            pendingImportText = text
                            pendingImportCount = outcome.rules.size
                            pendingImportDropped = outcome.dropped
                        }
                    }
                }
                .onFailure {
                    snackbar.showSnackbar(ctx.getString(R.string.import_invalid_file))
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu))
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_export)) },
                                enabled = rules.isNotEmpty(),
                                onClick = {
                                    menuOpen = false
                                    exportLauncher.launch("regexphone-rules.json")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_import)) },
                                onClick = {
                                    menuOpen = false
                                    importLauncher.launch(arrayOf("application/json", "*/*"))
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddRule) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_rule))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            RoleCard()
            if (storageWarning) {
                StorageWarningCard(onDismiss = { RuleRepository.dismissStorageWarning() })
            }
            HorizontalDivider()
            if (rules.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(rules, key = { it.id }) { rule ->
                        RuleRow(
                            rule = rule,
                            onToggle = {
                                scope.launch {
                                    if (!RuleRepository.toggleEnabled(rule.id)) {
                                        snackbar.showSnackbar(ctx.getString(R.string.save_change_failed))
                                    }
                                }
                            },
                            onEdit = { onEditRule(rule.id) },
                            onDelete = {
                                val deleted = rule
                                val originalIndex = rules.indexOf(rule)
                                scope.launch {
                                    if (!RuleRepository.delete(deleted.id)) {
                                        snackbar.showSnackbar(ctx.getString(R.string.delete_failed))
                                        return@launch
                                    }
                                    val result = snackbar.showSnackbar(
                                        message = ctx.getString(R.string.rule_deleted),
                                        actionLabel = ctx.getString(R.string.undo),
                                        duration = SnackbarDuration.Short,
                                    )
                                    if (result == SnackbarResult.ActionPerformed &&
                                        !RuleRepository.restoreAt(deleted, originalIndex)
                                    ) {
                                        snackbar.showSnackbar(ctx.getString(R.string.restore_failed))
                                    }
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    val pendingText = pendingImportText
    if (pendingText != null) {
        val pendingCount = pendingImportCount
        val pendingDropped = pendingImportDropped
        val clear = {
            pendingImportText = null
            pendingImportCount = 0
            pendingImportDropped = 0
        }
        val dropSuffix =
            if (pendingDropped > 0) stringResource(R.string.import_dialog_skipped, pendingDropped)
            else ""
        val pendingRules by produceState<List<Rule>?>(initialValue = null, pendingText) {
            value = withContext(Dispatchers.Default) {
                RuleIO.decode(pendingText).getOrElse { emptyList() }
            }
        }
        var importBusy by remember { mutableStateOf(false) }
        val perform = perform@{ replace: Boolean, verbPlural: Int, failMsg: Int ->
            val toImport = pendingRules ?: return@perform
            if (importBusy) return@perform
            importBusy = true
            // Clear the saved pending state before the commit: if the
            // activity is recreated or the dialog dismissed while the import
            // is in flight, a restored dialog would offer the same file
            // again after it was already applied.
            clear()
            // Undispatched start enters the non-cancellable block before the
            // composition scope can be cancelled, so a confirmed import is
            // committed even if the screen is disposed right after the tap.
            // Only the snackbar is lost in that case.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val ok = withContext(NonCancellable) {
                    RuleRepository.importRules(toImport, replace = replace)
                }
                snackbar.showSnackbar(
                    if (ok) importSummary(ctx.resources, verbPlural, pendingCount, pendingDropped)
                    else ctx.getString(failMsg)
                )
            }
        }
        // Replace is bounded by the file cap already; only merge can push the
        // stored set past it, so block merge when the total would exceed it.
        val mergeWouldExceed = rules.size + pendingCount > MAX_IMPORT_RULES
        AlertDialog(
            onDismissRequest = clear,
            title = { Text(stringResource(R.string.menu_import)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            if (mergeWouldExceed) R.string.import_dialog_replace_only
                            else R.string.import_dialog_text,
                            rules.size, pendingCount, dropSuffix,
                        )
                    )
                    if (mergeWouldExceed) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.import_merge_over_limit, MAX_IMPORT_RULES),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = pendingRules != null && !importBusy && !mergeWouldExceed, onClick = {
                        perform(false, R.plurals.import_merged, R.string.import_merge_failed)
                    }) { Text(stringResource(R.string.merge)) }
                    TextButton(enabled = pendingRules != null && !importBusy, onClick = {
                        perform(true, R.plurals.import_replaced, R.string.import_replace_failed)
                    }) { Text(stringResource(R.string.replace)) }
                }
            },
            dismissButton = {
                TextButton(onClick = clear) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun RoleCard() {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var roleHeld by remember { mutableStateOf(isCallScreeningRoleHeld(ctx)) }
    val launcher = rememberLauncherForActivityResult(StartActivityForResult()) {
        roleHeld = isCallScreeningRoleHeld(ctx)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                roleHeld = isCallScreeningRoleHeld(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (roleHeld) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(
                    if (roleHeld) R.string.role_active_title else R.string.role_inactive_title
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    if (roleHeld) R.string.role_active_body else R.string.role_inactive_body
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (roleHeld) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.role_contacts_note),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    val rm = ctx.getSystemService(RoleManager::class.java)
                    val intent = rm?.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
                    if (intent != null) launcher.launch(intent)
                }) {
                    Text(stringResource(R.string.role_set_default))
                }
            }
        }
    }
}

@Composable
private fun StorageWarningCard(onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.storage_warning_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.storage_warning_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        }
    }
}

@Composable
private fun RuleRow(
    rule: Rule,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = rule.name.ifBlank { stringResource(R.string.rule_unnamed) },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = rule.pattern,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(4.dp))
            ActionChip(rule.action)
        }
        Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
        }
    }
}

@Composable
internal fun actionLabel(action: RuleAction): String = stringResource(
    when (action) {
        RuleAction.BLOCK -> R.string.action_block
        RuleAction.SILENCE -> R.string.action_silence
        RuleAction.ALLOW -> R.string.action_allow
    }
)

@Composable
private fun ActionChip(action: RuleAction) {
    val color = when (action) {
        RuleAction.BLOCK -> MaterialTheme.colorScheme.errorContainer
        RuleAction.SILENCE -> MaterialTheme.colorScheme.surfaceVariant
        RuleAction.ALLOW -> MaterialTheme.colorScheme.tertiaryContainer
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(actionLabel(action)) },
        colors = AssistChipDefaults.assistChipColors(
            disabledContainerColor = color,
            disabledLabelColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun isCallScreeningRoleHeld(ctx: Context): Boolean {
    val rm = ctx.getSystemService(RoleManager::class.java) ?: return false
    return rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
}

private fun importSummary(res: Resources, verbPlural: Int, count: Int, dropped: Int): String {
    val base = res.getQuantityString(verbPlural, count, count)
    return if (dropped > 0) base + res.getString(R.string.import_skipped_suffix, dropped) else base
}

// Strings parcel as UTF-16 into the saved-instance-state Bundle, which
// shares a roughly 1 MB Binder transaction with everything else; keep the
// kept import text well below that. Larger pending imports simply do not
// survive process death.
private const val MAX_SAVED_IMPORT_CHARS = 20_000
private const val MAX_IMPORT_BYTES = 1_000_000

// Cap on the stored rule set an import may produce, enforced on both the
// incoming file and a merge total. Every enabled rule is matched on each
// incoming call within a fixed deadline, so an unbounded list could exhaust
// the screening budget and leave later rules unevaluated. A 1 MB file can
// hold far more than this; reject rather than truncate so nothing is
// silently dropped.
private const val MAX_IMPORT_RULES = 1_000

private val SafeImportTextSaver: Saver<String?, String> = Saver(
    save = { value -> value?.takeIf { it.length <= MAX_SAVED_IMPORT_CHARS } },
    restore = { it },
)

private class FileTooLargeException : RuntimeException()

private fun readUtf8WithLimit(stream: InputStream, limit: Int): String {
    val sink = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (true) {
        val n = stream.read(buf)
        if (n == -1) break
        total += n
        if (total > limit) throw FileTooLargeException()
        sink.write(buf, 0, n)
    }
    return sink.toString(Charsets.UTF_8.name())
}
