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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Process
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.annotation.VisibleForTesting
import it.allard.regexphone.R
import it.allard.regexphone.data.RegexGuard
import it.allard.regexphone.data.Rule
import it.allard.regexphone.data.RuleAction
import it.allard.regexphone.data.RuleRepository
import java.util.Locale

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
                is Decision.Notify -> Unit
            }
        }.build()

        respondToCall(details, response)
        if (decision is Decision.Notify) postWarning(number, decision.rule)

        // Runaway matches cannot be stopped and only end with the process.
        // Once they fill the abandoned slots the next ones start taking the
        // match slots, until no rule matches at all. Give up the process
        // before that, now that this call is answered: the next call starts
        // a fresh one with every slot free. Not while the app is on screen,
        // which would close it under the user; the rules list warns there.
        if (RegexGuard.saturated() && !uiVisible) Process.killProcess(Process.myPid())
    }

    // Posted only after the response so the call is never held up. Without
    // the notification permission, or with notifications turned off, the
    // call simply rings as an allowed one.
    private fun postWarning(number: String, rule: Rule) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        // The call is already ringing, so a notification sound on top of the
        // ringtone adds nothing. The user can still change it in settings.
        val channel = NotificationChannel(
            CHANNEL_WARNINGS,
            getString(R.string.notify_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.notify_channel_description)
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
        val notification = Notification.Builder(this, CHANNEL_WARNINGS)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(
                getString(
                    R.string.notify_title,
                    number.ifEmpty { getString(R.string.notify_hidden_number) },
                )
            )
            .setContentText(
                if (rule.name.isBlank()) getString(R.string.notify_text_unnamed)
                else getString(R.string.notify_text, rule.name)
            )
            .build()
        nm.notify(WARNING_ID, notification)
    }

    sealed interface Decision {
        /** [rule] is the matching allow rule, or null when nothing matched. */
        data class Allow(val rule: Rule? = null) : Decision
        data class Block(val rule: Rule) : Decision
        data class Silence(val rule: Rule) : Decision
        data class Notify(val rule: Rule) : Decision
    }

    companion object {
        /** Kept by MainActivity: whether the app is on screen. */
        @Volatile
        var uiVisible = false

        private const val CHANNEL_WARNINGS = "call_warnings"
        // One id for every warning: a new matching call replaces the previous
        // warning instead of piling up, the call log keeps the history.
        private const val WARNING_ID = 1

        // Stay well inside Telecom's ~5 second response deadline even when
        // several fresh patterns each exhaust their individual watchdog
        // timeout on the first call after process start.
        private const val TOTAL_BUDGET_MS = 3500L
        // Cap the ALLOW pass below the full budget so a heavy or slow ALLOW
        // ruleset cannot drain the deadline and leave the later passes no
        // time, which would let a call the user blocked ring through. A slow
        // ALLOW rule cut short by this smaller cap at worst lets a later
        // BLOCK override a whitelist, the safe direction.
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
                // ISO region codes are ASCII, so uppercase with ROOT: the
                // default locale's Turkish-i rule would turn an "i" country
                // (in, it, fi, ...) into "İ" (U+0130), an unknown region that
                // makes formatNumberToE164 drop the E.164 candidate.
                val region = countryIso.uppercase(Locale.ROOT)
                PhoneNumberUtils.formatNumberToE164(raw, region)?.let { candidates.add(it) }
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
            // A null network ISO means the same as an empty one here, so fall
            // back to the SIM country in both cases. Guarding only for empty
            // would skip the fallback when the platform returns null.
            return telephony.networkCountryIso?.takeIf { it.isNotEmpty() } ?: telephony.simCountryIso
        }

        @VisibleForTesting
        fun decide(number: String, rules: List<Rule>): Decision =
            decide(listOf(number), rules)

        // The pass budgets are parameters defaulting to the production caps so
        // a test can drive a pass to zero remaining time and assert the
        // starvation guard deterministically, rather than with a wall-clock
        // dependent slow pattern.
        fun decide(
            numbers: List<String>,
            rules: List<Rule>,
            scope: RegexGuard.Scope = RegexGuard.Scope.SCREENING,
            allowBudgetMs: Long = ALLOW_BUDGET_MS,
            totalBudgetMs: Long = TOTAL_BUDGET_MS,
        ): Decision {
            val start = System.nanoTime()
            fun remainingMs(budgetMs: Long): Long =
                budgetMs - (System.nanoTime() - start) / 1_000_000L
            val active = rules.filter { it.enabled }
            fun firstMatching(action: RuleAction, budgetMs: Long): Rule? =
                active.firstOrNull { rule ->
                    rule.action == action && numbers.any { rule.matches(it, remainingMs(budgetMs), scope) }
                }

            firstMatching(RuleAction.ALLOW, allowBudgetMs)?.let { return Decision.Allow(it) }
            firstMatching(RuleAction.BLOCK, totalBudgetMs)?.let { return Decision.Block(it) }
            firstMatching(RuleAction.SILENCE, totalBudgetMs)?.let { return Decision.Silence(it) }
            firstMatching(RuleAction.NOTIFY, totalBudgetMs)?.let { return Decision.Notify(it) }
            return Decision.Allow()
        }
    }
}
