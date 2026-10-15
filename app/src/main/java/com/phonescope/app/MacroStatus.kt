package com.phonescope.app

import android.os.Handler
import android.os.Looper

/** Status shared by both detection services and shown in the app. */
object MacroStatus {
    @Volatile var screenRunning = false
    @Volatile var logRunning = false
    @Volatile var last = "Stopped"
        private set
    var listener: ((String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    val running get() = screenRunning || logRunning

    fun post(s: String) {
        last = s
        main.post { listener?.invoke(s) }
    }
}
