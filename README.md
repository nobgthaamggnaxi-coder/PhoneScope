# PhoneScope

Android app that watches **Sol's RNG** on your phone and posts to Discord:

- **Biome Started - X** / **Biome Ended - X** (with your private server link)
- **MERCHANT SPOTTED - Mari Found** / **Jester Found** (with a join link)
- Optional @ping of you or a role for rare biomes and merchants
- Biome icons and merchant pictures on every alert

**Shizuku mode (default):** follows Roblox's log output, the same status messages PC macros read.
No screen recording, no screenshots, no root. **Screen mode** (fallback) reads the screen with
on-device OCR if your phone doesn't expose the logs. Nothing is ever clicked or typed in the game.

## 1. Get the APK

**Option A – Download (easiest)**
Go to the [**Releases** page](../../releases/latest) and download `PhoneScope-v1.0.0.apk`.

**Option B – Android Studio**
Open this folder in Android Studio → *Build → Build App Bundle(s) / APK(s) → Build APK(s)*.

Install on the phone (allow "Install unknown apps" for your file manager / browser).

## 2. Discord setup (macro server)

1. In your server: **Server Settings → Integrations → Webhooks → New Webhook**.
   Pick the channel (e.g. `#biomes`), then **Copy Webhook URL**.
   Make a second one in `#merchants` if you want merchants in a separate channel.
2. For pings: **User Settings → Advanced → Developer Mode ON**, then long-press your name →
   **Copy User ID** (or long-press a role in Server Settings → Roles → **Copy Role ID**).
3. Private server link: in Sol's RNG on Roblox, Servers → your private server → **Share** → copy link.

## 3. Shizuku setup (one time, no PC needed)

1. Install **Shizuku** from the Play Store (or github.com/RikkaApps/Shizuku/releases).
2. Turn on **Developer options**: Settings → About phone → tap **Build number** 7 times.
3. Open Shizuku → **Start via Wireless debugging** → follow its pairing steps
   (connect to Wi-Fi first).
4. In PhoneScope tap **Check Shizuku & logs** → allow PhoneScope in the Shizuku popup → check again.
   - "✅ Log mode should work" → you're set.
   - "⚠️ No biome info found" → open Sol's RNG for a minute and check again. If it still finds nothing,
     your Roblox version doesn't expose the logs: turn off **Use Shizuku** to use screen mode.

Shizuku has to be started again after the phone restarts (open Shizuku → Start).

## 4. App setup

1. Paste the webhook URL(s), private server link and IDs. Tap **Test biome** / **Test merchant** –
   a green "connected" message should appear in Discord.
2. Choose which biomes to message about and which ones ping you
   (default ping: GLITCHED, DREAMSPACE, CYBERSPACE).
3. Tap **Start**. Roblox opens automatically.
   (In screen mode, allow screen recording: **Entire screen**, or "A single app" → Roblox.)
4. Disable battery optimisation for PhoneScope (Settings → Apps → PhoneScope → Battery → Unrestricted).

## Screen mode: tuning the screen areas

Areas are `left,top,right,bottom` in percent of the screen (landscape while playing).

- Watch the **Status** box in the app – it shows `OCR sees: …`. Switch back to it to check.
- **Biome area**: narrow it to where the biome name is displayed, e.g. `0,80,40,100` for bottom-left.
  This stops random capital words on screen from being read as a biome.
- **Chat area**: keep the chat window open in game and set the area over it, e.g. `0,0,45,45`.
  Merchants are detected from the chat message "**Mari has arrived on the island**" / "**Jester has arrived…**".

A biome must be seen on two scans in a row before it is announced, and each merchant has a 5 minute cooldown.
