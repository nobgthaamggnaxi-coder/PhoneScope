package com.phonescope.app

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Runs shell commands with "shell" (ADB) rights through Shizuku.
 * Shell can read every app's logcat output and /sdcard/Android/data, which normal apps can't.
 */
object ShizukuShell {
    const val ROBLOX_DIR = "/sdcard/Android/data/com.roblox.client"

    fun isRunning(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    fun hasPermission(): Boolean = isRunning() && !Shizuku.isPreV11() &&
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    /** Starts a process as shell. Shizuku keeps newProcess non-public, so it is called by reflection. */
    fun start(vararg cmd: String): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, arrayOf(*cmd), null, null) as Process
    }

    /** Runs `sh -c script` and returns its output. */
    fun run(script: String): String {
        val p = start("sh", "-c", script)
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        return out
    }

    /** Newest Roblox log file, or null if Roblox keeps its logs where shell can't see them. */
    fun newestRobloxLog(): String? =
        run("find $ROBLOX_DIR -type f -name '*.log' -exec ls -t {} + 2>/dev/null | head -n 1")
            .trim().ifEmpty { null }

    /** Report for the "Check Shizuku & logs" button. */
    fun diagnose(): String {
        if (!isRunning()) return "❌ Shizuku is not running. Open the Shizuku app and start it."
        if (!hasPermission()) return "❌ PhoneScope doesn't have Shizuku permission yet. Tap Start to allow it."
        val sb = StringBuilder("✅ Shizuku works\n\n")

        val dir = run("ls $ROBLOX_DIR 2>&1").trim()
        sb.append("Roblox data folder: ").append(if (dir.contains("No such")) "not found" else "found").append('\n')

        val files = run("find $ROBLOX_DIR -type f -name '*.log' 2>/dev/null | wc -l").trim()
        sb.append("Roblox log files: ").append(files.ifEmpty { "0" }).append('\n')
        newestRobloxLog()?.let { f ->
            sb.append("Newest: ").append(f.substringAfterLast('/')).append('\n')
            sb.append("Biome lines in it: ")
                .append(run("grep -c BloxstrapRPC '$f' 2>/dev/null").trim().ifEmpty { "0" }).append('\n')
        }

        val rbx = run("logcat -d 2>/dev/null | grep -ci roblox").trim()
        val rpc = run("logcat -d -v raw 2>/dev/null | grep -c BloxstrapRPC").trim()
        sb.append("Roblox lines in logcat: ").append(rbx.ifEmpty { "0" }).append('\n')
        sb.append("Biome lines in logcat: ").append(rpc.ifEmpty { "0" }).append('\n')

        val last = run(
            "{ logcat -d -v raw 2>/dev/null | grep BloxstrapRPC; " +
                "f=\$(find $ROBLOX_DIR -type f -name '*.log' -exec ls -t {} + 2>/dev/null | head -n 1); " +
                "[ -n \"\$f\" ] && grep BloxstrapRPC \"\$f\"; } | tail -n 1"
        )
        val biome = LogParser.biome(last)
        sb.append("\nLatest biome found: ").append(biome ?: "none")
        sb.append(
            if (biome != null) "\n\n✅ Log mode should work."
            else "\n\n⚠️ No biome info found yet. Play Sol's RNG for a minute, then check again. " +
                "If it stays at none, use screen mode instead."
        )
        return sb.toString()
    }
}

/** Reads Sol's RNG's Discord-status messages, e.g. [BloxstrapRPC] {"command":"SetRichPresence","data":{"largeImage":{"hoverText":"STARFALL"}}} */
object LogParser {
    private val hover = Regex("\"largeImage\"\\s*:\\s*\\{[^}]*?\"hoverText\"\\s*:\\s*\"([^\"]+)\"")

    fun biome(line: String): String? {
        if (!line.contains("BloxstrapRPC")) return null
        val h = hover.findAll(line).lastOrNull()?.groupValues?.get(1)?.trim() ?: return null
        return h.uppercase().ifEmpty { null }
    }
}
