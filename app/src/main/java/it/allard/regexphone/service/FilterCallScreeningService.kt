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

import android.net.Uri
import android.telecom.Call
import android.telecom.CallScreeningService
import it.allard.regexphone.data.Rule
import it.allard.regexphone.data.RuleAction
import it.allard.regexphone.data.RuleRepository

class FilterCallScreeningService : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        RuleRepository.init(applicationContext)
        val number = details.handle?.let { Uri.decode(it.schemeSpecificPart) } ?: ""
        val decision = decide(number, RuleRepository.currentRules())

        val response = CallResponse.Builder().apply {
            when (decision) {
                is Decision.Allow -> Unit
                is Decision.Block -> {
                    setDisallowCall(true)
                    setRejectCall(true)
                    setSkipCallLog(decision.rule.skipCallLog)
                    setSkipNotification(decision.rule.skipNotification)
                }
                is Decision.Silence -> setSilenceCall(true)
            }
        }.build()

        respondToCall(details, response)
    }

    sealed interface Decision {
        data object Allow : Decision
        data class Block(val rule: Rule) : Decision
        data object Silence : Decision
    }

    companion object {
        fun decide(number: String, rules: List<Rule>): Decision {
            val active = rules.filter { it.enabled }
            if (active.any { it.action == RuleAction.ALLOW && it.matches(number) }) {
                return Decision.Allow
            }
            val blockRule = active.firstOrNull {
                it.action == RuleAction.BLOCK && it.matches(number)
            }
            if (blockRule != null) return Decision.Block(blockRule)
            val hasSilence = active.any {
                it.action == RuleAction.SILENCE && it.matches(number)
            }
            return if (hasSilence) Decision.Silence else Decision.Allow
        }
    }
}
