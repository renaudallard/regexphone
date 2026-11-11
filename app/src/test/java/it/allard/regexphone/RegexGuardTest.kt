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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    // Holds guard slots with matches that block on a latch, so a test fills
    // and frees them deterministically and leaves no runaway behind.
    private class Stuck(private val prefix: String, private val scope: RegexGuard.Scope) {
        private val gate = CountDownLatch(1)
        private var count = 0

        /** Starts one match that times out and stays stuck; false when refused outright. */
        fun add(): Boolean {
            val started = CountDownLatch(1)
            RegexGuard.run("$prefix-${count++}", 20L, scope) {
                started.countDown()
                gate.await()
                false
            }
            // A refused match never starts its body; a timed-out one has a
            // thread that reaches it shortly even on a loaded machine.
            return started.await(2, TimeUnit.SECONDS)
        }

        fun release() = gate.countDown()
    }

    private fun healthy(scope: RegexGuard.Scope): Boolean? =
        RegexGuard.find(Pattern.compile("^\\+1"), "+15551234567", scope = scope)

    // The workers release their slots a moment after the latch opens.
    private fun awaitHealthy(scope: RegexGuard.Scope) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (healthy(scope) != true) {
            assertTrue("guard did not recover", System.nanoTime() < deadline)
            Thread.sleep(10)
        }
    }

    @Test
    fun testerRunawaysCannotStarveScreening() {
        val stuck = Stuck("tester-starve", RegexGuard.Scope.TESTER)
        try {
            // Fill every tester slot: once a match is refused outright, the
            // tester has nothing left to start one with.
            var guard = 0
            while (stuck.add()) assertTrue(guard++ < 100)
            assertNull(healthy(RegexGuard.Scope.TESTER))
            assertEquals(true, healthy(RegexGuard.Scope.SCREENING))
        } finally {
            stuck.release()
            awaitHealthy(RegexGuard.Scope.TESTER)
        }
    }
}
