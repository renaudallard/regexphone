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

package it.allard.regexphone.service

import android.telecom.Call
import android.telecom.CallScreeningService
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import it.allard.regexphone.data.RegexGuard
import it.allard.regexphone.data.Rule
import it.allard.regexphone.data.RuleAction
import it.allard.regexphone.data.RuleRepository

class FilterCallScreeningService : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        // The framework also delivers outgoing calls to the screening role
        // holder but ignores any response to them, so skip rule evaluation.
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) {
            respondToCall(details, CallResponse.Builder().build())
            return
        }
        RuleRepository.init(applicationContext)
        val number = details.handle?.schemeSpecificPart ?: ""
        val decision = decide(
            candidateNumbers(number, countryIso(getSystemService(TelephonyManager::class.java))),
            RuleRepository.currentRules(),
        )

        val response = CallResponse.Builder().apply {
            when (decision) {
                is Decision.Allow -> Unit
                is Decision.Block -> {
                    setDisallowCall(true)
                    setRejectCall(true)
                    // setSkipCallLog is reserved for carrier and system
                    // screeners; the platform ignores it for role holders.
                    setSkipNotification(decision.rule.skipNotification)
                }
                is Decision.Silence -> setSilenceCall(true)
            }
        }.build()

        respondToCall(details, response)
    }

    sealed interface Decision {
        /** [rule] is the matching allow rule, or null when nothing matched. */
        data class Allow(val rule: Rule? = null) : Decision
        data class Block(val rule: Rule) : Decision
        data class Silence(val rule: Rule) : Decision
    }

    companion object {
        // Stay well inside Telecom's ~5 second response deadline even when
        // several fresh patterns each exhaust their individual watchdog
        // timeout on the first call after process start.
        private const val TOTAL_BUDGET_MS = 3500L
        // Cap the ALLOW pass below the full budget so a heavy or slow ALLOW
        // ruleset cannot drain the deadline and leave the BLOCK and SILENCE
        // passes no time, which would let a call the user blocked ring
        // through. A slow ALLOW rule cut short by this smaller cap at worst
        // lets a later BLOCK override a whitelist, the safe direction.
        private const val ALLOW_BUDGET_MS = 2000L

        /**
         * The handle is whatever the network delivered: depending on the
         * carrier it may be national format or contain separators. Also match
         * the separator-stripped form and the E.164 form so rules anchored to
         * an international prefix keep working. The live tester uses the same
         * expansion so its preview agrees with the service.
         */
        fun candidateNumbers(raw: String, countryIso: String?): List<String> {
            val candidates = mutableListOf(raw)
            PhoneNumberUtils.normalizeNumber(raw)?.takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
            if (!countryIso.isNullOrEmpty()) {
                PhoneNumberUtils.formatNumberToE164(raw, countryIso.uppercase())?.let { candidates.add(it) }
            }
            return candidates.distinct()
        }

        /**
         * The country ISO used to build the E.164 candidate, network first
         * then SIM. Shared with the live tester so its preview agrees with the
         * service.
         */
        fun countryIso(telephony: TelephonyManager?): String? {
            if (telephony == null) return null
            return telephony.networkCountryIso?.ifEmpty { telephony.simCountryIso }
        }

        fun decide(number: String, rules: List<Rule>): Decision =
            decide(listOf(number), rules)

        fun decide(
            numbers: List<String>,
            rules: List<Rule>,
            scope: RegexGuard.Scope = RegexGuard.Scope.SCREENING,
        ): Decision {
            val start = System.nanoTime()
            fun remainingMs(budgetMs: Long): Long =
                budgetMs - (System.nanoTime() - start) / 1_000_000L
            val active = rules.filter { it.enabled }
            fun firstMatching(action: RuleAction, budgetMs: Long): Rule? =
                active.firstOrNull { rule ->
                    rule.action == action && numbers.any { rule.matches(it, remainingMs(budgetMs), scope) }
                }

            firstMatching(RuleAction.ALLOW, ALLOW_BUDGET_MS)?.let { return Decision.Allow(it) }
            firstMatching(RuleAction.BLOCK, TOTAL_BUDGET_MS)?.let { return Decision.Block(it) }
            firstMatching(RuleAction.SILENCE, TOTAL_BUDGET_MS)?.let { return Decision.Silence(it) }
            return Decision.Allow()
        }
    }
}
