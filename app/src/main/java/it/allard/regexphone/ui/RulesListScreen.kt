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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.allard.regexphone.data.Rule
import it.allard.regexphone.data.RuleAction
import it.allard.regexphone.data.RuleIO
import it.allard.regexphone.data.RuleRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesListScreen(
    onAddRule: () -> Unit,
    onEditRule: (Long) -> Unit,
) {
    val ctx = LocalContext.current
    val rules by RuleRepository.rules.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var menuOpen by remember { mutableStateOf(false) }
    var pendingImportText by rememberSaveable(stateSaver = SafeImportTextSaver) {
        mutableStateOf<String?>(null)
    }
    var pendingImportCount by rememberSaveable { mutableStateOf(0) }
    var pendingImportDropped by rememberSaveable { mutableStateOf(0) }

    val exportLauncher = rememberLauncherForActivityResult(CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(RuleRepository.exportJson())
            }
        }.isSuccess
        scope.launch {
            snackbar.showSnackbar(
                if (ok) "Exported ${rules.size} rule(s)" else "Export failed"
            )
        }
    }

    val importLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text == null) {
            scope.launch { snackbar.showSnackbar("Could not read file") }
            return@rememberLauncherForActivityResult
        }
        RuleIO.decodeWithSummary(text)
            .onSuccess { outcome ->
                when {
                    outcome.rules.isEmpty() -> {
                        scope.launch {
                            snackbar.showSnackbar(
                                if (outcome.dropped > 0) "Nothing to import (${outcome.dropped} invalid)"
                                else "Nothing to import"
                            )
                        }
                    }
                    rules.isEmpty() -> {
                        RuleRepository.importJson(text, replace = true)
                        scope.launch {
                            snackbar.showSnackbar(importSummary("Imported", outcome.rules.size, outcome.dropped))
                        }
                    }
                    else -> {
                        pendingImportText = text
                        pendingImportCount = outcome.rules.size
                        pendingImportDropped = outcome.dropped
                    }
                }
            }
            .onFailure {
                scope.launch { snackbar.showSnackbar("Not a valid rules file") }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RegexPhone") },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Export rules") },
                                enabled = rules.isNotEmpty(),
                                onClick = {
                                    menuOpen = false
                                    exportLauncher.launch("regexphone-rules.json")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Import rules") },
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
                Icon(Icons.Filled.Add, contentDescription = "Add rule")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            RoleCard()
            HorizontalDivider()
            if (rules.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(rules, key = { it.id }) { rule ->
                        RuleRow(
                            rule = rule,
                            onToggle = { RuleRepository.toggleEnabled(rule.id) },
                            onEdit = { onEditRule(rule.id) },
                            onDelete = {
                                val deleted = rule
                                RuleRepository.delete(deleted.id)
                                scope.launch {
                                    val result = snackbar.showSnackbar(
                                        message = "Rule deleted",
                                        actionLabel = "Undo",
                                        duration = SnackbarDuration.Short,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        RuleRepository.save(deleted)
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
        val dropSuffix = if (pendingDropped > 0) " ($pendingDropped invalid will be skipped)" else ""
        AlertDialog(
            onDismissRequest = clear,
            title = { Text("Import rules") },
            text = {
                Text(
                    "You have ${rules.size} existing rule(s) and the file contains " +
                        "$pendingCount$dropSuffix. Replace everything, or merge?"
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        RuleRepository.importJson(pendingText, replace = false)
                        clear()
                        scope.launch {
                            snackbar.showSnackbar(importSummary("Merged", pendingCount, pendingDropped))
                        }
                    }) { Text("Merge") }
                    TextButton(onClick = {
                        RuleRepository.importJson(pendingText, replace = true)
                        clear()
                        scope.launch {
                            snackbar.showSnackbar(importSummary("Replaced with", pendingCount, pendingDropped))
                        }
                    }) { Text("Replace") }
                }
            },
            dismissButton = {
                TextButton(onClick = clear) { Text("Cancel") }
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
                text = if (roleHeld) "Active as call-screening app"
                else "Not the default call-screening app",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (roleHeld) {
                    "Incoming calls will be checked against the rules below."
                } else {
                    "Rules below have no effect until this app is set as the default."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (roleHeld) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Android skips screening for callers saved in your contacts, so rules can't block a known contact. Delete the contact first if you need a regex to apply.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    val rm = ctx.getSystemService(RoleManager::class.java)
                    val intent = rm?.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
                    if (intent != null) launcher.launch(intent)
                }) {
                    Text("Set as default")
                }
            }
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
                text = rule.name.ifBlank { "(unnamed)" },
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
            Icon(Icons.Filled.Edit, contentDescription = "Edit")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete")
        }
    }
}

@Composable
private fun ActionChip(action: RuleAction) {
    val (label, color) = when (action) {
        RuleAction.BLOCK -> "BLOCK" to MaterialTheme.colorScheme.errorContainer
        RuleAction.SILENCE -> "SILENCE" to MaterialTheme.colorScheme.surfaceVariant
        RuleAction.ALLOW -> "ALLOW" to MaterialTheme.colorScheme.tertiaryContainer
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(label) },
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
            Text("No rules yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap + to add a regex rule, or Import from the menu.",
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

private fun importSummary(verb: String, count: Int, dropped: Int): String {
    val base = "$verb $count rule(s)"
    return if (dropped > 0) "$base ($dropped skipped)" else base
}

private const val MAX_SAVED_IMPORT_CHARS = 200_000

private val SafeImportTextSaver: Saver<String?, String> = Saver(
    save = { value -> value?.takeIf { it.length <= MAX_SAVED_IMPORT_CHARS } },
    restore = { it },
)
