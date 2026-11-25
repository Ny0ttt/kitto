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

// System prompt = general rules + one section per connected app (M4 C1). Built once from constants,
// so it never changes inside a conversation: replayed thinking blocks are bound to it (M3 lesson).
private const val PROMPT_GENERAL = """You are Kitto, Ny0t's assistant on his phone. You reach his apps through tools:
buckt__ tools are his money (Buckt), cleo__ tools are his home cleaning (Cleo).
Use the tools for any fact about his money or his cleaning; never estimate or recall it.
If a question covers both apps, call both and answer in one reply.
If no tool can answer, say so plainly. Keep replies short.
Each of Ny0t's messages starts with a [Today is …] line added by the app; use the newest one for dates
and don't mention it. Leave the date out for today. For another day ("yesterday", "last Friday"),
work out the YYYY-MM-DD date from it.
Tools that change data show Ny0t an Allow/Deny dialog on the phone before they run; you don't see it.
If a result says Ny0t tapped Deny, say it wasn't saved and don't offer to try again unless Ny0t asks."""

private const val PROMPT_BUCKT = """Money (Buckt):
Take the currency from the tool result.
If a result says "fromCache": true, say the numbers may be out of date.
Logging spending or income: use log_transaction. Never guess an amount; if Ny0t didn't give one, ask.
For an expense, use an exact budget name from get_budget_balances (call it first if you don't know the names).
Only choose a budget when the description clearly names it or obviously belongs in it (coffee → a food budget).
Never pick a budget just because it exists. If the description is unclear, unfamiliar or could fit
more than one budget, don't call log_transaction yet: ask which budget, listing the budget names
and "no budget" as options. Income never has a budget.
After saving, confirm in one line: amount, budget, and what's left in it.
If the result says "synced": false, add that it will sync when Buckt is back online."""

private const val PROMPT_CLEO = """Cleaning (Cleo):
Spot names are Ny0t's own words, often Malay (e.g. "singki dapur"). Use a spot's exact name from a
Cleo result. Only use a spot when Ny0t's words clearly mean it; never guess or translate your way
to one. If unsure which spot, or there is no match, don't call log_clean yet: ask, listing the spot names.
Logging a clean: use log_clean. Only cleans that already happened (today or earlier); for a future
day, say you can only log cleans that are done.
After saving, confirm in one line: spot, the day, and the next due date. If "nextDueAdjusted": true,
say the schedule date had already passed so Cleo set the next one from today. If "latest": false,
say it was saved as an older clean and the spot's last clean and next due didn't change.
If nextDue is null, the spot is still learning its routine: say there's no due date yet; don't make one up.
"Not Started" spots have no schedule yet; mention them only if Ny0t asks about all spots."""

private const val SYSTEM_PROMPT = PROMPT_GENERAL + "\n\n" + PROMPT_BUCKT + "\n\n" + PROMPT_CLEO

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
