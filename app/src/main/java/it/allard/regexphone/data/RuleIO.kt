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

package it.allard.regexphone.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray

object RuleIO {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    data class DecodeOutcome(val rules: List<Rule>, val dropped: Int)

    fun encode(rules: List<Rule>): String = json.encodeToString(rules)

    fun decodeWithSummary(text: String): Result<DecodeOutcome> =
        runCatching {
            // Editors such as Windows Notepad prepend a BOM, which the JSON
            // parser rejects.
            val all = json.decodeFromString<List<Rule>>(text.removePrefix("\uFEFF"))
            val valid = all.filter { it.pattern.isNotBlank() && isValidRegex(it.pattern) }
            DecodeOutcome(valid, all.size - valid.size)
        }

    fun decode(text: String): Result<List<Rule>> =
        decodeWithSummary(text).map { it.rules }

    /**
     * Best-effort decode for damaged payloads: recovers every element that
     * still parses as a valid rule and drops the rest. Returns an empty list
     * when the text is not a JSON array at all.
     */
    fun salvage(text: String): List<Rule> {
        val elements = runCatching { json.parseToJsonElement(text).jsonArray }
            .getOrElse { return emptyList() }
        return elements
            .mapNotNull { element -> runCatching { json.decodeFromJsonElement<Rule>(element) }.getOrNull() }
            .filter { it.pattern.isNotBlank() && isValidRegex(it.pattern) }
    }

    fun merge(
        current: List<Rule>,
        incoming: List<Rule>,
        startId: Long = (current.maxOfOrNull { it.id } ?: 0L) + 1L,
    ): List<Rule> {
        var nextId = startId
        return current + incoming.map { it.copy(id = nextId++) }
    }

    fun reassignIds(rules: List<Rule>, startId: Long = 1L): List<Rule> {
        var nextId = startId
        return rules.map { it.copy(id = nextId++) }
    }
}
