package com.ny0t.kitto

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.ny0t.kitto.protocol.ToolSpec
import com.ny0t.kitto.protocol.parseToolSpecs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val AGENT_TOOLS_ACTION = "com.ny0t.kitto.action.AGENT_TOOLS"

/** An app on this phone that offers tools over the Kitto protocol. */
data class Provider(val label: String, val component: ComponentName)

class ToolException(val code: String, message: String) : Exception(message)

/** Finds every installed app that declares the AGENT_TOOLS service. */
fun findProviders(context: Context): List<Provider> {
    val pm = context.packageManager
    return pm.queryIntentServices(Intent(AGENT_TOOLS_ACTION), 0).map { info ->
        Provider(
            label = info.loadLabel(pm).toString(),
            component = ComponentName(info.serviceInfo.packageName, info.serviceInfo.name),
        )
    }
}

private class ProviderConnection(
    val onBound: (IAgentTools) -> Unit,
    val onLost: () -> Unit,
) : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, service: IBinder) =
        onBound(IAgentTools.Stub.asInterface(service))

    override fun onServiceDisconnected(name: ComponentName) = onLost()
}

/**
 * Binds to one provider, runs [block] on a background thread, then unbinds.
 * The 20 s timeout covers the bind too, so a provider that never connects can't hang Kitto.
 */
suspend fun <T> withProvider(
    context: Context,
    provider: Provider,
    block: suspend (IAgentTools) -> T,
): T {
    val intent = Intent(AGENT_TOOLS_ACTION).setComponent(provider.component)
    var connection: ProviderConnection? = null
    try {
        return withTimeout(20_000) {
            val agent = suspendCancellableCoroutine<IAgentTools> { cont ->
                val conn = ProviderConnection(
                    onBound = { if (cont.isActive) cont.resume(it) },
                    onLost = { if (cont.isActive) cont.resumeWithException(ToolException("DISCONNECTED", "${provider.label} disconnected")) },
                )
                connection = conn
                val bound = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
                if (!bound && cont.isActive) {
                    cont.resumeWithException(ToolException("NOT_BOUND", "Could not connect to ${provider.label}"))
                }
            }
            withContext(Dispatchers.IO) { block(agent) }
        }
    } finally {
        connection?.let { runCatching { context.unbindService(it) } }
    }
}

/** Asks one provider for its tool list. */
suspend fun listTools(context: Context, provider: Provider): List<ToolSpec> =
    withProvider(context, provider) { agent -> parseToolSpecs(agent.listTools()) }

/** Runs one tool on one provider and returns its result JSON. */
suspend fun callTool(context: Context, provider: Provider, name: String, argsJson: String): String =
    withProvider(context, provider) { agent ->
        suspendCancellableCoroutine { cont ->
            agent.callTool(name, argsJson, object : IToolCallback.Stub() {
                override fun onResult(resultJson: String) {
                    if (cont.isActive) cont.resume(resultJson)
                }
                override fun onError(code: String, message: String) {
                    if (cont.isActive) cont.resumeWithException(ToolException(code, message))
                }
            })
        }
    }