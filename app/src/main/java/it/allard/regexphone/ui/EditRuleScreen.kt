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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.allard.regexphone.data.Rule
import it.allard.regexphone.data.RuleAction
import it.allard.regexphone.data.RuleRepository
import it.allard.regexphone.data.isValidRegex
import it.allard.regexphone.data.regexFinds
import it.allard.regexphone.service.FilterCallScreeningService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditRuleScreen(
    ruleId: Long?,
    onDone: () -> Unit,
) {
    val existing = remember(ruleId) { ruleId?.let { RuleRepository.findById(it) } }

    if (ruleId != null && existing == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }

    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var pattern by rememberSaveable { mutableStateOf(existing?.pattern ?: "") }
    var action by rememberSaveable { mutableStateOf(existing?.action ?: RuleAction.BLOCK) }
    var enabled by rememberSaveable { mutableStateOf(existing?.enabled ?: true) }
    var skipNotification by rememberSaveable { mutableStateOf(existing?.skipNotification ?: true) }
    var testNumber by rememberSaveable { mutableStateOf("") }

    val trimmedPattern = pattern.trim()
    val patternValid = remember(trimmedPattern) {
        trimmedPattern.isNotEmpty() && isValidRegex(trimmedPattern)
    }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val canSave = patternValid && !saving
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val dirty = if (existing == null) {
        name.isNotBlank() || pattern.isNotBlank() || action != RuleAction.BLOCK ||
            !enabled || !skipNotification
    } else {
        name != existing.name || pattern != existing.pattern || action != existing.action ||
            enabled != existing.enabled || skipNotification != existing.skipNotification
    }
    val requestClose = {
        if (dirty) confirmDiscard = true else onDone()
    }
    BackHandler(enabled = dirty) { confirmDiscard = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "New rule" else "Edit rule") },
                navigationIcon = {
                    IconButton(onClick = requestClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (existing != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                    TextButton(
                        enabled = canSave,
                        onClick = onSave@{
                            if (saving) return@onSave
                            saving = true
                            val rule = Rule(
                                id = existing?.id ?: RuleRepository.nextId(),
                                name = name.trim(),
                                pattern = trimmedPattern,
                                action = action,
                                enabled = enabled,
                                skipNotification = skipNotification,
                            )
                            scope.launch {
                                if (RuleRepository.save(rule)) {
                                    onDone()
                                } else {
                                    saving = false
                                    snackbar.showSnackbar("Could not save rule")
                                }
                            }
                        },
                    ) { Text("Save") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = pattern,
                onValueChange = { pattern = it },
                label = { Text("Regex pattern") },
                singleLine = true,
                isError = pattern.isNotBlank() && !patternValid,
                supportingText = {
                    when {
                        pattern.isBlank() ->
                            Text("Required. Matched with find(); anchor with ^ and \$ for whole-number match.")
                        !patternValid ->
                            Text("Invalid regular expression")
                        pattern != trimmedPattern ->
                            Text("Leading or trailing whitespace will be trimmed on save.")
                    }
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            Text("Action", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                RuleAction.entries.forEachIndexed { index, value ->
                    SegmentedButton(
                        selected = action == value,
                        onClick = { action = value },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = RuleAction.entries.size,
                        ),
                    ) { Text(value.name) }
                }
            }
            Spacer(Modifier.height(16.dp))

            SwitchRow(label = "Enabled", checked = enabled, onChange = { enabled = it })

            if (action == RuleAction.BLOCK) {
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    label = "Skip notification",
                    sublabel = "Hide the missed-call notification when blocked",
                    checked = skipNotification,
                    onChange = { skipNotification = it },
                )
            }
            Spacer(Modifier.height(24.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Test against a number",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = testNumber,
                        onValueChange = { testNumber = it },
                        label = { Text("Phone number") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))

                    // Preview the decision the service would take, across all
                    // enabled rules with the current edits applied, not just
                    // the rule being edited.
                    val allRules by RuleRepository.rules.collectAsStateWithLifecycle()
                    val editedId = existing?.id ?: 0L
                    val preview by produceState<TesterPreview?>(
                        initialValue = null,
                        trimmedPattern, testNumber, patternValid, name,
                        action, enabled, skipNotification, allRules,
                    ) {
                        value = if (!patternValid || testNumber.isBlank()) null
                        else withContext(Dispatchers.Default) {
                            val edited = Rule(
                                id = editedId,
                                name = name.trim(),
                                pattern = trimmedPattern,
                                action = action,
                                enabled = enabled,
                                skipNotification = skipNotification,
                            )
                            val rules =
                                if (allRules.any { it.id == editedId }) {
                                    allRules.map { if (it.id == editedId) edited else it }
                                } else {
                                    allRules + edited
                                }
                            TesterPreview(
                                editedTimedOut = regexFinds(trimmedPattern, testNumber) == null,
                                decision = FilterCallScreeningService.decide(testNumber, rules),
                            )
                        }
                    }
                    Text(
                        text = when {
                            !patternValid -> "Enter a valid pattern to test."
                            testNumber.isBlank() -> "Enter a number to test."
                            else -> testerVerdict(preview, editedId)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Unsaved changes to this rule will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDone()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            },
        )
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete rule?") },
            text = { Text("This will permanently remove the rule.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        if (RuleRepository.delete(existing.id)) {
                            onDone()
                        } else {
                            snackbar.showSnackbar("Could not delete rule")
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

private data class TesterPreview(
    val editedTimedOut: Boolean,
    val decision: FilterCallScreeningService.Decision,
)

private fun testerVerdict(preview: TesterPreview?, editedId: Long): String {
    if (preview == null) return "Evaluating…"
    val prefix =
        if (preview.editedTimedOut) "Pattern took too long, treated as no match. " else ""
    fun byWhom(rule: Rule): String =
        if (rule.id == editedId) "this rule" else "rule '${rule.name.ifBlank { "(unnamed)" }}'"
    return prefix + when (val d = preview.decision) {
        is FilterCallScreeningService.Decision.Allow ->
            if (d.rule == null) "No rule matches → ALLOW"
            else "Match on ${byWhom(d.rule)} → ALLOW"
        is FilterCallScreeningService.Decision.Block -> {
            val flag = if (d.rule.skipNotification) "silent notif" else "notif shown"
            "Match on ${byWhom(d.rule)} → BLOCK ($flag)"
        }
        is FilterCallScreeningService.Decision.Silence ->
            "Match on ${byWhom(d.rule)} → SILENCE (ringtone muted, call still logged and notified)"
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    sublabel: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (sublabel != null) {
                Text(
                    sublabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

