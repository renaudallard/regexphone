package it.allard.regexphone

import it.allard.regexphone.data.Rule
import it.allard.regexphone.data.RuleAction
import it.allard.regexphone.data.RuleIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleIOTest {

    private fun rule(
        id: Long,
        pattern: String,
        action: RuleAction = RuleAction.BLOCK,
        skipNotification: Boolean = true,
        skipCallLog: Boolean = true,
    ) = Rule(id, "r$id", pattern, action, true, skipNotification, skipCallLog)

    @Test
    fun encodeDecodeRoundTrip() {
        val rules = listOf(
            rule(1, "^\\+1", RuleAction.BLOCK),
            rule(2, "^\\+33", RuleAction.ALLOW, skipNotification = false),
        )
        val text = RuleIO.encode(rules)
        val decoded = RuleIO.decode(text).getOrThrow()
        assertEquals(rules.size, decoded.size)
        assertEquals(rules[0].pattern, decoded[0].pattern)
        assertEquals(rules[1].action, decoded[1].action)
        assertEquals(false, decoded[1].skipNotification)
    }

    @Test
    fun decodeInvalidJsonReturnsFailure() {
        val result = RuleIO.decode("not json")
        assertTrue(result.isFailure)
    }

    @Test
    fun decodeEmptyArrayIsEmpty() {
        assertEquals(emptyList<Rule>(), RuleIO.decode("[]").getOrThrow())
    }

    @Test
    fun mergeAssignsFreshIds() {
        val current = listOf(rule(1, "a"), rule(2, "b"))
        val incoming = listOf(rule(1, "c"), rule(2, "d"))
        val merged = RuleIO.merge(current, incoming)
        assertEquals(4, merged.size)
        assertEquals(listOf(1L, 2L, 3L, 4L), merged.map { it.id })
    }

    @Test
    fun mergeIntoEmptyStartsAtOne() {
        val merged = RuleIO.merge(emptyList(), listOf(rule(99, "a"), rule(100, "b")))
        assertEquals(listOf(1L, 2L), merged.map { it.id })
    }

    @Test
    fun mergePreservesNonIdFields() {
        val current = listOf(rule(5, "x"))
        val incoming = listOf(rule(99, "y", RuleAction.ALLOW, skipCallLog = false))
        val merged = RuleIO.merge(current, incoming)
        val added = merged.last()
        assertEquals(6L, added.id)
        assertEquals("y", added.pattern)
        assertEquals(RuleAction.ALLOW, added.action)
        assertEquals(false, added.skipCallLog)
        assertNotEquals(99L, added.id)
    }
}
