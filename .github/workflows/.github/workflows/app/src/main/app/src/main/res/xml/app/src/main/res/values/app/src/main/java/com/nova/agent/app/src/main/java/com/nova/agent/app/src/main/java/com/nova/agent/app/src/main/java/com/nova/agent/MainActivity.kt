package com.nova.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.CountDownLatch

class MainActivity : Activity() {
    private val BG = Color.parseColor("#0E1116")
    private val CARD = Color.parseColor("#1A1F29")
    private val ACCENT = Color.parseColor("#6C5CE7")
    private val GREEN = Color.parseColor("#00B894")
    private val RED = Color.parseColor("#E17055")
    private val REQ_VOICE = 41

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var statusView: TextView
    private lateinit var input: EditText
    private var agent: Agent? = null
    private var worker: Thread? = null
    private var stateText = "तैयार"
    private val history = mutableListOf<String>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun pill(color: Int, r: Int = 14) =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun btn(label: String, color: Int, weight: Float = 1f, onClick: () -> Unit) = Button(this).apply {
        text = label; setTextColor(Color.WHITE); background = pill(color); isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, dp(48), weight).apply { setMargins(dp(3), 0, dp(3), 0) }
    }

    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEach { addView(it) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(36), dp(16), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "✦ Nova AI Agent"; setTextColor(Color.WHITE); textSize = 24f; typeface = Typeface.DEFAULT_BOLD
        })
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; setPadding(dp(12), dp(6), dp(12), dp(6)); background = pill(CARD, 20)
        }
        root.addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        root.addView(row(
            btn("⚙ Settings", CARD) { showSettings() },
            btn("♿ Accessibility", CARD) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            btn("🗑 Clear", CARD) { clearAll() }
        ))

        logView = TextView(this).apply {
            setTextColor(Color.parseColor("#E6E9EF")); textSize = 14f; setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { background = pill(CARD, 16); addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            .apply { topMargin = dp(10) })

        input = EditText(this).apply {
            hint = "कमांड लिखें, जैसे: YouTube खोलो"
            setHintTextColor(Color.GRAY); setTextColor(Color.WHITE)
            background = pill(CARD, 14); setPadding(dp(14), dp(10), dp(14), dp(10))
            minLines = 1; maxLines = 3
        }
        root.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        root.addView(row(
            btn("🎤", CARD, 0.6f) { startVoice() },
            btn("▶ Run", GREEN, 1.2f) { runTask() },
            btn("■ Stop", RED, 1f) { stopTask() }
        ))
        setContentView(root)

        val prefs = getSharedPreferences("nova", MODE_PRIVATE)
        if (!prefs.getBoolean("onboarded", false)) {
            AlertDialog.Builder(this).setTitle("स्वागत है")
                .setMessage("1) Settings में अपनी Gemini API key डालें।\n" +
                        "2) 'Accessibility' दबाकर Nova AI Agent को ON करें, ताकि ऐप स्क्रीन पढ़ और टैप कर सके।\n" +
                        "3) कमांड लिखकर Run दबाएँ। कभी भी Stop दबा सकते हैं।\n\n" +
                        "ऐप पासवर्ड नहीं पढ़ता; Like/Send/Buy जैसे actions से पहले आपसे पूछता है।")
                .setPositiveButton("ठीक है") { _, _ -> prefs.edit().putBoolean("onboarded", true).apply() }
                .show()
        }
    }

    override fun onResume() { super.onResume(); refreshStatus() }

    private fun refreshStatus() {
        val a11y = if (NovaAccessibilityService.instance != null) "Accessibility: ON" else "Accessibility: OFF"
        statusView.text = "$stateText • $a11y"
    }

    private fun setStatus(s: String) { runOnUiThread { stateText = s; refreshStatus() } }

    private fun log(m: String) {
        runOnUiThread { logView.append(m + "\n\n"); scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }
    }

    private fun confirm(q: String): Boolean {
        val latch = CountDownLatch(1)
        var result = false
        runOnUiThread {
            AlertDialog.Builder(this).setTitle("पुष्टि करें").setMessage(q).setCancelable(false)
                .setPositiveButton("हाँ") { _, _ -> result = true; latch.countDown() }
                .setNegativeButton("नहीं") { _, _ -> latch.countDown() }
                .show()
        }
        latch.await()
        return result
    }

    private fun runTask() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (worker?.isAlive == true) { log("पहले वाला काम चल रहा है, पहले Stop दबाएँ।"); return }
        val prefs = getSharedPreferences("nova", MODE_PRIVATE)
        val key = prefs.getString("key", "") ?: ""
        if (key.isBlank()) { log("⚠️ पहले Settings में API key डालें।"); return }
        if (NovaAccessibilityService.instance == null) log("⚠️ Accessibility OFF है, सिर्फ app खोलना काम करेगा।")
        input.setText("")
        log("आप: $text")
        val a = Agent(this, key, prefs.getString("model", "gemini-2.5-flash") ?: "gemini-2.5-flash",
            { m -> log(m) }, { s -> setStatus(s) }, { q -> confirm(q) })
        agent = a
        worker = Thread { a.run(text, history) }.also { it.start() }
    }

    private fun stopTask() {
        agent?.cancel()
        worker?.interrupt()
        log("⛔ Stop दबाया गया")
    }

    private fun clearAll() {
        stopTask()
        history.clear()
        logView.text = ""
    }

    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
        try { startActivityForResult(i, REQ_VOICE) } catch (e: ActivityNotFoundException) { log("Voice input इस फोन पर उपलब्ध नहीं।") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOICE && resultCode == RESULT_OK) {
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { input.setText(it) }
        }
    }

    private fun showSettings() {
        val prefs = getSharedPreferences("nova", MODE_PRIVATE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val key = EditText(this).apply {
            hint = "Gemini API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
        }
        val model = EditText(this).apply { hint = "Model"; setText(prefs.getString("model", "gemini-2.5-flash")) }
        box.addView(key); box.addView(model)
        AlertDialog.Builder(this).setTitle("Settings").setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("key", key.text.toString().trim())
                    .putString("model", model.text.toString().trim().ifEmpty { "gemini-2.5-flash" }).apply()
                log("✅ Settings सेव हुईं")
            }
            .setNeutralButton("Test") { _, _ ->
                val k = key.text.toString().trim()
                val m = model.text.toString().trim().ifEmpty { "gemini-2.5-flash" }
                Thread {
                    try {
                        Gemini.plan(k, m, "Reply with the JSON {\"ok\":true}", "ping")
                        log("✅ Gemini connection सही है")
                    } catch (e: Exception) { log("❌ Gemini test fail: ${e.message}") }
                }.start()
            }
            .setNegativeButton("Cancel", null).show()
    }
}
