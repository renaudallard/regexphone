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
        val number = details.handle
            ?.let { Uri.decode(it.schemeSpecificPart) }
            ?: ""
        val decision = decide(number, RuleRepository.currentRules())

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
