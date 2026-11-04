package com.example.hinton

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class BrainConfig(
    val server: String,   // laptop address like 10.62.118.137:8000 (blank = laptop link off)
    val token: String,    // the PAIR_TOKEN you set on the laptop
    val apiKey: String,   // NVIDIA key, used for direct chat when the laptop is unreachable
    val model: String,    // NVIDIA model id for direct chat
)

interface BrainCallback {
    fun onState(state: String)        // "thinking" / "idle" ...
    fun onSay(text: String)           // something Hinton should speak
    fun onConfirm(cmd: String)        // laptop wants permission to run a command
    fun onLink(connected: Boolean)    // laptop connection changed
}

/**
 * Talks to the laptop server over a WebSocket when it is reachable (so terminal
 * commands work). If the laptop is not reachable, chats directly with NVIDIA.
 */
class HintonBrain(initialConfig: BrainConfig, private val cb: BrainCallback) {

    @Volatile private var cfg: BrainConfig = initialConfig
    @Volatile private var warnedToken = false

    private val io: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    private val laptopClient: OkHttpClient = buildLaptopClient()
    private val apiClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var linked = false
    @Volatile private var connecting = false
    private val history = ArrayList<JSONObject>()
    private val lock = Any()

    fun start() {
        io.scheduleWithFixedDelay({ ensureLink() }, 0, 6, TimeUnit.SECONDS)
    }

    fun stop() {
        socket?.cancel()
        io.shutdownNow()
    }

    fun updateConfig(c: BrainConfig) {
        cfg = c
        warnedToken = false
        socket?.cancel()
        connecting = false
        setLinked(false)
    }

    private fun setLinked(v: Boolean) {
        if (linked != v) {
            linked = v
            cb.onLink(v)
        }
    }

    // ---------------- laptop link ----------------

    private fun ensureLink() {
        if (linked || connecting) return
        val c = cfg
        val host = c.server.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        if (host.isEmpty()) return
        val url = "https://$host/ws".toHttpUrlOrNull()
            ?.newBuilder()?.addQueryParameter("token", c.token)?.build() ?: return
        connecting = true
        socket = laptopClient.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connecting = false
                setLinked(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServer(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connecting = false
                setLinked(false)
                if (code == 4401 && !warnedToken) {
                    warnedToken = true
                    cb.onSay("The laptop rejected my pairing token. Please check the settings.")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connecting = false
                setLinked(false)
            }
        })
    }

    private fun handleServer(text: String) {
        try {
            val j = JSONObject(text)
            when (j.optString("type")) {
                "state" -> cb.onState(j.optString("value"))
                "say" -> cb.onSay(j.optString("text"))
                "confirm" -> cb.onConfirm(j.optString("cmd"))
            }
        } catch (e: Exception) {
            // ignore malformed messages
        }
    }

    // ---------------- public API ----------------

    fun ask(text: String) {
        val s = socket
        if (linked && s != null) {
            cb.onState("thinking")
            val ok = s.send(JSONObject().put("type", "text").put("text", text).toString())
            if (!ok) askDirect(text)
        } else {
            askDirect(text)
        }
    }

    fun confirm(ok: Boolean) {
        socket?.send(JSONObject().put("type", "confirm").put("ok", ok).toString())
    }

    // ---------------- direct NVIDIA chat ----------------

    private fun askDirect(text: String) {
        val c = cfg
        if (c.apiKey.isBlank()) {
            cb.onSay("I can't reach your laptop, and there is no NVIDIA key set. Open settings to add one.")
            return
        }
        cb.onState("thinking")
        io.execute {
            try {
                val msgs = JSONArray()
                msgs.put(JSONObject().put("role", "system").put("content", SYSTEM_DIRECT))
                synchronized(lock) {
                    history.add(JSONObject().put("role", "user").put("content", text))
                    while (history.size > 20) history.removeAt(0)
                    for (m in history) msgs.put(m)
                }
                val body = JSONObject()
                    .put("model", c.model)
                    .put("messages", msgs)
                    .put("temperature", 0.4)
                    .put("max_tokens", 600)
                val req = Request.Builder()
                    .url("https://integrate.api.nvidia.com/v1/chat/completions")
                    .header("Authorization", "Bearer ${c.apiKey}")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                apiClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        cb.onSay("NVIDIA returned error ${resp.code}.")
                    } else {
                        val msg = JSONObject(raw).getJSONArray("choices")
                            .getJSONObject(0).getJSONObject("message")
                        var answer = if (msg.isNull("content")) "" else msg.getString("content")
                        answer = answer.replace(Regex("(?s)<think>.*?</think>"), "").trim()
                        if (answer.isEmpty()) answer = "Sorry, I got an empty answer."
                        synchronized(lock) {
                            history.add(JSONObject().put("role", "assistant").put("content", answer))
                        }
                        cb.onSay(answer)
                    }
                }
            } catch (e: Exception) {
                cb.onSay("Something went wrong: ${e.message ?: "network error"}")
            } finally {
                cb.onState("idle")
            }
        }
    }

    companion object {
        private const val SYSTEM_DIRECT =
            "You are Hinton, a friendly voice assistant shown as a cute astronaut robot face on the " +
            "user's phone. Your replies are spoken aloud, so keep them short and plain: no markdown, " +
            "no lists. The user's laptop is not connected right now, so you cannot run terminal " +
            "commands. If asked to, say the laptop is offline."
    }
}

/**
 * Client used ONLY for the connection to your own laptop server. The server uses a
 * self-signed certificate, so this client accepts it. Don't use it for anything else.
 */
private fun buildLaptopClient(): OkHttpClient {
    val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
    return OkHttpClient.Builder()
        .sslSocketFactory(ctx.socketFactory, trustAll)
        .hostnameVerifier { _, _ -> true }
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
}
