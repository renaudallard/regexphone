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
import it.allard.regexphone.data.RuleIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleIOTest {

    @Test
    fun encodeDecodeRoundTrip() {
        val rules = listOf(
            testRule(1, "^\\+1", RuleAction.BLOCK),
            testRule(2, "^\\+33", RuleAction.ALLOW, skipNotification = false),
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
        val current = listOf(testRule(1, "a"), testRule(2, "b"))
        val incoming = listOf(testRule(1, "c"), testRule(2, "d"))
        val merged = RuleIO.merge(current, incoming)
        assertEquals(4, merged.size)
        assertEquals(listOf(1L, 2L, 3L, 4L), merged.map { it.id })
    }

    @Test
    fun mergeIntoEmptyStartsAtOne() {
        val merged = RuleIO.merge(emptyList(), listOf(testRule(99, "a"), testRule(100, "b")))
        assertEquals(listOf(1L, 2L), merged.map { it.id })
    }

    @Test
    fun mergePreservesNonIdFields() {
        val current = listOf(testRule(5, "x"))
        val incoming = listOf(testRule(99, "y", RuleAction.ALLOW, skipCallLog = false))
        val merged = RuleIO.merge(current, incoming)
        val added = merged.last()
        assertEquals(6L, added.id)
        assertEquals("y", added.pattern)
        assertEquals(RuleAction.ALLOW, added.action)
        assertEquals(false, added.skipCallLog)
        assertNotEquals(99L, added.id)
    }
}
