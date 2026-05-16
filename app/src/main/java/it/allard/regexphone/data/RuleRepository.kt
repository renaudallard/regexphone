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

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object RuleRepository {
    private const val PREFS = "regexphone_prefs"
    private const val KEY_RULES = "rules"
    private const val KEY_LAST_ID = "last_id"

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var initialized = false

    @Volatile
    private lateinit var prefs: SharedPreferences

    @Volatile
    private var lastIssuedId: Long = 0L

    private val _rules = MutableStateFlow<List<Rule>>(emptyList())
    val rules: StateFlow<List<Rule>> = _rules.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val loaded = load()
        lastIssuedId = maxOf(
            prefs.getLong(KEY_LAST_ID, 0L),
            loaded.maxOfOrNull { it.id } ?: 0L,
        )
        _rules.value = loaded
        initialized = true
    }

    fun currentRules(): List<Rule> = _rules.value

    fun save(rule: Rule): Boolean {
        val updated = _rules.value.toMutableList().also { list ->
            val idx = list.indexOfFirst { it.id == rule.id }
            if (idx >= 0) list[idx] = rule else list.add(rule)
        }
        return persist(updated)
    }

    fun delete(id: Long): Boolean = persist(_rules.value.filter { it.id != id })

    fun toggleEnabled(id: Long): Boolean =
        persist(_rules.value.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })

    fun findById(id: Long): Rule? = _rules.value.firstOrNull { it.id == id }

    @Synchronized
    fun nextId(): Long {
        val candidate = lastIssuedId + 1L
        if (prefs.edit().putLong(KEY_LAST_ID, candidate).commit()) {
            lastIssuedId = candidate
        }
        return candidate
    }

    fun exportJson(): String = RuleIO.encode(_rules.value)

    fun importJson(text: String, replace: Boolean): Result<Int> =
        RuleIO.decode(text).mapCatching { imported ->
            val next = if (replace) {
                RuleIO.reassignIds(imported, lastIssuedId + 1L)
            } else {
                RuleIO.merge(_rules.value, imported, lastIssuedId + 1L)
            }
            if (!persist(next)) error("Could not save imported rules")
            imported.size
        }

    @Synchronized
    private fun persist(list: List<Rule>): Boolean {
        val newMax = list.maxOfOrNull { it.id } ?: 0L
        val nextLastId = maxOf(newMax, lastIssuedId)
        val ok = prefs.edit()
            .putString(KEY_RULES, json.encodeToString(list))
            .putLong(KEY_LAST_ID, nextLastId)
            .commit()
        if (ok) {
            lastIssuedId = nextLastId
            _rules.value = list
        }
        return ok
    }

    private fun load(): List<Rule> {
        val text = prefs.getString(KEY_RULES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<Rule>>(text) }
            .getOrElse { emptyList() }
    }
}
