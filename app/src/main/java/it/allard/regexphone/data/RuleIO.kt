package it.allard.regexphone.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object RuleIO {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun encode(rules: List<Rule>): String = json.encodeToString(rules)

    fun decode(text: String): Result<List<Rule>> =
        runCatching { json.decodeFromString<List<Rule>>(text) }

    fun merge(current: List<Rule>, incoming: List<Rule>): List<Rule> {
        var nextId = (current.maxOfOrNull { it.id } ?: 0L) + 1L
        return current + incoming.map { it.copy(id = nextId++) }
    }
}
