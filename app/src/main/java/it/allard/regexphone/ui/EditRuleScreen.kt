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

import android.telephony.TelephonyManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.allard.regexphone.R
import it.allard.regexphone.data.RegexGuard
import it.allard.regexphone.data.Rule
import it.allard.regexphone.data.RuleAction
import it.allard.regexphone.data.RuleRepository
import it.allard.regexphone.data.isValidRegex
import it.allard.regexphone.data.regexFinds
import it.allard.regexphone.service.FilterCallScreeningService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    // The id a new rule will be saved under, reserved on the first save and
    // kept across recreation so a save torn apart by a configuration change
    // overwrites its own first copy instead of adding a duplicate.
    var assignedId by rememberSaveable { mutableStateOf(existing?.id ?: -1L) }

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
    val ctx = LocalContext.current

    // The saved rule and the tester preview must stay identical, so build the
    // edited rule from the current fields in one place. Only the id differs.
    fun buildRule(id: Long) = Rule(
        id = id,
        name = name.trim(),
        pattern = trimmedPattern,
        action = action,
        enabled = enabled,
        skipNotification = skipNotification,
    )

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
                title = {
                    Text(stringResource(if (existing == null) R.string.new_rule else R.string.edit_rule))
                },
                navigationIcon = {
                    IconButton(onClick = requestClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    if (existing != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }
                    TextButton(
                        enabled = canSave,
                        onClick = onSave@{
                            if (saving) return@onSave
                            saving = true
                            if (assignedId < 0) assignedId = RuleRepository.nextId()
                            val rule = buildRule(assignedId)
                            scope.launch {
                                val ok = RuleRepository.save(rule)
                                saving = false
                                if (ok) {
                                    onDone()
                                } else {
                                    snackbar.showSnackbar(ctx.getString(R.string.save_rule_failed))
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.save)) }
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
                label = { Text(stringResource(R.string.field_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = pattern,
                onValueChange = { pattern = it },
                label = { Text(stringResource(R.string.field_pattern_label)) },
                singleLine = true,
                isError = pattern.isNotBlank() && !patternValid,
                supportingText = {
                    when {
                        pattern.isBlank() ->
                            Text(stringResource(R.string.pattern_hint_required))
                        !patternValid ->
                            Text(stringResource(R.string.pattern_invalid))
                        pattern != trimmedPattern ->
                            Text(stringResource(R.string.pattern_whitespace_hint))
                    }
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            Text(stringResource(R.string.action_label), style = MaterialTheme.typography.labelLarge)
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
                    ) { Text(actionLabel(value)) }
                }
            }
            Spacer(Modifier.height(16.dp))

            SwitchRow(
                label = stringResource(R.string.enabled_label),
                checked = enabled,
                onChange = { enabled = it },
            )

            if (action == RuleAction.BLOCK) {
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    label = stringResource(R.string.skip_notification_label),
                    sublabel = stringResource(R.string.skip_notification_sublabel),
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
                        stringResource(R.string.tester_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = testNumber,
                        onValueChange = { testNumber = it },
                        label = { Text(stringResource(R.string.tester_field_label)) },
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
                    val countryIso = remember {
                        FilterCallScreeningService.countryIso(
                            ctx.getSystemService(TelephonyManager::class.java)
                        )
                    }
                    val preview by produceState<TesterPreview?>(
                        initialValue = null,
                        trimmedPattern, testNumber, patternValid, name,
                        action, enabled, skipNotification, allRules,
                    ) {
                        // Reset to the evaluating state on every input change so
                        // the debounce window does not keep showing the previous
                        // number's verdict as if it were current.
                        value = null
                        if (patternValid && testNumber.isNotBlank()) {
                            // Debounce so intermediate keystrokes do not each
                            // burn a watchdog evaluation.
                            delay(250)
                            value = withContext(Dispatchers.Default) {
                                val edited = buildRule(editedId)
                                val rules =
                                    if (allRules.any { it.id == editedId }) {
                                        allRules.map { if (it.id == editedId) edited else it }
                                    } else {
                                        allRules + edited
                                    }
                                val candidates =
                                    FilterCallScreeningService.candidateNumbers(testNumber, countryIso)
                                TesterPreview(
                                    editedTimedOut = candidates.any { regexFinds(trimmedPattern, it) == null },
                                    decision = FilterCallScreeningService.decide(
                                        candidates,
                                        rules,
                                        RegexGuard.Scope.TESTER,
                                    ),
                                )
                            }
                        }
                    }
                    Text(
                        text = when {
                            !patternValid -> stringResource(R.string.tester_need_pattern)
                            testNumber.isBlank() -> stringResource(R.string.tester_need_number)
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
            title = { Text(stringResource(R.string.discard_title)) },
            text = { Text(stringResource(R.string.discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDone()
                }) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.keep_editing))
                }
            },
        )
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_dialog_title)) },
            text = { Text(stringResource(R.string.delete_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        if (RuleRepository.delete(existing.id)) {
                            onDone()
                        } else {
                            snackbar.showSnackbar(ctx.getString(R.string.delete_failed))
                        }
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

private data class TesterPreview(
    val editedTimedOut: Boolean,
    val decision: FilterCallScreeningService.Decision,
)

@Composable
private fun testerVerdict(preview: TesterPreview?, editedId: Long): String {
    if (preview == null) return stringResource(R.string.tester_evaluating)
    val prefix =
        if (preview.editedTimedOut) stringResource(R.string.tester_timeout_prefix) else ""

    @Composable
    fun byWhom(rule: Rule): String =
        if (rule.id == editedId) {
            stringResource(R.string.tester_this_rule)
        } else {
            stringResource(
                R.string.tester_rule_name,
                rule.name.ifBlank { stringResource(R.string.rule_unnamed) },
            )
        }

    return prefix + when (val d = preview.decision) {
        is FilterCallScreeningService.Decision.Allow ->
            if (d.rule == null) stringResource(R.string.tester_no_match)
            else stringResource(R.string.tester_allow, byWhom(d.rule))
        is FilterCallScreeningService.Decision.Block ->
            stringResource(
                R.string.tester_block,
                byWhom(d.rule),
                stringResource(
                    if (d.rule.skipNotification) R.string.tester_flag_hidden
                    else R.string.tester_flag_shown
                ),
            )
        is FilterCallScreeningService.Decision.Silence ->
            stringResource(R.string.tester_silence, byWhom(d.rule))
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
        modifier = Modifier
            .fillMaxWidth()
            // Make the whole row one toggleable control so the label and its
            // sublabel are announced together with the switch state, and the
            // tap target spans the row. The switch itself stays read-only.
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch),
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
        Switch(checked = checked, onCheckedChange = null)
    }
}

