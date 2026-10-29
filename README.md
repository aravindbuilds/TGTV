# 🎬 TGTV — Your personal cloud cinema.

<p align="center">
  <b>Your personal cloud cinema.</b><br/>
  Turn your Telegram account into a private Netflix — stream your saved movies and series on Android with a beautiful, dark, cinema-first UI.
</p>

TGTV is an Android app that streams videos straight from your own Telegram chats (Saved Messages, channels, groups or a private storage bot) using **TDLib in-process streaming** — no third-party bots, no middle servers, no uploads to anyone else's cloud. **Your Telegram account is the server.**

Brought to you by the maker of [TelegramVideoTV](https://github.com/aravindbuilds/TelegramVideoTV) — the Android TV edition. TGTV is the phone companion with the same DNA: private, fast, and Netflix-simple.

---

## ✨ Features

- **📺 Netflix-style Home** — cinematic hero banner with TMDB artwork, ratings, synopses and cast, plus a smooth Continue Watching rail
- **🔍 Automatic metadata** — titles are matched against TMDB for posters, backdrops, ratings, runtimes, genres, story lines and cast photos (bring your own free TMDB key)
- **⏯️ Resume & Continue Watching** — pick up exactly where you left off, on any file, with per-title progress tracking
- **⚡ True streaming** — watch instantly via TDLib's in-process downloader; files never have to fully download first
- **⬇️ Downloads** — save any video to your gallery or a folder of your choice, with progress notifications
- **💬 Group by chat** — browse your library per chat with swipeable card rows, or one flat grid with sort options (newest, oldest, A–Z, largest)
- **💬 Subtitles** — built-in Telegram subtitles plus automatic OpenSubtitles.com lookup by hash
- **🎞️ Player built for movies** — ExoPlayer with speed control, aspect-ratio modes, subtitle styling, gesture seek and auto-hiding controls
- **💾 My List** — bookmark titles for later
- **🔒 Private by design** — no analytics, no trackers, no accounts beyond your own Telegram login

## 📲 Download

Grab the latest APK from [**Releases**](../../releases) — install, log in with your Telegram API credentials, and your library builds itself.

## 🚀 Getting started

1. **Get Telegram API credentials** (one time, free) at [my.telegram.org](https://my.telegram.org) → *API development tools*. You need the **api_id** and **api_hash**.
2. **Install TGTV** from the Releases page and enter your API ID / Hash on first launch (or bake them into your own build — see below).
3. **Sign in** with your phone number and the Telegram login code.
4. TGTV scans your chats for videos and builds **My Library** automatically. Pin your favorite movie channels or forward files to *Saved Messages* to organize them.
5. *(Optional)* Add a **TMDB key** in Settings for posters, ratings and cast; add an **OpenSubtitles key** for auto subtitles; pick a download folder; tune cache retention.

### Building from source

```bash
git clone https://github.com/aravindbuilds/TGTV.git
cd TGTV
./gradlew assembleDebug        # APK at app/build/outputs/apk/debug/
```

**Baking your API credentials (optional):** create `tgtv-secrets.properties` next to the project folder:

```properties
api_id=12345678
api_hash=your_api_hash_here
```

The build XOR-obfuscates them into the app so the login screen is pre-filled (and in release builds, skipped entirely). The file is never committed — it stays out of the repo by design.

## 🧱 Under the hood

| Layer | Tech |
| --- | --- |
| Telegram | [TDLib](https://core.telegram.org/tdlib) JNI, in-process streaming & download |
| Playback | [Media3 / ExoPlayer](https://github.com/androidx/media) |
| UI | Native Android (Kotlin, Material), dark cinema theme |
| Metadata | TMDB API with on-disk caching |
| Subtitles | Telegram embedded tracks + OpenSubtitles.com |

## 🔐 Privacy

TGTV talks to exactly three services: **Telegram** (your chats and files), **TMDB** (poster/metadata lookups, only if a key is set) and **OpenSubtitles.com** (subtitle lookups, only if a key is set). No analytics SDKs, no crash reporters, no ad networks. Watch progress, settings and bookmarks live only on your device.

## 📺 Sister project

- [**TelegramVideoTV**](https://github.com/aravindbuilds/TelegramVideoTV) — the same private-streaming idea built for **Android TV**, with QR / bot login and a leanback UI.

## ⚖️ Disclaimer

TGTV streams files **from your own Telegram account**. You are responsible for the content you access and for complying with Telegram's Terms of Service and your local laws. This project is not affiliated with Telegram or TMDB.

## 📄 License

[MIT](LICENSE) © 2026 Aravind E S
