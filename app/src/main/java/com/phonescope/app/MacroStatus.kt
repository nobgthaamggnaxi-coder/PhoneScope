package com.phonescope.app

import android.os.Handler
import android.os.Looper

/** Macro status shown in the app. */
object MacroStatus {
    @Volatile var screenRunning = false
    @Volatile var last = "Stopped"
        private set
    var listener: ((String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    val running get() = screenRunning

    fun post(s: String) {
        last = s
        main.post { listener?.invoke(s) }
    }
}
