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

import androidx.annotation.VisibleForTesting
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
 * in the same process and must not blacklist a saved rule for real calls.
 *
 * How many matches run at once is a hard cap enforced by reservation. A
 * runaway that misses its deadline is dropped to minimum priority and
 * abandoned: it releases its live slot so a fresh match is never blocked by an
 * un-stoppable one, and is instead accounted against a separate, larger cap on
 * background burners. That cap, not the live cap, is what a crafted set of
 * distinct catastrophic patterns can fill, and only at the cost of extra
 * background CPU, never by silencing the screener.
 */
object RegexGuard {
    const val DEFAULT_TIMEOUT_MS = 1000L
    private const val MAX_LIVE_THREADS = 6
    private const val MAX_ABANDONED_THREADS = 16
    private const val MAX_POISONED = 64

    // Slot state for one match, advanced by whichever of the two racing
    // parties acts first: the worker thread when the match finishes, or the
    // caller at the deadline. CAS makes each transition happen exactly once,
    // so a slot is released exactly once.
    private const val STATE_LIVE = 0
    private const val STATE_ABANDONED = 1
    private const val STATE_DONE = 2

    /** Which blacklist a match reads and writes. */
    enum class Scope { SCREENING, TESTER }

    private val screeningPoisoned = ConcurrentHashMap.newKeySet<String>()
    private val testerPoisoned = ConcurrentHashMap.newKeySet<String>()
    // Matches we are actively waiting on. The reservation bounds concurrency;
    // the slot is released the moment we stop waiting, whether the match
    // finished or was abandoned at the deadline.
    private val liveThreads = AtomicInteger(0)
    // Abandoned runaways still burning a core past their deadline. They no
    // longer hold a live slot, so they cannot block a fresh match; this
    // separate cap bounds how many may pile up under a crafted-rules attack.
    private val abandonedThreads = AtomicInteger(0)

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
        if (!tryReserve(liveThreads, MAX_LIVE_THREADS)) return null
        val state = AtomicInteger(STATE_LIVE)
        val task = FutureTask {
            try {
                pattern.matcher(input).find()
            } finally {
                when {
                    state.compareAndSet(STATE_LIVE, STATE_DONE) -> liveThreads.decrementAndGet()
                    state.compareAndSet(STATE_ABANDONED, STATE_DONE) -> abandonedThreads.decrementAndGet()
                }
            }
        }
        val thread = Thread(task, "regex-guard").apply { isDaemon = true }
        try {
            thread.start()
        } catch (t: Throwable) {
            if (state.compareAndSet(STATE_LIVE, STATE_DONE)) liveThreads.decrementAndGet()
            return null
        }
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            // The runaway cannot be stopped; drop it to minimum priority and
            // abandon it so it stops holding a live slot while it burns its
            // core in the background.
            thread.priority = Thread.MIN_PRIORITY
            abandon(state)
            // Only blacklist a run that had its full time allowance; one cut
            // short by the caller's remaining budget proves nothing. When the
            // set is full, drop the new entry rather than wiping known ones;
            // the lock keeps the cap exact under concurrent timeouts.
            if (timeoutMs >= DEFAULT_TIMEOUT_MS) {
                val registry = if (scope == Scope.SCREENING) screeningPoisoned else testerPoisoned
                synchronized(registry) {
                    if (registry.size < MAX_POISONED) registry.add(key)
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Whether [pattern] is currently blacklisted for [scope], mirroring the
     * fast-fail gate in [find]. Lets a test assert the blacklist by state
     * rather than by timing how fast a rejected match returns.
     */
    @VisibleForTesting
    fun isPoisoned(pattern: Pattern, scope: Scope = Scope.SCREENING): Boolean {
        val key = pattern.pattern()
        return key in screeningPoisoned || (scope == Scope.TESTER && key in testerPoisoned)
    }

    // Move a timed-out match from its live slot to an abandoned one so a fresh
    // match can take the freed live slot. A no-op if the worker already
    // finished and released the slot, or if the background-burner cap is full,
    // in which case the slot stays held until the worker ends on its own.
    private fun abandon(state: AtomicInteger) {
        if (!tryReserve(abandonedThreads, MAX_ABANDONED_THREADS)) return
        if (state.compareAndSet(STATE_LIVE, STATE_ABANDONED)) {
            liveThreads.decrementAndGet()
        } else {
            abandonedThreads.decrementAndGet()
        }
    }

    // Reserve a slot before starting work so the cap is an invariant, not a
    // racy check. Healthy matches hold a live slot for microseconds; only
    // abandoned runaways hold one for long, and only until abandon() moves
    // them off it.
    private fun tryReserve(counter: AtomicInteger, max: Int): Boolean {
        while (true) {
            val current = counter.get()
            if (current >= max) return false
            if (counter.compareAndSet(current, current + 1)) return true
        }
    }
}
