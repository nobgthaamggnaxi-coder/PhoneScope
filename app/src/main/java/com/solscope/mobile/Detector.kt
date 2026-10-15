package com.solscope.mobile

/**
 * Turns OCR text into biome / merchant events.
 *
 * Biomes: Sol's RNG shows the biome name in CAPITALS. We only accept upper-case matches
 * (so chat like "normal" is ignored) and require the same biome on 2 scans in a row
 * before announcing a change, to filter out OCR glitches.
 *
 * Merchants: looks for "<Name> has arrived" in the chat text, with a cooldown per merchant.
 */
class Detector(
    biomes: List<String>,
    private val merchants: List<String>,
    private val merchantCooldownMs: Long = 5 * 60_000L,
) {
    data class BiomeChange(val old: String?, val new: String)
    data class MerchantSeen(val name: String, val line: String)

    // Longest first so "SAND STORM" wins over a shorter name inside it.
    private val biomePatterns = biomes.sortedByDescending { it.length }.map { b ->
        b to Regex("(^|[^A-Z])" + b.split(' ').joinToString("\\s*") { Regex.escape(it) } + "([^A-Z]|$)")
    }

    var current: String? = null
        private set
    private var candidate: String? = null
    private var candidateHits = 0
    private val lastMerchant = mutableMapOf<String, Long>()

    fun onBiomeText(lines: List<String>): BiomeChange? {
        val found = findBiome(lines) ?: return null
        if (found == current) {
            candidate = null; candidateHits = 0
            return null
        }
        if (found == candidate) candidateHits++ else { candidate = found; candidateHits = 1 }
        if (candidateHits < 2) return null
        val old = current
        current = found
        candidate = null; candidateHits = 0
        return BiomeChange(old, found)
    }

    fun findBiome(lines: List<String>): String? {
        for (raw in lines) {
            val line = normalize(raw)
            for ((name, re) in biomePatterns) if (re.containsMatchIn(line)) return name
        }
        return null
    }

    fun onChatText(lines: List<String>, now: Long = System.currentTimeMillis()): List<MerchantSeen> {
        val out = mutableListOf<MerchantSeen>()
        val joined = lines.joinToString("\n")
        for (m in merchants) {
            val re = Regex(Regex.escape(m) + "\\W{0,3}\\s*has\\s+arriv", RegexOption.IGNORE_CASE)
            val match = re.find(joined) ?: continue
            val last = lastMerchant[m.lowercase()] ?: 0L
            if (now - last < merchantCooldownMs) continue
            lastMerchant[m.lowercase()] = now
            val line = lines.firstOrNull { re.containsMatchIn(it) }
                ?: joined.substring(match.range.first).lineSequence().first()
            out += MerchantSeen(m, line.trim())
        }
        return out
    }

    /** Fixes common OCR confusions inside capital words (N0RMAL → NORMAL, NU1L → NULL). */
    private fun normalize(s: String): String =
        s.replace(Regex("(?<=[A-Z])0|0(?=[A-Z])"), "O")
            .replace(Regex("(?<=[A-Z])1|1(?=[A-Z])"), "I")
            .replace("NUIL", "NULL").replace("NUII", "NULL")
}
