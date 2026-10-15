package com.solscope.mobile

import android.content.Context
import android.graphics.RectF

/** All user settings, stored in SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("solscope", Context.MODE_PRIVATE)

    private fun str(key: String, def: String) = sp.getString(key, def) ?: def
    private fun put(key: String, value: String) = sp.edit().putString(key, value).apply()

    var biomeWebhook: String
        get() = str("biome_webhook", "")
        set(v) = put("biome_webhook", v.trim())

    /** Optional separate webhook for merchants. Falls back to the biome webhook when blank. */
    var merchantWebhook: String
        get() = str("merchant_webhook", "")
        set(v) = put("merchant_webhook", v.trim())

    var privateServer: String
        get() = str("private_server", "")
        set(v) = put("private_server", v.trim())

    var pingUserId: String
        get() = str("ping_user", "")
        set(v) = put("ping_user", v.trim())

    var pingRoleId: String
        get() = str("ping_role", "")
        set(v) = put("ping_role", v.trim())

    /** Biomes to detect (comma separated, UPPERCASE as shown in game). */
    var biomes: String
        get() = str("biomes", DEFAULT_BIOMES)
        set(v) = put("biomes", v)

    /** Biomes that get a webhook message. */
    var notifyBiomes: String
        get() = str("notify_biomes", DEFAULT_BIOMES)
        set(v) = put("notify_biomes", v)

    /** Biomes that also ping the user / role. */
    var pingBiomes: String
        get() = str("ping_biomes", "GLITCHED, DREAMSPACE, CYBERSPACE")
        set(v) = put("ping_biomes", v)

    var merchants: String
        get() = str("merchants", "Mari, Jester")
        set(v) = put("merchants", v)

    var pingMerchants: Boolean
        get() = sp.getBoolean("ping_merchants", true)
        set(v) = sp.edit().putBoolean("ping_merchants", v).apply()

    var attachScreenshot: Boolean
        get() = sp.getBoolean("attach_shot", true)
        set(v) = sp.edit().putBoolean("attach_shot", v).apply()

    /** Screen area (percent: left,top,right,bottom) where the biome name is shown. */
    var biomeRegion: String
        get() = str("biome_region", "0,0,100,100")
        set(v) = put("biome_region", v)

    /** Screen area (percent) where the chat is shown. */
    var chatRegion: String
        get() = str("chat_region", "0,0,100,100")
        set(v) = put("chat_region", v)

    var scanIntervalSec: Int
        get() = sp.getInt("scan_interval", 2)
        set(v) = sp.edit().putInt("scan_interval", v.coerceIn(1, 30)).apply()

    fun merchantWebhookOrDefault() = merchantWebhook.ifBlank { biomeWebhook }

    fun biomeList() = splitList(biomes).map { it.uppercase() }
    fun notifies(biome: String) = splitList(notifyBiomes).any { it.equals(biome, true) }
    fun pings(biome: String) = splitList(pingBiomes).any { it.equals(biome, true) }
    fun merchantList() = splitList(merchants)

    /** Discord mention text, e.g. "<@123> <@&456>", or "" if nothing configured. */
    fun mentionText(): String = buildList {
        if (pingUserId.isNotBlank()) add("<@${pingUserId.filter(Char::isDigit)}>")
        if (pingRoleId.isNotBlank()) add("<@&${pingRoleId.filter(Char::isDigit)}>")
    }.joinToString(" ")

    companion object {
        const val DEFAULT_BIOMES =
            "NORMAL, WINDY, RAINY, SNOWY, SAND STORM, HELL, STARFALL, HEAVEN, CORRUPTION, NULL, " +
                "GLITCHED, DREAMSPACE, CYBERSPACE"

        fun splitList(s: String) = s.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

        /** Parses "l,t,r,b" percentages into a fractional RectF (0..1). Invalid input → whole screen. */
        fun parseRegion(s: String): RectF {
            val n = s.split(',').mapNotNull { it.trim().toFloatOrNull() }
            if (n.size != 4) return RectF(0f, 0f, 1f, 1f)
            val r = RectF(
                n[0].coerceIn(0f, 100f) / 100f, n[1].coerceIn(0f, 100f) / 100f,
                n[2].coerceIn(0f, 100f) / 100f, n[3].coerceIn(0f, 100f) / 100f
            )
            return if (r.width() < 0.02f || r.height() < 0.02f) RectF(0f, 0f, 1f, 1f) else r
        }
    }
}
