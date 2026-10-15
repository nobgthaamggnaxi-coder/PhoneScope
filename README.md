# SolScope Mobile

Android app that watches **Sol's RNG** on your phone and posts to Discord:

- **Biome Started - X** / **Biome Ended - X** (with your private server link)
- **MERCHANT SPOTTED - Mari Found** / **Jester Found** (with a join link)
- Optional @ping of you or a role for rare biomes and merchants
- Optional screenshot attached to each alert

It reads the screen with on-device OCR (Google ML Kit) every few seconds. No root needed, nothing is
clicked or typed in the game – it only watches and notifies.

## 1. Get the APK

**Option A – GitHub (no tools needed on your PC)**
1. Create a new GitHub repository and upload everything in this folder (keep the `.github` folder).
2. Open the **Actions** tab → **Build APK** → wait ~5 min.
3. Open the finished run → download **SolScopeMobile-apk** → unzip → copy `app-debug.apk` to your phone.

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

## 3. App setup

1. Paste the webhook URL(s), private server link and IDs. Tap **Test biome** / **Test merchant** –
   a green "connected" message should appear in Discord.
2. Choose which biomes to message about and which ones ping you
   (default ping: GLITCHED, DREAMSPACE, CYBERSPACE).
3. Tap **Start**, allow screen recording (**Entire screen**, or "A single app" → Roblox).
   Roblox opens automatically.
4. Disable battery optimisation for SolScope (Settings → Apps → SolScope → Battery → Unrestricted).

## Tuning the screen areas

Areas are `left,top,right,bottom` in percent of the screen (landscape while playing).

- Watch the **Status** box in the app – it shows `OCR sees: …`. Switch back to it to check.
- **Biome area**: narrow it to where the biome name is displayed, e.g. `0,80,40,100` for bottom-left.
  This stops random capital words on screen from being read as a biome.
- **Chat area**: keep the chat window open in game and set the area over it, e.g. `0,0,45,45`.
  Merchants are detected from the chat message "**Mari has arrived on the island**" / "**Jester has arrived…**".

A biome must be seen on two scans in a row before it is announced, and each merchant has a 5 minute cooldown.
