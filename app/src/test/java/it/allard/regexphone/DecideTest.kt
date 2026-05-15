package it.allard.regexphone

import it.allard.regexphone.data.Rule
import it.allard.regexphone.data.RuleAction
import it.allard.regexphone.service.FilterCallScreeningService
import it.allard.regexphone.service.FilterCallScreeningService.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecideTest {

    private fun rule(
        id: Long = 1,
        pattern: String,
        action: RuleAction,
        enabled: Boolean = true,
        skipNotification: Boolean = true,
        skipCallLog: Boolean = true,
    ) = Rule(id, "r$id", pattern, action, enabled, skipNotification, skipCallLog)

    @Test
    fun emptyRulesAllowsCall() {
        assertEquals(Decision.Allow, FilterCallScreeningService.decide("+15551234567", emptyList()))
    }

    @Test
    fun blockRuleBlocksMatchingNumber() {
        val r = rule(pattern = "^\\+1", action = RuleAction.BLOCK)
        val d = FilterCallScreeningService.decide("+15551234567", listOf(r))
        assertTrue(d is Decision.Block)
        assertEquals(r, (d as Decision.Block).rule)
    }

    @Test
    fun blockRuleIgnoresNonMatchingNumber() {
        val rules = listOf(rule(pattern = "^\\+1", action = RuleAction.BLOCK))
        assertEquals(Decision.Allow, FilterCallScreeningService.decide("+33123456789", rules))
    }

    @Test
    fun allowRuleBeatsBlockRule() {
        val rules = listOf(
            rule(id = 1, pattern = "^\\+1", action = RuleAction.BLOCK),
            rule(id = 2, pattern = "^\\+15551", action = RuleAction.ALLOW),
        )
        assertEquals(Decision.Allow, FilterCallScreeningService.decide("+15551234567", rules))
    }

    @Test
    fun allowRuleOrderIndependent() {
        val rules = listOf(
            rule(id = 1, pattern = "^\\+15551", action = RuleAction.ALLOW),
            rule(id = 2, pattern = "^\\+1", action = RuleAction.BLOCK),
        )
        assertEquals(Decision.Allow, FilterCallScreeningService.decide("+15551234567", rules))
    }

    @Test
    fun disabledRulesAreIgnored() {
        val rules = listOf(rule(pattern = "^\\+1", action = RuleAction.BLOCK, enabled = false))
        assertEquals(Decision.Allow, FilterCallScreeningService.decide("+15551234567", rules))
    }

    @Test
    fun invalidRegexDoesNotMatch() {
        val rules = listOf(rule(pattern = "[", action = RuleAction.BLOCK))
        assertEquals(Decision.Allow, FilterCallScreeningService.decide("anything", rules))
    }

    @Test
    fun hiddenNumberBlockedByEmptyAnchor() {
        val r = rule(pattern = "^$", action = RuleAction.BLOCK)
        val d = FilterCallScreeningService.decide("", listOf(r))
        assertTrue(d is Decision.Block)
    }

    @Test
    fun hiddenNumberNotBlockedByDigitPattern() {
        val rules = listOf(rule(pattern = "^\\+1", action = RuleAction.BLOCK))
        assertEquals(Decision.Allow, FilterCallScreeningService.decide("", rules))
    }

    @Test
    fun ruleMatchesUsesFindNotMatches() {
        val r = rule(pattern = "555", action = RuleAction.BLOCK)
        assertTrue(r.matches("+15551234567"))
        assertFalse(r.matches("+12025550123".replace("555", "444")))
    }

    @Test
    fun blockDecisionExposesRuleFlags() {
        val r = rule(
            pattern = "^\\+1",
            action = RuleAction.BLOCK,
            skipNotification = false,
            skipCallLog = false,
        )
        val d = FilterCallScreeningService.decide("+15551234567", listOf(r)) as Decision.Block
        assertFalse(d.rule.skipNotification)
        assertFalse(d.rule.skipCallLog)
    }

    @Test
    fun firstMatchingBlockRuleWinsForFlags() {
        val a = rule(
            id = 1, pattern = "^\\+1", action = RuleAction.BLOCK,
            skipNotification = true, skipCallLog = true,
        )
        val b = rule(
            id = 2, pattern = "^\\+15", action = RuleAction.BLOCK,
            skipNotification = false, skipCallLog = false,
        )
        val d = FilterCallScreeningService.decide("+15551234567", listOf(a, b)) as Decision.Block
        assertEquals(1L, d.rule.id)
    }
}
