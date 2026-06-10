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

object RuleRepository {
    private const val PREFS = "regexphone_prefs"
    private const val KEY_RULES = "rules"
    private const val KEY_LAST_ID = "last_id"
    private const val KEY_RULES_UNREADABLE = "rules_unreadable"

    private var initialized = false

    private lateinit var prefs: SharedPreferences

    private var lastIssuedId: Long = 0L

    private val _rules = MutableStateFlow<List<Rule>>(emptyList())
    val rules: StateFlow<List<Rule>> = _rules.asStateFlow()

    private val _storageWarning = MutableStateFlow(false)
    val storageWarning: StateFlow<Boolean> = _storageWarning.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        // Device-protected storage is readable before the first unlock, so
        // the screening service can filter calls right after a reboot. Fall
        // back to credential-encrypted storage if the migration fails, to
        // avoid losing existing rules.
        val appContext = context.applicationContext
        val deviceContext = appContext.createDeviceProtectedStorageContext()
        val storageContext =
            if (deviceContext.moveSharedPreferencesFrom(appContext, PREFS)) deviceContext
            else appContext
        prefs = storageContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val loaded = load()
        lastIssuedId = maxOf(
            prefs.getLong(KEY_LAST_ID, 0L),
            loaded.maxOfOrNull { it.id } ?: 0L,
        )
        _rules.value = loaded
        initialized = true
    }

    fun currentRules(): List<Rule> = _rules.value

    fun save(rule: Rule): Boolean = persist { current, _ ->
        current.toMutableList().also { list ->
            val idx = list.indexOfFirst { it.id == rule.id }
            if (idx >= 0) list[idx] = rule else list.add(rule)
        }
    }

    fun delete(id: Long): Boolean = persist { current, _ ->
        current.filter { it.id != id }
    }

    fun restoreAt(rule: Rule, index: Int): Boolean = persist { current, _ ->
        if (current.any { it.id == rule.id }) current
        else current.toMutableList().also {
            it.add(index.coerceIn(0, it.size), rule)
        }
    }

    fun toggleEnabled(id: Long): Boolean = persist { current, _ ->
        current.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }
    }

    fun findById(id: Long): Rule? = _rules.value.firstOrNull { it.id == id }

    @Synchronized
    fun nextId(): Long {
        lastIssuedId += 1L
        return lastIssuedId
    }

    fun dismissStorageWarning() {
        _storageWarning.value = false
    }

    fun exportJson(): String = RuleIO.encode(_rules.value)

    fun importRules(imported: List<Rule>, replace: Boolean): Boolean =
        persist { current, lastId ->
            if (replace) {
                RuleIO.reassignIds(imported, lastId + 1L)
            } else {
                RuleIO.merge(current, imported, lastId + 1L)
            }
        }

    @Synchronized
    private fun persist(transform: (current: List<Rule>, lastId: Long) -> List<Rule>): Boolean {
        val newList = transform(_rules.value, lastIssuedId)
        val newMax = newList.maxOfOrNull { it.id } ?: 0L
        val nextLastId = maxOf(newMax, lastIssuedId)
        val ok = prefs.edit()
            .putString(KEY_RULES, RuleIO.encode(newList))
            .putLong(KEY_LAST_ID, nextLastId)
            .commit()
        if (ok) {
            lastIssuedId = nextLastId
            _rules.value = newList
        }
        return ok
    }

    private fun load(): List<Rule> {
        val text = prefs.getString(KEY_RULES, null) ?: return emptyList()
        return RuleIO.decode(text).getOrElse {
            // Keep the unreadable payload aside so the next save cannot
            // overwrite it, then recover whatever still parses.
            prefs.edit().putString(KEY_RULES_UNREADABLE, text).commit()
            _storageWarning.value = true
            RuleIO.salvage(text).distinctBy { rule -> rule.id }
        }
    }
}
