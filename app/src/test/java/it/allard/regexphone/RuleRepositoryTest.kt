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
import it.allard.regexphone.data.RuleIO
import it.allard.regexphone.data.RuleRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking

class RuleRepositoryTest {

    // The keys RuleRepository stores under.
    private val rules = "rules"
    private val unreadable = "rules_unreadable"
    private val slow = "slow_patterns"

    @Test
    fun damagedRulesAreSetAsideUntilTheWarningIsDismissed() {
        val prefs = FakePreferences()
        prefs.edit().putString(rules, "not json").commit()

        RuleRepository.initForTest(prefs)
        assertTrue(RuleRepository.storageWarning.value)
        assertEquals("not json", prefs.getString(unreadable, null))

        RuleRepository.dismissStorageWarning()
        assertFalse(RuleRepository.storageWarning.value)
        assertFalse(prefs.contains(unreadable))
    }

    @Test
    fun warningComesBackWhileACopyIsStillSetAside() {
        // As after a restart: the rules were repaired and saved since, but
        // the copy was never dismissed.
        val prefs = FakePreferences()
        prefs.edit()
            .putString(rules, RuleIO.encode(listOf(testRule(1, "^\\+1"))))
            .putString(unreadable, "not json")
            .commit()

        RuleRepository.initForTest(prefs)
        assertEquals(1, RuleRepository.currentRules().size)
        assertTrue(RuleRepository.storageWarning.value)
    }

    // Blacklists [pattern] for screening the way a real timeout does, with a
    // match that outlasts the full allowance and then ends.
    private fun timeOut(pattern: String) {
        val gate = CountDownLatch(1)
        try {
            assertNull(
                RegexGuard.run(pattern, RegexGuard.DEFAULT_TIMEOUT_MS, RegexGuard.Scope.SCREENING) {
                    gate.await()
                    false
                }
            )
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun slowPatternsSurviveARestart() = runBlocking {
        val prefs = FakePreferences()
        RuleRepository.initForTest(prefs)
        assertTrue(RuleRepository.save(testRule(1, "slow-restart")))

        timeOut("slow-restart")
        RuleRepository.saveSlowPatterns()
        assertEquals(setOf("slow-restart"), RuleRepository.slowPatterns.value)
        assertEquals(setOf("slow-restart"), prefs.getStringSet(slow, null))

        // A new process starts with an empty guard and reads the set back.
        RegexGuard.forgive("slow-restart")
        RuleRepository.initForTest(prefs)
        assertTrue(RegexGuard.isPoisoned(Pattern.compile("slow-restart")))
        assertEquals(setOf("slow-restart"), RuleRepository.slowPatterns.value)

        RuleRepository.delete(1)
        Unit
    }

    @Test
    fun turningARuleOffAndOnRetriesIt() = runBlocking {
        val prefs = FakePreferences()
        RuleRepository.initForTest(prefs)
        assertTrue(RuleRepository.save(testRule(1, "slow-toggle")))
        timeOut("slow-toggle")
        RuleRepository.saveSlowPatterns()

        // Off keeps the mark, back on clears it.
        assertTrue(RuleRepository.toggleEnabled(1))
        assertEquals(setOf("slow-toggle"), prefs.getStringSet(slow, null))
        assertTrue(RuleRepository.toggleEnabled(1))
        assertEquals(emptySet<String>(), prefs.getStringSet(slow, null))
        assertEquals(emptySet<String>(), RuleRepository.slowPatterns.value)
        assertFalse(RegexGuard.isPoisoned(Pattern.compile("slow-toggle")))

        RuleRepository.delete(1)
        Unit
    }

    @Test
    fun savingOrDeletingARuleClearsItsSlowMark() = runBlocking {
        val prefs = FakePreferences()
        RuleRepository.initForTest(prefs)
        assertTrue(RuleRepository.save(testRule(1, "slow-save")))
        assertTrue(RuleRepository.save(testRule(2, "slow-delete")))
        timeOut("slow-save")
        timeOut("slow-delete")
        RuleRepository.saveSlowPatterns()
        assertEquals(setOf("slow-save", "slow-delete"), prefs.getStringSet(slow, null))

        assertTrue(RuleRepository.save(testRule(1, "slow-save")))
        assertEquals(setOf("slow-delete"), prefs.getStringSet(slow, null))
        assertTrue(RuleRepository.delete(2))
        assertEquals(emptySet<String>(), prefs.getStringSet(slow, null))
        assertFalse(RegexGuard.isPoisoned(Pattern.compile("slow-delete")))

        RuleRepository.delete(1)
        Unit
    }

    @Test
    fun intactRulesRaiseNoWarning() {
        val prefs = FakePreferences()
        prefs.edit().putString(rules, RuleIO.encode(listOf(testRule(1, "^\\+1")))).commit()

        RuleRepository.initForTest(prefs)
        assertFalse(RuleRepository.storageWarning.value)
        assertFalse(prefs.contains(unreadable))
    }
}
