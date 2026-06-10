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
        val decision = decide(candidateNumbers(number), RuleRepository.currentRules())

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

    /**
     * The handle is whatever the network delivered: depending on the carrier
     * it may be national format or contain separators. Also match the
     * separator-stripped form and the E.164 form so rules anchored to an
     * international prefix keep working.
     */
    private fun candidateNumbers(raw: String): List<String> {
        val candidates = mutableListOf(raw)
        PhoneNumberUtils.normalizeNumber(raw)?.takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
        val telephony = getSystemService(TelephonyManager::class.java)
        val country = telephony?.networkCountryIso?.ifEmpty { telephony.simCountryIso }
        if (!country.isNullOrEmpty()) {
            PhoneNumberUtils.formatNumberToE164(raw, country.uppercase())?.let { candidates.add(it) }
        }
        return candidates.distinct()
    }

    sealed interface Decision {
        data object Allow : Decision
        data class Block(val rule: Rule) : Decision
        data object Silence : Decision
    }

    companion object {
        fun decide(number: String, rules: List<Rule>): Decision =
            decide(listOf(number), rules)

        fun decide(numbers: List<String>, rules: List<Rule>): Decision {
            val active = rules.filter { it.enabled }
            fun firstMatching(action: RuleAction): Rule? =
                active.firstOrNull { rule ->
                    rule.action == action && numbers.any { rule.matches(it) }
                }

            if (firstMatching(RuleAction.ALLOW) != null) return Decision.Allow
            firstMatching(RuleAction.BLOCK)?.let { return Decision.Block(it) }
            return if (firstMatching(RuleAction.SILENCE) != null) Decision.Silence
            else Decision.Allow
        }
    }
}
