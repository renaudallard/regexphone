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

    @Test
    fun silenceRuleSilencesMatchingNumber() {
        val r = rule(pattern = "^\\+1", action = RuleAction.SILENCE)
        val d = FilterCallScreeningService.decide("+15551234567", listOf(r))
        assertTrue(d is Decision.Silence)
        assertEquals(r, (d as Decision.Silence).rule)
    }

    @Test
    fun blockBeatsSilence() {
        val rules = listOf(
            rule(id = 1, pattern = "^\\+1", action = RuleAction.SILENCE),
            rule(id = 2, pattern = "^\\+155", action = RuleAction.BLOCK),
        )
        val d = FilterCallScreeningService.decide("+15551234567", rules)
        assertTrue(d is Decision.Block)
        assertEquals(2L, (d as Decision.Block).rule.id)
    }

    @Test
    fun allowBeatsSilence() {
        val rules = listOf(
            rule(id = 1, pattern = "^\\+1", action = RuleAction.SILENCE),
            rule(id = 2, pattern = "^\\+155", action = RuleAction.ALLOW),
        )
        assertEquals(Decision.Allow, FilterCallScreeningService.decide("+15551234567", rules))
    }
}
