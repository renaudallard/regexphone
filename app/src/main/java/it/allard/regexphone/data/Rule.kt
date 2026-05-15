package it.allard.regexphone.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.regex.Pattern

@Serializable
enum class RuleAction { BLOCK, ALLOW }

@Serializable
data class Rule(
    val id: Long,
    val name: String,
    val pattern: String,
    val action: RuleAction,
    val enabled: Boolean = true,
    val skipNotification: Boolean = true,
    val skipCallLog: Boolean = true,
) {
    @Transient
    private val compiled: Pattern? =
        runCatching { Pattern.compile(pattern) }.getOrNull()

    fun matches(number: String): Boolean =
        compiled?.matcher(number)?.find() == true
}

fun isValidRegex(pattern: String): Boolean =
    runCatching { Pattern.compile(pattern) }.isSuccess
