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

package it.allard.regexphone.data

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * Runs regex matches on watchdog threads with a deadline. java.util.regex has
 * no step limit, so a catastrophically backtracking pattern would otherwise
 * hang the caller; in the screening service that means missing the Telecom
 * response deadline and letting a blocked call ring through. A match cannot be
 * cancelled once started, so a pattern that misses its deadline is remembered
 * and never run again for the lifetime of the process, and the number of
 * abandoned matches still spinning in the background is capped.
 */
object RegexGuard {
    const val DEFAULT_TIMEOUT_MS = 1000L
    private const val MAX_STRANDED = 4

    private val poisoned = ConcurrentHashMap.newKeySet<String>()
    private val stranded = AtomicInteger(0)

    /**
     * Returns whether [pattern] is found in [input], or null when the match
     * did not finish within [timeoutMs] or failed. Callers must treat null as
     * no match.
     */
    fun find(pattern: Pattern, input: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean? {
        if (timeoutMs <= 0) return null
        if (pattern.pattern() in poisoned) return null
        if (stranded.get() >= MAX_STRANDED) return null
        // 0 = running, 1 = completed in time, 2 = abandoned by the waiter.
        val state = AtomicInteger(0)
        val task = FutureTask {
            try {
                pattern.matcher(input).find()
            } finally {
                if (!state.compareAndSet(0, 1)) stranded.decrementAndGet()
            }
        }
        Thread(task, "regex-guard").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            if (state.compareAndSet(0, 2)) stranded.incrementAndGet()
            // Only blacklist a pattern that had its full time allowance; a
            // run cut short by the caller's remaining budget proves nothing.
            if (timeoutMs >= DEFAULT_TIMEOUT_MS) poisoned.add(pattern.pattern())
            null
        } catch (_: Exception) {
            null
        }
    }
}
