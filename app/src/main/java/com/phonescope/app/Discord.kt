package com.phonescope.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.Executors

/** Sends Discord webhook messages one at a time (keeps order, respects rate limits). */
object Discord {
    private const val TAG = "PhoneScope"
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun send(
        url: String,
        payload: JSONObject,
        image: ByteArray? = null,
        onResult: ((ok: Boolean, msg: String) -> Unit)? = null,
    ) {
        if (!url.startsWith("https://")) {
            onResult?.let { cb -> main.post { cb(false, "Webhook URL is missing or invalid") } }
            return
        }
        exec.execute {
            var result = false to "Unknown error"
            for (attempt in 0 until 3) {
                result = try {
                    val (code, body) = post(url, payload, image)
                    when {
                        code in 200..299 -> true to "Sent"
                        code == 429 -> {
                            val wait = runCatching { JSONObject(body).getDouble("retry_after") }.getOrDefault(2.0)
                            Thread.sleep((wait * 1000).toLong() + 250)
                            false to "Rate limited"
                        }
                        else -> false to "Discord said $code: ${body.take(200)}"
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "webhook failed", e)
                    false to (e.message ?: e.javaClass.simpleName)
                }
                if (result.first || result.second != "Rate limited") break
            }
            onResult?.let { cb -> main.post { cb(result.first, result.second) } }
        }
    }

    private fun post(url: String, payload: JSONObject, image: ByteArray?): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "PhoneScope/1.0")
        if (image == null) {
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        } else {
            val boundary = "----PhoneScope${System.currentTimeMillis()}"
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            DataOutputStream(conn.outputStream).use { out ->
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"payload_json\"\r\n")
                out.writeBytes("Content-Type: application/json\r\n\r\n")
                out.write(payload.toString().toByteArray())
                out.writeBytes("\r\n--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"files[0]\"; filename=\"screenshot.jpg\"\r\n")
                out.writeBytes("Content-Type: image/jpeg\r\n\r\n")
                out.write(image)
                out.writeBytes("\r\n--$boundary--\r\n")
            }
        }
        val code = conn.responseCode
        val body = (if (code >= 400) conn.errorStream else conn.inputStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return code to body
    }
}

/** Builds the embeds (styled like the PC macros). */
object Alerts {
    const val FOOTER = "PhoneScope | Release v1.0.0"

    private val biomeColors = mapOf(
        "NORMAL" to 0xFFFFFF, "WINDY" to 0x9AE5FF, "RAINY" to 0x027CBD, "SNOWY" to 0xDCEFFF,
        "SAND STORM" to 0xF4C27C, "HELL" to 0x5C1219, "STARFALL" to 0x6784E0, "HEAVEN" to 0xFFF4B5,
        "CORRUPTION" to 0x9042FF, "NULL" to 0x000000, "GLITCHED" to 0x65FF65,
        "DREAMSPACE" to 0xFF7DFF, "CYBERSPACE" to 0x00E5FF,
    )
    private val merchantColors = mapOf("mari" to 0xFFF4A3, "jester" to 0xA352FF)

    private fun stamp(): Pair<Long, String> {
        val now = System.currentTimeMillis() / 1000
        return now to "<t:$now:T> (<t:$now:R>)"
    }

    private fun payload(username: String, content: String, embed: JSONObject) = JSONObject().apply {
        put("username", username)
        put("content", content)
        put("embeds", JSONArray().put(embed))
    }

    private fun footer() = JSONObject().put("text", FOOTER)

    fun biomeStarted(p: Prefs, biome: String, shot: ByteArray?) {
        val (_, time) = stamp()
        val embed = JSONObject().apply {
            put("title", "Biome Started - $biome")
            put("description", "**$time**")
            put("color", biomeColors[biome] ?: 0x5865F2)
            put("timestamp", Instant.now().toString())
            if (p.privateServer.isNotBlank()) {
                put("fields", JSONArray().put(JSONObject().put("name", "Private Server").put("value", p.privateServer)))
            }
            if (shot != null) put("image", JSONObject().put("url", "attachment://screenshot.jpg"))
            put("footer", footer())
        }
        val content = if (p.pings(biome)) "${p.mentionText()} **$biome** has started!".trim() else ""
        Discord.send(p.biomeWebhook, payload("Biome Alert", content, embed), shot)
    }

    fun biomeEnded(p: Prefs, biome: String) {
        val (_, time) = stamp()
        val embed = JSONObject().apply {
            put("title", "Biome Ended - $biome")
            put("description", "**$time**")
            put("color", biomeColors[biome] ?: 0x5865F2)
            put("footer", footer())
        }
        Discord.send(p.biomeWebhook, payload("Biome Alert", "", embed))
    }

    fun merchant(p: Prefs, name: String, line: String, shot: ByteArray?) {
        val (_, time) = stamp()
        val fields = JSONArray()
            .put(JSONObject().put("name", "Time").put("value", time))
            .put(JSONObject().put("name", "Message").put("value", line.take(1000).ifBlank { "-" }))
        if (p.privateServer.isNotBlank()) {
            fields.put(JSONObject().put("name", "Join").put("value", "[Click me to join](${p.privateServer})"))
        }
        val embed = JSONObject().apply {
            put("title", "MERCHANT SPOTTED - $name Found")
            put("color", merchantColors[name.lowercase()] ?: 0xF1C40F)
            put("fields", fields)
            put("timestamp", Instant.now().toString())
            if (shot != null) put("image", JSONObject().put("url", "attachment://screenshot.jpg"))
            put("footer", footer())
        }
        val content = if (p.pingMerchants) "${p.mentionText()} **$name** is here!".trim() else ""
        Discord.send(p.merchantWebhookOrDefault(), payload("$name Alert", content, embed), shot)
    }

    fun test(p: Prefs, url: String, label: String, onResult: (Boolean, String) -> Unit) {
        val embed = JSONObject().apply {
            put("title", "PhoneScope connected ✅")
            put("description", "This is the **$label** webhook. Alerts will appear here.")
            put("color", 0x57F287)
            put("footer", footer())
        }
        Discord.send(url, payload("PhoneScope", p.mentionText(), embed), null, onResult)
    }

    fun status(p: Prefs, text: String) {
        val embed = JSONObject().put("title", text).put("color", 0x5865F2)
            .put("description", "**${stamp().second}**").put("footer", footer())
        Discord.send(p.biomeWebhook, payload("Biome Alert", "", embed))
    }
}
