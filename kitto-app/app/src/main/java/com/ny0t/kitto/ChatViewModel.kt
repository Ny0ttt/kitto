package com.ny0t.kitto

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.UnauthorizedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

private const val SYSTEM_PROMPT = """You are Kitto, Ny0t's assistant on his phone.
Use the tools for any figure about his money; never estimate or recall numbers.
Take the currency from the tool result.
If a result says "fromCache": true, say the numbers may be out of date.
If no tool can answer, say so plainly. Keep replies short.

Logging spending or income: use the log_transaction tool. Never guess an amount; if Ny0t didn't give one, ask.
For an expense, use an exact budget name from get_budget_balances (call it first if you don't know the names).
Only choose a budget when the description clearly names it or obviously belongs in it (coffee → a food budget).
Never pick a budget just because it exists. If the description is unclear, unfamiliar or could fit
more than one budget, don't call log_transaction yet: ask which budget, listing the budget names
and "no budget" as options. Income never has a budget.
Each of Ny0t's messages starts with a [Today is …] line added by the app; use the newest one for dates
and don't mention it. Leave the date out for today. For another day ("yesterday", "last Friday"),
work out the YYYY-MM-DD date from it.
Tools that change data show Ny0t an Allow/Deny dialog on the phone before they run; you don't see it.
If a result says Ny0t tapped Deny, say it wasn't saved and don't offer to try again unless Ny0t asks.
After saving, confirm in one line: amount, budget, and what's left in it.
If the result says "synced": false, add that it will sync when Buckt is back online."""

data class ChatLine(val fromUser: Boolean, val text: String)

private class NoProvidersException : Exception("No connected apps found")

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    val lines = mutableStateListOf<ChatLine>()
    var status by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var gateRequest by mutableStateOf<GateRequest?>(null)
        private set

    private var gateAnswer: CompletableDeferred<Boolean>? = null
    private var loop: AgentLoop? = null

    private val client by lazy {
        AnthropicOkHttpClient.builder().apiKey(BuildConfig.ANTHROPIC_API_KEY).build()
    }

    fun send(text: String) {
        val question = text.trim()
        if (question.isEmpty() || busy) return
        lines += ChatLine(fromUser = true, text = question)
        busy = true
        status = "thinking…"
        viewModelScope.launch {
            val reply = try {
                val agent = loop ?: connect().also { loop = it }
                // The date rides on the new message (append-only), never in the system prompt.
                agent.ask(withToday(question))
            } catch (e: TimeoutCancellationException) {
                friendlyError(e)
            } catch (e: CancellationException) {
                throw e // ViewModel cleared: nothing left to show
            } catch (e: Exception) {
                Log.e("KittoLoop", "question failed", e)
                friendlyError(e)
            }
            lines += ChatLine(fromUser = false, text = reply)
            status = null
            busy = false
        }
    }

    /** Called by the gate dialog's buttons. */
    fun answerGate(allow: Boolean) {
        gateAnswer?.complete(allow)
    }

    /** Finds provider apps, lists their tools, and builds the loop. Retried on the next question if it fails. */
    private suspend fun connect(): AgentLoop {
        val context = getApplication<Application>()
        val providers = findProviders(context)
        if (providers.isEmpty()) throw NoProvidersException()

        val routed = mutableMapOf<String, RoutedTool<Provider>>()
        for (provider in providers) {
            status = "checking ${provider.label}…"
            val prefix = prefixFor(provider.component.packageName)
            for (spec in listTools(context, provider)) {
                routed[toClaudeName(prefix, spec.name)] = RoutedTool(provider, provider.label, spec)
            }
        }
        status = "thinking…"

        val conversation = SdkConversation(
            client = client,
            system = SYSTEM_PROMPT,
            tools = buildToolUnions(routed.map { (name, tool) -> name to tool.spec }),
        )
        val router = ToolRouter(routed, ToolGate(::askUser)) { provider, toolName, argsJson ->
            status = "checking ${provider.label}…"
            try {
                callTool(context, provider, toolName, argsJson)
            } finally {
                status = "thinking…"
            }
        }
        return AgentLoop(conversation, router::run) { Log.d("KittoLoop", it) }
    }

    /** Shows the dialog and waits for Allow / Deny. */
    private suspend fun askUser(request: GateRequest): Boolean {
        val answer = CompletableDeferred<Boolean>()
        gateAnswer = answer
        gateRequest = request
        return try {
            answer.await()
        } finally {
            gateRequest = null
            gateAnswer = null
        }
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is NoProvidersException -> "No connected apps found. Is Buckt installed?"
        is SecurityException -> "Buckt refused the connection (signing key doesn't match)."
        is TimeoutCancellationException -> "Buckt timed out."
        is ToolException -> "${e.code}: ${e.message}"
        is UnauthorizedException -> "API key missing or wrong (401)."
        is AnthropicServiceException -> "Claude API error ${e.statusCode()}: ${e.message}"
        is AnthropicIoException, is java.io.IOException -> "No internet connection."
        else -> "Something went wrong: ${e::class.simpleName}: ${e.message}"
    }
}
