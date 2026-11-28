package com.ny0t.kitto

import android.app.Activity
import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
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
    /** Kitto needs a Google sign-in before it can ask the backend anything (M6 B6). */
    var signedIn by mutableStateOf(Backend.isSignedIn())
        private set
    var signInError by mutableStateOf<String?>(null)
        private set
    var signingIn by mutableStateOf(false)
        private set

    private var gateAnswer: CompletableDeferred<Boolean>? = null
    /** Speaks replies to spoken questions (Part D). Shut down with the ViewModel, so closing the sheet stops it. */
    private val speaker = Speaker(app)
    val speaking: Boolean get() = speaker.speaking
    /** Whether the question being answered was spoken: decides if the reply and the gate hint are spoken. */
    private var spokenQuestion = false

    /** Index in [lines] of the reply bubble being streamed, or -1 when there is none. */
    private var liveLine = -1
    private var loop: AgentLoop? = null

    // Calls Kitto's backend, not Claude directly; the API key never ships in the app (M6 B6).
    private val client by lazy { Backend.client() }

    fun signIn(activity: Activity) {
        if (signingIn) return
        signingIn = true
        signInError = null
        viewModelScope.launch {
            try {
                Backend.signIn(activity)
                signedIn = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: GetCredentialCancellationException) {
                // Closed the account picker: nothing to report.
            } catch (e: Exception) {
                Log.e("KittoAuth", "sign-in failed", e)
                signInError = "Sign-in didn't work: ${e.message ?: e::class.simpleName}"
            } finally {
                signingIn = false
            }
        }
    }

    fun send(text: String, spoken: Boolean = false) {
        val question = text.trim()
        if (question.isEmpty() || busy) return
        speaker.stop()
        spokenQuestion = spoken
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
                if (e is NotSignedInException) signedIn = false
                friendlyError(e)
            }
            // The streamed bubble gets the final text (it may be an error or a give-up message instead).
            if (liveLine >= 0) lines[liveLine] = ChatLine(fromUser = false, text = reply)
            else lines += ChatLine(fromUser = false, text = reply)
            liveLine = -1
            status = null
            busy = false
            // Spoken question → spoken answer (decision 3). Typed questions stay silent.
            if (spoken) speaker.say(reply)
        }
    }

    fun stopSpeaking() = speaker.stop()

    override fun onCleared() {
        speaker.shutdown()
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
            onUpdate = { update -> onStream(update, routed) },
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

    /** Stream events arrive on a background thread; apply them on the main thread, in order. */
    private fun onStream(update: StreamUpdate, routed: Map<String, RoutedTool<Provider>>) {
        viewModelScope.launch {
            when (update) {
                is StreamUpdate.ToolStarted -> {
                    // Text before a tool call ("Let me check…") isn't the answer: drop it.
                    if (liveLine >= 0) {
                        lines.removeAt(liveLine)
                        liveLine = -1
                    }
                    status = "checking ${routed[update.claudeName]?.appLabel ?: "your apps"}…"
                }
                is StreamUpdate.Text -> {
                    if (liveLine < 0) {
                        lines += ChatLine(fromUser = false, text = "")
                        liveLine = lines.lastIndex
                    }
                    lines[liveLine] = lines[liveLine].copy(text = lines[liveLine].text + update.piece)
                    status = null
                }
            }
        }
    }

    /** Shows the dialog and waits for Allow / Deny. */
    private suspend fun askUser(request: GateRequest): Boolean {
        val answer = CompletableDeferred<Boolean>()
        gateAnswer = answer
        gateRequest = request
        // Writes still need a tap, never a spoken "yes" (decision 4).
        if (spokenQuestion) speaker.say("Tap Allow to save.")
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
        is NotSignedInException -> "Please sign in again."
        is AppCheckException -> "Kitto couldn't prove it's the real app (App Check). Try again in a moment."
        // The backend's refusals start with a code (backend/functions/src/checks.ts, usage.ts).
        is AnthropicServiceException -> backendError(e)
        is AnthropicIoException, is java.io.IOException -> "No internet connection."
        else -> "Something went wrong: ${e::class.simpleName}: ${e.message}"
    }

    private fun backendError(e: AnthropicServiceException): String {
        val text = e.message.orEmpty()
        return when {
            "LIMIT_REACHED" in text -> "You've used all your questions for today. They reset at midnight."
            "RATE_LIMITED" in text -> "That's a lot of requests at once. Wait a moment and try again."
            "NOT_ALLOWED" in text -> "This Google account isn't on Kitto's list yet. Ask Ny0t to add you."
            "APP_CHECK_FAILED" in text -> "Kitto couldn't prove it's the real app (App Check)."
            e is UnauthorizedException -> "Your sign-in has expired. Please sign in again."
            e is PermissionDeniedException -> "Kitto's backend said no (403)."
            e is RateLimitException -> "Claude is busy right now. Try again in a moment."
            else -> "Claude error ${e.statusCode()}: ${e.message}"
        }
    }
}
