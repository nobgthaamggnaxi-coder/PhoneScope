package com.phonescope.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var statusText: TextView

    private lateinit var biomeWebhook: TextInputEditText
    private lateinit var merchantWebhook: TextInputEditText
    private lateinit var privateServer: TextInputEditText
    private lateinit var pingUser: TextInputEditText
    private lateinit var pingRole: TextInputEditText
    private lateinit var biomes: TextInputEditText
    private lateinit var notifyBiomes: TextInputEditText
    private lateinit var pingBiomes: TextInputEditText
    private lateinit var merchants: TextInputEditText
    private lateinit var biomeRegion: TextInputEditText
    private lateinit var chatRegion: TextInputEditText
    private lateinit var interval: TextInputEditText
    private lateinit var pingMerchants: MaterialSwitch

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) {
                startMonitor(r.resultCode, data)
                toast("Macro started – opening Roblox")
                openRoblox()
            } else {
                toast("Screen capture permission was denied")
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestCapture() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        MacroStatus.listener = { statusText.text = it }
        statusText.text = MacroStatus.last
    }

    override fun onPause() {
        MacroStatus.listener = null
        super.onPause()
    }

    // ---------- actions ----------

    private fun save(): Boolean {
        prefs.biomeWebhook = biomeWebhook.value()
        prefs.merchantWebhook = merchantWebhook.value()
        prefs.privateServer = privateServer.value()
        prefs.pingUserId = pingUser.value()
        prefs.pingRoleId = pingRole.value()
        prefs.biomes = biomes.value().ifBlank { Prefs.DEFAULT_BIOMES }
        prefs.notifyBiomes = notifyBiomes.value()
        prefs.pingBiomes = pingBiomes.value()
        prefs.merchants = merchants.value()
        prefs.biomeRegion = biomeRegion.value().ifBlank { "0,0,100,100" }
        prefs.chatRegion = chatRegion.value().ifBlank { "0,0,100,100" }
        prefs.scanIntervalSec = interval.value().toIntOrNull() ?: 2
        prefs.pingMerchants = pingMerchants.isChecked

        if (!prefs.biomeWebhook.startsWith("https://discord.com/api/webhooks/") &&
            !prefs.biomeWebhook.startsWith("https://discordapp.com/api/webhooks/") &&
            !prefs.biomeWebhook.startsWith("https://ptb.discord.com/api/webhooks/") &&
            !prefs.biomeWebhook.startsWith("https://canary.discord.com/api/webhooks/")
        ) {
            biomeWebhook.error = "Paste a Discord webhook URL (https://discord.com/api/webhooks/…)"
            return false
        }
        return true
    }

    private fun startMacro() {
        if (!save()) return
        if (MacroStatus.running) {
            toast("Already running")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestCapture()
        }
    }


    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        captureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun testWebhook(merchant: Boolean) {
        if (!save()) return
        val url = if (merchant) prefs.merchantWebhookOrDefault() else prefs.biomeWebhook
        Alerts.test(prefs, url, if (merchant) "merchant" else "biome") { ok, msg ->
            toast(if (ok) "Webhook works! Check Discord ✅" else "Failed: $msg")
        }
    }

    private fun openRoblox() {
        packageManager.getLaunchIntentForPackage("com.roblox.client")?.let { startActivity(it) }
            ?: toast("Roblox is not installed")
    }

    // ---------- UI (built in code to keep the project small) ----------

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        val scroll = ScrollView(this).apply { addView(col) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        col.addView(TextView(this).apply {
            text = "PhoneScope"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "Sol's RNG biome + merchant notifier for Discord"
            alpha = 0.7f
        })

        // Status card + main controls
        val statusCard = card(col)
        statusText = TextView(this).apply { textSize = 15f; text = MacroStatus.last }
        statusCard.addView(label("Status"))
        statusCard.addView(statusText)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        row.addView(button("▶ Start") { startMacro() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        row.addView(button("■ Stop", outlined = true) { stopMonitor() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusCard.addView(row)

        // Discord
        val discord = card(col)
        discord.addView(label("Discord webhooks"))
        biomeWebhook = input(discord, "Biome webhook URL", prefs.biomeWebhook,
            "Server Settings → Integrations → Webhooks → New Webhook → Copy URL")
        merchantWebhook = input(discord, "Merchant webhook URL (optional)", prefs.merchantWebhook,
            "Leave empty to use the biome webhook")
        privateServer = input(discord, "Private server link", prefs.privateServer,
            "https://www.roblox.com/share?code=…&type=Server")
        pingUser = input(discord, "Your Discord user ID to ping (optional)", prefs.pingUserId,
            "Settings → Advanced → Developer Mode, then long-press your name → Copy User ID", number = true)
        pingRole = input(discord, "Role ID to ping (optional)", prefs.pingRoleId,
            "For a macro server: long-press a role → Copy Role ID", number = true)
        val testRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        testRow.addView(button("Test biome", outlined = true) { testWebhook(false) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        testRow.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        testRow.addView(button("Test merchant", outlined = true) { testWebhook(true) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        discord.addView(testRow)

        // Biomes
        val biomeCard = card(col)
        biomeCard.addView(label("Biomes"))
        biomes = input(biomeCard, "Biomes to detect", prefs.biomes, "Comma separated, as written in game", multiline = true)
        notifyBiomes = input(biomeCard, "Send a message for", prefs.notifyBiomes, "Remove NORMAL etc. to reduce spam", multiline = true)
        pingBiomes = input(biomeCard, "Ping me for", prefs.pingBiomes, "Rare biomes that should @ping you", multiline = true)

        // Merchants
        val merchantCard = card(col)
        merchantCard.addView(label("Merchants"))
        merchants = input(merchantCard, "Merchants to detect", prefs.merchants, "Matches \"<name> has arrived\" in chat")
        pingMerchants = switch(merchantCard, "Ping me for merchants", prefs.pingMerchants)

        // Scanner
        val scanCard = card(col)
        scanCard.addView(label("Screen reading"))
        biomeRegion = input(scanCard, "Biome text area (left,top,right,bottom %)", prefs.biomeRegion,
            "0,0,100,100 = whole screen. Narrow it to where the biome name shows for fewer false alerts")
        chatRegion = input(scanCard, "Chat area (left,top,right,bottom %)", prefs.chatRegion,
            "Keep chat open in game. Example top-left chat: 0,0,45,45")
        interval = input(scanCard, "Scan every (seconds)", prefs.scanIntervalSec.toString(), "1–30", number = true)

        col.addView(button("Save settings", outlined = true) { if (save()) toast("Saved") },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        col.addView(TextView(this).apply {
            alpha = 0.7f
            setPadding(0, dp(16), 0, 0)
            text = "How it works: tap Start, allow screen recording (\"Entire screen\" or Roblox), then play " +
                "Sol's RNG. PhoneScope reads the biome name and merchant chat messages from the screen on your " +
                "phone – no screenshots are saved or sent. Keep the chat visible to catch Mari / Jester. " +
                "Disable battery optimisation for this app so it keeps running."
        })
        return scroll
    }

    private fun card(parent: LinearLayout): LinearLayout {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val card = MaterialCardView(this).apply { radius = dp(16).toFloat(); addView(inner) }
        parent.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(14) })
        return inner
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(6))
    }

    private fun input(
        parent: LinearLayout, hint: String, value: String, helper: String,
        multiline: Boolean = false, number: Boolean = false,
    ): TextInputEditText {
        val til = TextInputLayout(this).apply {
            this.hint = hint
            helperText = helper
        }
        val et = TextInputEditText(til.context).apply {
            setText(value)
            inputType = when {
                number -> InputType.TYPE_CLASS_NUMBER
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            if (!multiline) maxLines = 1
        }
        til.addView(et)
        parent.addView(til, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(8) })
        return et
    }

    private fun switch(parent: LinearLayout, text: String, checked: Boolean) = MaterialSwitch(this).apply {
        this.text = text
        isChecked = checked
        parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun button(text: String, outlined: Boolean = false, onClick: () -> Unit): MaterialButton {
        val b = if (outlined) MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        else MaterialButton(this)
        b.text = text
        b.setOnClickListener { onClick() }
        return b
    }

    private fun TextInputEditText.value() = text?.toString()?.trim() ?: ""
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
