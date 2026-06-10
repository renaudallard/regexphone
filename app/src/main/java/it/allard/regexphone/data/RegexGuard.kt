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
 * response deadline and letting a blocked call ring through.
 *
 * A match cannot be cancelled once started. A pattern that misses its full
 * allowance is blacklisted for the rest of the process, separately per
 * [Scope]: the live tester runs the same rules against arbitrary typed input
 * in the same process and must not blacklist a saved rule for real calls. The
 * number of watchdog threads alive at once is a hard cap enforced by
 * reservation, and an abandoned runaway is dropped to minimum priority.
 */
object RegexGuard {
    const val DEFAULT_TIMEOUT_MS = 1000L
    private const val MAX_LIVE_THREADS = 6
    private const val MAX_POISONED = 64

    /** Which blacklist a match reads and writes. */
    enum class Scope { SCREENING, TESTER }

    private val screeningPoisoned = ConcurrentHashMap.newKeySet<String>()
    private val testerPoisoned = ConcurrentHashMap.newKeySet<String>()
    private val liveThreads = AtomicInteger(0)

    /**
     * Returns whether [pattern] is found in [input], or null when the match
     * did not finish within [timeoutMs] or could not run. Callers must treat
     * null as no match.
     */
    fun find(
        pattern: Pattern,
        input: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        scope: Scope = Scope.SCREENING,
    ): Boolean? {
        if (timeoutMs <= 0) return null
        val key = pattern.pattern()
        // The tester also honors the screening blacklist so its preview
        // mirrors what the service would do; the service never consults the
        // tester's blacklist.
        if (key in screeningPoisoned) return null
        if (scope == Scope.TESTER && key in testerPoisoned) return null
        if (!reserveThread()) return null
        val task = FutureTask {
            try {
                pattern.matcher(input).find()
            } finally {
                liveThreads.decrementAndGet()
            }
        }
        val thread = Thread(task, "regex-guard").apply { isDaemon = true }
        try {
            thread.start()
        } catch (t: Throwable) {
            liveThreads.decrementAndGet()
            return null
        }
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            // The runaway cannot be stopped; stop it competing with the rest
            // of the app while it burns its core in the background.
            thread.priority = Thread.MIN_PRIORITY
            // Only blacklist a run that had its full time allowance; one cut
            // short by the caller's remaining budget proves nothing. When the
            // set is full, drop the new entry rather than wiping known ones.
            if (timeoutMs >= DEFAULT_TIMEOUT_MS) {
                val registry = if (scope == Scope.SCREENING) screeningPoisoned else testerPoisoned
                if (registry.size < MAX_POISONED) registry.add(key)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    // Reserve a slot before starting the thread so the cap is an invariant,
    // not a racy check. Healthy matches hold a slot for microseconds; only
    // abandoned runaways hold one for long.
    private fun reserveThread(): Boolean {
        while (true) {
            val current = liveThreads.get()
            if (current >= MAX_LIVE_THREADS) return false
            if (liveThreads.compareAndSet(current, current + 1)) return true
        }
    }
}
