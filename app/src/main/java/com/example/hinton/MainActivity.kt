package com.example.hinton

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.Gravity
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale

class MainActivity : ComponentActivity(), BrainCallback {

    private lateinit var face: WebView
    private lateinit var caption: TextView
    private lateinit var linkView: TextView
    private lateinit var talkBtn: Button
    private lateinit var brain: HintonBrain

    private var faceReady = false
    private val pendingJs = ArrayList<String>()
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var listening = false

    // ------------------------------------------------------------------ setup

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dark = Color.parseColor("#05070d")
        val soft = Color.parseColor("#cfe9ff")
        val match = LinearLayout.LayoutParams.MATCH_PARENT
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(dark)
        }

        linkView = TextView(this).apply {
            text = "laptop: offline (chat only)"
            setTextColor(Color.parseColor("#7f93b3"))
            textSize = 12f
            gravity = Gravity.END
            setPadding(24, 16, 24, 0)
        }

        face = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    faceReady = true
                    for (js in pendingJs) view?.evaluateJavascript(js, null)
                    pendingJs.clear()
                }
            }
            setOnClickListener { toggleListen() }
            loadUrl("file:///android_asset/face.html")
        }

        caption = TextView(this).apply {
            text = "Tap the button to talk"
            setTextColor(soft)
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(48, 8, 48, 16)
        }

        talkBtn = Button(this).apply {
            text = "Tap to talk"
            setOnClickListener { toggleListen() }
        }
        val settingsBtn = Button(this).apply {
            text = "Settings"
            setOnClickListener { showSettings() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
            addView(talkBtn, LinearLayout.LayoutParams(wrap, wrap).apply { marginEnd = 24 })
            addView(settingsBtn)
        }

        root.addView(linkView, LinearLayout.LayoutParams(match, wrap))
        root.addView(face, LinearLayout.LayoutParams(match, 0, 1f))
        root.addView(caption, LinearLayout.LayoutParams(match, wrap))
        root.addView(row, LinearLayout.LayoutParams(match, wrap))

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(root)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.forLanguageTag("en-IN")
                ttsReady = true
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                runOnUiThread { speaking = false; setMode("idle") }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread { speaking = false; setMode("idle") }
            }
        })

        val cfg = loadConfig()
        brain = HintonBrain(cfg, this)
        brain.start()
        if (cfg.server.isBlank() && cfg.apiKey.isBlank()) {
            caption.text = "Open Settings to add your laptop address and NVIDIA key."
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer?.destroy()
        tts?.shutdown()
        brain.stop()
    }

    // ------------------------------------------------------------------ face

    private fun runJs(js: String) {
        if (faceReady) face.evaluateJavascript(js, null) else pendingJs.add(js)
    }

    private fun setMode(m: String) = runJs("setMode('$m')")

    // ------------------------------------------------------------------ brain callbacks (called from background threads)

    override fun onState(state: String) {
        runOnUiThread {
            if (state == "idle") {
                if (!speaking && !listening) setMode("idle")
            } else if (!speaking) {
                setMode(state)
            }
        }
    }

    override fun onSay(text: String) {
        runOnUiThread { speak(text) }
    }

    override fun onConfirm(cmd: String) {
        runOnUiThread {
            runJs("setConfirm(true)")
            speak("Should I run $cmd?")
            AlertDialog.Builder(this)
                .setTitle("Run this command?")
                .setMessage(cmd)
                .setCancelable(false)
                .setPositiveButton("Yes") { _, _ -> runJs("setConfirm(false)"); brain.confirm(true) }
                .setNegativeButton("No") { _, _ -> runJs("setConfirm(false)"); brain.confirm(false) }
                .show()
        }
    }

    override fun onLink(connected: Boolean) {
        runOnUiThread {
            linkView.text = if (connected) "laptop: connected" else "laptop: offline (chat only)"
        }
    }

    // ------------------------------------------------------------------ speaking

    private fun speak(text: String) {
        caption.text = text
        val t = tts
        if (t == null || !ttsReady) {
            speaking = false
            setMode("idle")
            return
        }
        speaking = true
        setMode("speaking")
        val spoken = text.replace(Regex("hinton", RegexOption.IGNORE_CASE), "Hin-tun")
        t.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime())
    }

    // ------------------------------------------------------------------ listening

    private fun finishListening() {
        listening = false
        talkBtn.text = "Tap to talk"
    }

    private fun toggleListen() {
        if (listening) {
            recognizer?.cancel()
            finishListening()
            setMode("idle")
            caption.text = "Tap the button to talk"
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            caption.text = "Speech recognition isn't available on this phone."
            return
        }
        tts?.stop()
        speaking = false
        listening = true
        setMode("listening")
        talkBtn.text = "Listening… (tap to cancel)"
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        rec.startListening(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            toggleListen()
        } else {
            caption.text = "Microphone permission is needed to talk to Hinton."
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) caption.text = "You: $t"
        }

        override fun onError(error: Int) {
            finishListening()
            setMode("idle")
            caption.text = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that. Tap to try again."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
                else -> "Speech error ($error). Tap to try again."
            }
        }

        override fun onResults(results: Bundle?) {
            finishListening()
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (t.isNullOrBlank()) {
                setMode("idle")
                caption.text = "I didn't catch that."
                return
            }
            caption.text = "You: $t"
            setMode("thinking")
            brain.ask(t)
        }
    }

    // ------------------------------------------------------------------ settings

    private fun prefs() = getSharedPreferences("hinton", Context.MODE_PRIVATE)

    private fun loadConfig(): BrainConfig {
        val p = prefs()
        return BrainConfig(
            server = p.getString("server", "") ?: "",
            token = p.getString("token", "") ?: "",
            apiKey = p.getString("apiKey", "") ?: "",
            model = p.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL,
        )
    }

    private fun showSettings() {
        val c = loadConfig()
        fun field(hint: String, value: String, secret: Boolean = false): EditText =
            EditText(this).apply {
                this.hint = hint
                setText(value)
                setSingleLine()
                if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }

        val server = field("Laptop address, e.g. 10.62.118.137:8000", c.server)
        val token = field("Pairing token (PAIR_TOKEN)", c.token, secret = true)
        val key = field("NVIDIA API key (nvapi-...)", c.apiKey, secret = true)
        val model = field("NVIDIA model for direct chat", c.model)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(server)
            addView(token)
            addView(key)
            addView(model)
        }

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs().edit()
                    .putString("server", server.text.toString().trim())
                    .putString("token", token.text.toString().trim())
                    .putString("apiKey", key.text.toString().trim())
                    .putString("model", model.text.toString().trim().ifEmpty { DEFAULT_MODEL })
                    .apply()
                brain.updateConfig(loadConfig())
                caption.text = "Settings saved."
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val DEFAULT_MODEL = "nvidia/nemotron-3.5-lightning-30b-a3b"
    }
}
