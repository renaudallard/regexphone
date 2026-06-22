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

import it.allard.regexphone.data.RegexGuard
import it.allard.regexphone.data.RuleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

class RegexGuardTest {

    @Test
    fun normalPatternMatches() {
        assertEquals(true, RegexGuard.find(Pattern.compile("^\\+1"), "+15551234567"))
        assertEquals(false, RegexGuard.find(Pattern.compile("^\\+44"), "+15551234567"))
    }

    // (.*\d){12}x backtracks exponentially on a digits-only input; the
    // textbook (a+)+b is optimized away by modern OpenJDK and stays fast.
    // The full default allowance is used because a shorter, budget-cut run
    // does not blacklist the pattern.
    @Test
    fun catastrophicPatternTimesOutAndIsPoisoned() {
        val evil = Pattern.compile("(.*\\d){12}x")
        val input = "1".repeat(28)
        assertFalse(RegexGuard.isPoisoned(evil))
        assertNull(RegexGuard.find(evil, input, RegexGuard.DEFAULT_TIMEOUT_MS))
        // A run that used its full allowance and still timed out is
        // blacklisted, so a later call is rejected from the set without
        // starting another match.
        assertTrue(RegexGuard.isPoisoned(evil))
        assertNull(RegexGuard.find(evil, input, RegexGuard.DEFAULT_TIMEOUT_MS))
    }

    @Test
    fun timedOutPatternNeverMatchesARule() {
        val rule = testRule(pattern = "(.*\\d){12}y", action = RuleAction.BLOCK)
        assertFalse(rule.matches("2".repeat(28)))
    }

    @Test
    fun testerTimeoutDoesNotBlacklistForScreening() {
        val evil = Pattern.compile("(.*\\d){12}w")
        val input = "4".repeat(28)
        assertNull(RegexGuard.find(evil, input, RegexGuard.DEFAULT_TIMEOUT_MS, RegexGuard.Scope.TESTER))
        // A tester timeout blacklists the pattern for the tester scope only:
        // the screening path must still admit it so a saved rule keeps
        // blocking real calls, while the tester rejects it from now on.
        assertTrue(RegexGuard.isPoisoned(evil, RegexGuard.Scope.TESTER))
        assertFalse(RegexGuard.isPoisoned(evil, RegexGuard.Scope.SCREENING))
    }
}
