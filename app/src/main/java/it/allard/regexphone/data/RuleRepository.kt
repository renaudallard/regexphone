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

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var initialized = false
    private lateinit var prefs: SharedPreferences

    @Volatile
    private var cache: List<Rule> = emptyList()

    private val _rules = MutableStateFlow<List<Rule>>(emptyList())
    val rules: StateFlow<List<Rule>> = _rules.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        cache = load()
        _rules.value = cache
        initialized = true
    }

    fun currentRules(): List<Rule> = cache

    fun save(rule: Rule) {
        val updated = cache.toMutableList().also { list ->
            val idx = list.indexOfFirst { it.id == rule.id }
            if (idx >= 0) list[idx] = rule else list.add(rule)
        }
        persist(updated)
    }

    fun delete(id: Long) {
        persist(cache.filter { it.id != id })
    }

    fun toggleEnabled(id: Long) {
        persist(cache.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
    }

    fun findById(id: Long): Rule? = cache.firstOrNull { it.id == id }

    fun nextId(): Long = (cache.maxOfOrNull { it.id } ?: 0L) + 1L

    private fun persist(list: List<Rule>) {
        prefs.edit().putString(KEY_RULES, json.encodeToString(list)).commit()
        cache = list
        _rules.value = list
    }

    private fun load(): List<Rule> {
        val text = prefs.getString(KEY_RULES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<Rule>>(text) }
            .getOrElse { emptyList() }
    }
}
