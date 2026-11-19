package com.ny0t.kitto.debugclient

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ny0t.kitto.IAgentTools
import com.ny0t.kitto.IToolCallback

/**
 * Throwaway Kitto M1 test client. Buttons, or from adb:
 *   adb shell am start -n <pkg>/com.ny0t.kitto.debugclient.MainActivity --es call get_budget_balances
 *   adb logcat -s KittoDebugClient
 * <pkg> = com.ny0t.kitto.debugclient (Buckt's key) or com.ny0t.kitto.debugclient.otherkey (debug key).
 */
class MainActivity : Activity() {
  private val tag = "KittoDebugClient"
  private val action = "com.ny0t.kitto.action.AGENT_TOOLS"
  private lateinit var out: TextView
  private var tools: IAgentTools? = null
  private var bindStarted = 0L
  private var pending: Pair<String, String>? = null

  private val conn = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
      tools = IAgentTools.Stub.asInterface(binder)
      say("BOUND ${name.packageName} in ${SystemClock.elapsedRealtime() - bindStarted} ms")
      pending?.let { call(it.first, it.second) }
      pending = null
    }
    override fun onServiceDisconnected(name: ComponentName) {
      tools = null
      say("DISCONNECTED")
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 96, 32, 32) }
    fun button(label: String, onClick: () -> Unit) =
      col.addView(Button(this).apply { text = label; isAllCaps = false; setOnClickListener { onClick() } })
    button("Bind to Buckt") { bind() }
    button("listTools") { call("list", "{}") }
    button("get_budget_balances") { call("get_budget_balances", "{}") }
    button("get_recent_transactions (3)") { call("get_recent_transactions", """{"limit":3}""") }
    button("unknown tool") { call("delete_everything", "{}") }
    out = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
    col.addView(out)
    setContentView(ScrollView(this).apply { addView(col) })
    say("I am ${packageName}")
    handleAdb(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleAdb(intent)
  }

  private fun handleAdb(intent: Intent?) {
    val name = intent?.getStringExtra("call") ?: return
    val args = intent.getStringExtra("args") ?: "{}"
    if (tools != null) call(name, args) else { pending = name to args; bind() }
  }

  private fun bind() {
    val found = packageManager.queryIntentServices(Intent(action), 0)
    say("DISCOVER ${found.size} service(s): ${found.joinToString { it.serviceInfo.packageName }}")
    val svc = found.firstOrNull { it.serviceInfo.packageName == "com.navitech.buckt" }?.serviceInfo
      ?: return say("NO BUCKT SERVICE")
    bindStarted = SystemClock.elapsedRealtime()
    try {
      val ok = bindService(Intent(action).setClassName(svc.packageName, svc.name), conn, BIND_AUTO_CREATE)
      if (!ok) say("REFUSED bindService returned false")
    } catch (e: SecurityException) {
      say("REFUSED ${e.message}")
    }
  }

  private fun call(name: String, args: String) {
    val t = tools ?: return say("NOT BOUND")
    val started = SystemClock.elapsedRealtime()
    fun ms() = SystemClock.elapsedRealtime() - started
    try {
      if (name == "list") { say("OK list (${ms()} ms): ${t.listTools()}"); return }
      t.callTool(name, args, object : IToolCallback.Stub() {
        override fun onResult(resultJson: String) { runOnUiThread { say("OK $name (${ms()} ms): $resultJson") } }
        override fun onError(code: String, message: String) { runOnUiThread { say("ERROR $name (${ms()} ms): $code $message") } }
      })
    } catch (e: Exception) {
      say("FAILED $name: $e")
    }
  }

  private fun say(line: String) {
    Log.i(tag, line)
    out.append("$line\n\n")
  }

  override fun onDestroy() {
    if (tools != null) unbindService(conn)
    super.onDestroy()
  }
}
