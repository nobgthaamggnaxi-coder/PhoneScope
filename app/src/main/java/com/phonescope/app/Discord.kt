package com.phonescope.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
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
                    val (code, body) = post(url, payload)
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

    private fun post(url: String, payload: JSONObject): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "PhoneScope/1.0")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
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

    private fun key(biome: String) = biome.uppercase().filter(Char::isLetter)

    private val biomeColors = mapOf(
        "NORMAL" to 0xFFFFFF, "WINDY" to 0x9AE5FF, "RAINY" to 0x027CBD, "SNOWY" to 0xDCEFFF,
        "SANDSTORM" to 0xF4C27C, "HELL" to 0x5C1219, "STARFALL" to 0x6784E0, "HEAVEN" to 0xFFF4B5,
        "CORRUPTION" to 0x9042FF, "NULL" to 0x000000, "GLITCHED" to 0x65FF65,
        "DREAMSPACE" to 0xFF7DFF, "CYBERSPACE" to 0x00E5FF,
    )
    private val merchantColors = mapOf("mari" to 0xFFF4A3, "jester" to 0xA352FF)

    // Icons from the Sol's RNG wiki (sols-rng.fandom.com), shown as the embed thumbnail.
    private const val WIKI = "https://static.wikia.nocookie.net/sol-rng/images"
    private val biomeIcons = mapOf(
        "NORMAL" to "$WIKI/0/07/DISCORICH-NORMAL.png",
        "WINDY" to "$WIKI/9/98/DISCORICH-WINDY.png",
        "RAINY" to "$WIKI/7/77/DISCORICH-RAINY.png",
        "SNOWY" to "$WIKI/8/86/DISCORICH-SNOWY.png",
        "SANDSTORM" to "$WIKI/9/92/DISCORICH-SANDSTORM.png",
        "HELL" to "$WIKI/b/b9/DISCORICH-HELL.png",
        "STARFALL" to "$WIKI/1/11/DISCORICH-STARFALL.png",
        "CORRUPTION" to "$WIKI/5/5f/DISCORICH-CORRUPTION.png",
        "GLITCHED" to "$WIKI/d/dc/DISCORICH-BROKEN.png",
        "HEAVEN" to "$WIKI/f/f9/Heaven.png",
        "NULL" to "$WIKI/b/bb/Null_Biome_%28BloxTrap%29.png",
        "DREAMSPACE" to "$WIKI/7/7a/DreamspaceRichPresenceFIX.png",
        "CYBERSPACE" to "$WIKI/e/e9/CyberspaceNew.png/revision/latest/scale-to-width-down/512",
    )
    private val merchantIcons = mapOf(
        "mari" to "$WIKI/e/e2/MariRender.png/revision/latest/scale-to-width-down/512",
        "jester" to "$WIKI/5/5e/JesterRender.png/revision/latest/scale-to-width-down/512",
    )

    private fun JSONObject.thumbnail(url: String?) = apply {
        if (url != null) put("thumbnail", JSONObject().put("url", url))
    }

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

    fun biomeStarted(p: Prefs, biome: String) {
        val (_, time) = stamp()
        val embed = JSONObject().apply {
            put("title", "Biome Started - $biome")
            put("description", "**$time**")
            put("color", biomeColors[key(biome)] ?: 0x5865F2)
            thumbnail(biomeIcons[key(biome)])
            put("timestamp", Instant.now().toString())
            if (p.privateServer.isNotBlank()) {
                put("fields", JSONArray().put(JSONObject().put("name", "Private Server").put("value", p.privateServer)))
            }
            put("footer", footer())
        }
        val content = if (p.pings(biome)) "${p.mentionText()} **$biome** has started!".trim() else ""
        Discord.send(p.biomeWebhook, payload("Biome Alert", content, embed))
    }

    fun biomeEnded(p: Prefs, biome: String) {
        val (_, time) = stamp()
        val embed = JSONObject().apply {
            put("title", "Biome Ended - $biome")
            put("description", "**$time**")
            put("color", biomeColors[key(biome)] ?: 0x5865F2)
            thumbnail(biomeIcons[key(biome)])
            put("footer", footer())
        }
        Discord.send(p.biomeWebhook, payload("Biome Alert", "", embed))
    }

    fun merchant(p: Prefs, name: String, line: String) {
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
            thumbnail(merchantIcons[name.lowercase()])
            put("fields", fields)
            put("timestamp", Instant.now().toString())
            put("footer", footer())
        }
        val content = if (p.pingMerchants) "${p.mentionText()} **$name** is here!".trim() else ""
        Discord.send(p.merchantWebhookOrDefault(), payload("$name Alert", content, embed))
    }

    fun test(p: Prefs, url: String, label: String, onResult: (Boolean, String) -> Unit) {
        val embed = JSONObject().apply {
            put("title", "PhoneScope connected ✅")
            put("description", "This is the **$label** webhook. Alerts will appear here.")
            put("color", 0x57F287)
            put("footer", footer())
        }
        Discord.send(url, payload("PhoneScope", p.mentionText(), embed), onResult)
    }

    fun status(p: Prefs, text: String) {
        val embed = JSONObject().put("title", text).put("color", 0x5865F2)
            .put("description", "**${stamp().second}**").put("footer", footer())
        Discord.send(p.biomeWebhook, payload("Biome Alert", "", embed))
    }
}
