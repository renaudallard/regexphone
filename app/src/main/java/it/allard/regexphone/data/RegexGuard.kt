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
 * abandoned: it releases its live slot and is instead accounted against a
 * separate, larger cap on background burners. Once that cap is full, further
 * runaways keep their live slot, and when those are all held nothing matches
 * any more until a runaway ends.
 *
 * A pattern is never started again while an earlier run of it is still
 * going past its deadline, so each distinct pattern holds one thread at most,
 * whether or not it got blacklisted.
 *
 * Each [Scope] has its own slots, so runaways started from the live tester
 * can never take a slot that call screening needs.
 */
object RegexGuard {
    const val DEFAULT_TIMEOUT_MS = 1000L
    private const val MAX_POISONED = 64

    // Slot state for one match, advanced by whichever of the two racing
    // parties acts first: the worker thread when the match finishes, or the
    // caller at the deadline. CAS makes each transition happen exactly once,
    // so a slot is released exactly once.
    private const val STATE_LIVE = 0
    private const val STATE_ABANDONED = 1
    private const val STATE_DONE = 2
    // Timed out with the abandoned cap full: still holding its live slot.
    private const val STATE_HELD = 3

    /** Which slots and which blacklist a match uses. */
    enum class Scope { SCREENING, TESTER }

    private class Pool(val maxLive: Int, val maxAbandoned: Int) {
        // Matches we are actively waiting on. The reservation bounds
        // concurrency; the slot is released the moment we stop waiting,
        // whether the match finished or was abandoned at the deadline.
        val live = AtomicInteger(0)
        // Abandoned runaways still burning a core past their deadline. They
        // no longer hold a live slot; this separate cap bounds how many may
        // pile up under a crafted-rules attack.
        val abandoned = AtomicInteger(0)
        val poisoned: MutableSet<String> = ConcurrentHashMap.newKeySet()
        // Patterns with a run still going past its deadline, with how many.
        // Bounded by the two caps above.
        val stuck = ConcurrentHashMap<String, Int>()

        fun unstick(key: String) {
            stuck.computeIfPresent(key) { _, runs -> if (runs <= 1) null else runs - 1 }
        }
    }

    private val screening = Pool(maxLive = 6, maxAbandoned = 16)
    // The tester only overlaps with its own superseded previews, so it needs
    // fewer slots.
    private val tester = Pool(maxLive = 4, maxAbandoned = 4)

    private fun pool(scope: Scope) = if (scope == Scope.SCREENING) screening else tester

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
    ): Boolean? = run(pattern.pattern(), timeoutMs, scope) { pattern.matcher(input).find() }

    /**
     * The watchdog behind [find], with the match itself passed in as [match]
     * and [key] naming it in the blacklist. Split out so a test can hold a
     * slot with a body it controls instead of a wall-clock dependent pattern.
     */
    @VisibleForTesting
    internal fun run(key: String, timeoutMs: Long, scope: Scope, match: () -> Boolean): Boolean? {
        if (timeoutMs <= 0) return null
        // The tester also honors the screening blacklist so its preview
        // mirrors what the service would do; the service never consults the
        // tester's blacklist.
        if (key in screening.poisoned) return null
        val pool = pool(scope)
        if (scope == Scope.TESTER && key in pool.poisoned) return null
        // A run cut short by the caller's budget is not blacklisted, and the
        // blacklist itself is capped, so without this the same pattern would
        // leave one more runaway behind on every call.
        if (pool.stuck.containsKey(key)) return null
        if (!tryReserve(pool.live, pool.maxLive)) return null
        val state = AtomicInteger(STATE_LIVE)
        val task = FutureTask {
            try {
                match()
            } finally {
                when {
                    state.compareAndSet(STATE_LIVE, STATE_DONE) -> pool.live.decrementAndGet()
                    state.compareAndSet(STATE_ABANDONED, STATE_DONE) -> {
                        pool.abandoned.decrementAndGet()
                        pool.unstick(key)
                    }
                    state.compareAndSet(STATE_HELD, STATE_DONE) -> {
                        pool.live.decrementAndGet()
                        pool.unstick(key)
                    }
                }
            }
        }
        val thread = Thread(task, "regex-guard").apply { isDaemon = true }
        try {
            thread.start()
        } catch (t: Throwable) {
            if (state.compareAndSet(STATE_LIVE, STATE_DONE)) pool.live.decrementAndGet()
            return null
        }
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            // The runaway cannot be stopped; drop it to minimum priority and
            // abandon it so it stops holding a live slot while it burns its
            // core in the background.
            thread.priority = Thread.MIN_PRIORITY
            // Record the run as stuck before the state leaves LIVE: the
            // worker only drops the record from ABANDONED or HELD, so it can
            // never miss one written after it finished.
            pool.stuck.merge(key, 1, Int::plus)
            if (!abandon(pool, state) && !state.compareAndSet(STATE_LIVE, STATE_HELD)) {
                // The worker finished just as the deadline passed.
                pool.unstick(key)
            }
            // Only blacklist a run that had its full time allowance; one cut
            // short by the caller's remaining budget proves nothing. When the
            // set is full, drop the new entry rather than wiping known ones;
            // the lock keeps the cap exact under concurrent timeouts.
            if (timeoutMs >= DEFAULT_TIMEOUT_MS) {
                synchronized(pool.poisoned) {
                    if (pool.poisoned.size < MAX_POISONED) pool.poisoned.add(key)
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Whether call screening has used up its abandoned slots. From here on
     * every further runaway keeps a match slot, and six of those leave no
     * rule matching, so callers should treat this as the point to act.
     */
    fun saturated(): Boolean = screening.abandoned.get() >= screening.maxAbandoned

    /**
     * Whether [pattern] is currently blacklisted for [scope], mirroring the
     * fast-fail gate in [find]. Lets a test assert the blacklist by state
     * rather than by timing how fast a rejected match returns.
     */
    @VisibleForTesting
    fun isPoisoned(pattern: Pattern, scope: Scope = Scope.SCREENING): Boolean {
        val key = pattern.pattern()
        return key in screening.poisoned || (scope == Scope.TESTER && key in tester.poisoned)
    }

    // Move a timed-out match from its live slot to an abandoned one so a fresh
    // match can take the freed live slot, and return whether it moved. It
    // does not when the worker already finished and released the slot, or
    // when the background-burner cap is full, in which case the slot stays
    // held until the worker ends on its own.
    private fun abandon(pool: Pool, state: AtomicInteger): Boolean {
        if (!tryReserve(pool.abandoned, pool.maxAbandoned)) return false
        if (state.compareAndSet(STATE_LIVE, STATE_ABANDONED)) {
            pool.live.decrementAndGet()
            return true
        }
        pool.abandoned.decrementAndGet()
        return false
    }

    // Reserve a slot before starting work so the cap is an invariant, not a
    // racy check. Healthy matches hold a live slot for microseconds; only
    // runaways hold one for long.
    private fun tryReserve(counter: AtomicInteger, max: Int): Boolean {
        while (true) {
            val current = counter.get()
            if (current >= max) return false
            if (counter.compareAndSet(current, current + 1)) return true
        }
    }
}
