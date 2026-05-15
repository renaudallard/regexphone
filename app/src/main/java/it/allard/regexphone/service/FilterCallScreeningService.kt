package it.allard.regexphone.service

import android.net.Uri
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import it.allard.regexphone.data.Rule
import it.allard.regexphone.data.RuleAction
import it.allard.regexphone.data.RuleRepository

class FilterCallScreeningService : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        RuleRepository.init(applicationContext)
        val handle = details.handle
        val number = handle?.let { Uri.decode(it.schemeSpecificPart) } ?: ""
        val rules = RuleRepository.currentRules()
        val decision = decide(number, rules)

        Log.d(
            TAG,
            "handle=$handle number=\"$number\" enabledRules=${rules.count { it.enabled }} decision=$decision",
        )

        val response = CallResponse.Builder().apply {
            if (decision is Decision.Block) {
                setDisallowCall(true)
                setRejectCall(true)
                setSkipCallLog(decision.rule.skipCallLog)
                setSkipNotification(decision.rule.skipNotification)
            }
        }.build()

        respondToCall(details, response)
    }

    sealed interface Decision {
        data object Allow : Decision
        data class Block(val rule: Rule) : Decision
    }

    companion object {
        private const val TAG = "RegexPhone"

        fun decide(number: String, rules: List<Rule>): Decision {
            val active = rules.filter { it.enabled }
            if (active.any { it.action == RuleAction.ALLOW && it.matches(number) }) {
                return Decision.Allow
            }
            val blockRule = active.firstOrNull {
                it.action == RuleAction.BLOCK && it.matches(number)
            }
            return if (blockRule != null) Decision.Block(blockRule) else Decision.Allow
        }
    }
}
