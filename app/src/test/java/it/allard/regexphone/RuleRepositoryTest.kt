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

import it.allard.regexphone.data.RuleIO
import it.allard.regexphone.data.RuleRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleRepositoryTest {

    // The keys RuleRepository stores under.
    private val rules = "rules"
    private val unreadable = "rules_unreadable"

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

    @Test
    fun intactRulesRaiseNoWarning() {
        val prefs = FakePreferences()
        prefs.edit().putString(rules, RuleIO.encode(listOf(testRule(1, "^\\+1")))).commit()

        RuleRepository.initForTest(prefs)
        assertFalse(RuleRepository.storageWarning.value)
        assertFalse(prefs.contains(unreadable))
    }
}
