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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import it.allard.regexphone.data.Rule
import it.allard.regexphone.data.RuleAction
import it.allard.regexphone.data.RuleRepository
import it.allard.regexphone.data.isValidRegex

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditRuleScreen(
    ruleId: Long?,
    onDone: () -> Unit,
) {
    val existing = remember(ruleId) { ruleId?.let { RuleRepository.findById(it) } }

    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var pattern by rememberSaveable { mutableStateOf(existing?.pattern ?: "") }
    var action by rememberSaveable { mutableStateOf(existing?.action ?: RuleAction.BLOCK) }
    var enabled by rememberSaveable { mutableStateOf(existing?.enabled ?: true) }
    var skipNotification by rememberSaveable { mutableStateOf(existing?.skipNotification ?: true) }
    var skipCallLog by rememberSaveable { mutableStateOf(existing?.skipCallLog ?: true) }
    var testNumber by rememberSaveable { mutableStateOf("") }

    val patternValid = pattern.isNotBlank() && isValidRegex(pattern)
    val canSave = patternValid

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "New rule" else "Edit rule") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (existing != null) {
                        IconButton(onClick = {
                            RuleRepository.delete(existing.id)
                            onDone()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                    TextButton(
                        enabled = canSave,
                        onClick = {
                            val rule = Rule(
                                id = existing?.id ?: RuleRepository.nextId(),
                                name = name.trim(),
                                pattern = pattern,
                                action = action,
                                enabled = enabled,
                                skipNotification = skipNotification,
                                skipCallLog = skipCallLog,
                            )
                            RuleRepository.save(rule)
                            onDone()
                        },
                    ) { Text("Save") }
                },
            )
        },
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
                    if (pattern.isBlank()) {
                        Text("Required. Matched with find(); anchor with ^ and \$ for whole-number match.")
                    } else if (!patternValid) {
                        Text("Invalid regular expression")
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
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    label = "Skip call log",
                    sublabel = "Don't record the blocked call in the call log",
                    checked = skipCallLog,
                    onChange = { skipCallLog = it },
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

                    val patternMatches = patternValid && draftMatches(pattern, testNumber)
                    Text(
                        text = when {
                            !patternValid -> "Enter a valid pattern to test."
                            testNumber.isBlank() -> "Enter a number to test."
                            !patternMatches -> "No match → ALLOW"
                            !enabled -> "Match, but rule is disabled → ALLOW"
                            action == RuleAction.ALLOW -> "Match → ALLOW"
                            action == RuleAction.SILENCE -> "Match → SILENCE (ringtone muted, call still logged and notified)"
                            else -> {
                                val flags = listOf(
                                    if (skipNotification) "silent notif" else "notif shown",
                                    if (skipCallLog) "not logged" else "logged",
                                ).joinToString(", ")
                                "Match → BLOCK ($flags)"
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
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

private fun draftMatches(pattern: String, number: String): Boolean =
    runCatching { java.util.regex.Pattern.compile(pattern).matcher(number).find() }
        .getOrDefault(false)
