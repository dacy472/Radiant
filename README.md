# Radiant

**Radiant** is a free, open-source Android app for reading webtoons, manhwa, manga, and comics. It connects to online sources through installable extensions (similar to Mihon/Tachiyomi), keeps your library and reading progress on your device, and includes a vertical webtoon reader, downloads, and library management.

> Not affiliated with Mihon or Tachiyomi.

## What Radiant does

- **Browse and search** comics from many websites via extensions
- **Save favorites** to your personal library with categories
- **Read chapters** in a smooth vertical-scroll reader (or other reading modes)
- **Track progress** — unread chapters, history, and last-read page
- **Download chapters** for offline reading
- **Check for updates** when new chapters are released
- **Migrate** a series from one source to another while keeping read/bookmark progress
- **Back up and restore** your library data

Radiant does not host any comic content. It only reads from sources you choose and install yourself.

---

## Requirements

- **Android 8.0 (Oreo) or newer** (API 26+)
- An internet connection to browse sources (downloads can be read offline)

---

## Installation

### Option A — Install a release APK (easiest)

1. Go to the [Releases](https://github.com/dacy472/Radiant/releases) page on GitHub.
2. Download **Radiant-v0.1.4.apk** from the latest release.
3. On your phone, open the file and allow installation from your browser or file manager if prompted.
4. Open **Radiant** from your app drawer.

If there is no release yet, use **Option B** to build the app yourself.

### Option B — Build from source

You need [Android Studio](https://developer.android.com/studio) (or the Android SDK + JDK 17+).

```bash
git clone https://github.com/dacy472/Radiant.git
cd Radiant
./gradlew :app:assembleDebug
```

The debug APK will be at:

`app/build/outputs/apk/debug/app-debug.apk`

Transfer it to your phone and install it, or run directly from Android Studio with **Run ▶**.

---

## Getting started (beginner guide)

Follow these steps the first time you open Radiant.

### Step 1 — Install a source (extension)

Radiant needs at least one **extension** before you can browse comics.

1. Open the **Browse** tab (bottom navigation).
2. Go to the **Channels** tab.
3. Browse the list of available extensions and tap **Install** on a source you want to use.
4. Wait for the download to finish; Android may ask you to confirm the install.
5. After installing, the source appears under **Browse → Sources**.

You can install multiple extensions. Each one adds a different website.

### Step 2 — Find a comic

**From a single source**

1. Go to **Browse → Sources**.
2. Tap a source name to open its catalog.
3. Browse categories or use the source’s own search.

**Search everywhere at once**

1. On the **Browse** tab, tap the **globe** icon in the top bar.
2. Type a comic title and search across all installed sources.
3. Tap a result to open its detail page.

### Step 3 — Add to your library

1. On a comic’s **detail** screen, tap the **heart / favorite** button.
2. The comic is saved to your **Library** tab.
3. Optional: long-press or use category controls to organize titles into categories (Settings → Library).

### Step 4 — Read a chapter

1. On the detail screen, tap any chapter in the list.
2. The reader opens. **Scroll vertically** to read (default webtoon mode).
3. Tap the center of the screen to show controls (next/previous chapter, settings, etc.).
4. Your place is saved automatically. Unread chapters are marked when you finish them.

### Step 5 — Use the main tabs

| Tab | What it’s for |
|-----|----------------|
| **Library** | Your saved favorites, filters, and categories |
| **Updates** | New chapters for comics in your library |
| **History** | Recently read chapters — tap to continue |
| **Browse** | Sources, extension channels, migration, and global search |
| **Settings** | Appearance, reader, downloads, backup, security, and more |

---

## Common tasks

### Download chapters for offline reading

1. Open a comic’s detail page.
2. Tap the **download** icon on a chapter, or use multi-select to download several at once.
3. View active downloads from the download icon in the top bar (Library, Browse, or Settings).
4. Downloaded chapters open without internet.

### Check for new chapters

1. Open the **Updates** tab.
2. Pull to refresh or use the refresh button.
3. Tap an entry to open the comic and read the new chapter.

### Change reader settings

Go to **Settings → Reader** to adjust:

- Reading direction (vertical webtoon vs. other modes)
- Display options (e.g. brightness, navigation)
- Behavior while reading

### Back up your library

Go to **Settings → Backup & Restore** to export your library, history, and settings to a file. Restore from the same screen if you change phones or reinstall.

### Migrate a comic to another source

If a website stops working or you prefer a different source:

1. Go to **Browse → Migrate**.
2. Pick the source that has the comic in your library.
3. Select the comic to migrate.
4. Search for the same title on another source and confirm migration.

Radiant copies read status, bookmarks, and library placement when chapter numbers match.

### If a source shows “Verification needed” (Cloudflare)

Some sites use Cloudflare protection. Radiant will show an inline message instead of blocking the whole app.

1. Tap **Solve in WebView** on the affected source or search result.
2. Complete any browser check in the built-in WebView.
3. Go back and tap **Retry**.

You can also open a source in WebView manually from the **globe** icon on Browse or Source screens.

### Per-source settings

Long-press a source on **Browse → Sources**, or open **Settings** from the source list, to configure:

- Enable/disable the source
- Pin to the top of the list
- **Advanced:** override the base URL if the site moved to a mirror domain

---

## Settings overview

| Section | Purpose |
|---------|---------|
| **Appearance** | Light/dark theme, date format |
| **Library** | Default categories and library behavior |
| **Reader** | How chapters are displayed and navigated |
| **Downloads** | Where files are stored |
| **Backup & Restore** | Export/import your data |
| **Storage** | Manage downloaded files |
| **Tracking** | Optional AniList sync |
| **Security** | App lock with biometrics |
| **About** | Version info and GitHub link |

---

## For developers

### Tech stack

- Kotlin, Jetpack Compose, Material 3
- Room database for local library storage
- OkHttp for networking; Coil for images
- Tachiyomi-compatible extension system

### Run tests

```bash
./gradlew :app:testDebugUnitTest
```

### Project structure (high level)

```
app/src/main/java/com/radiant/webtoon/
  ui/          # Screens (library, reader, browse, settings, …)
  core/        # Database, repository, extensions, domain logic
app/src/main/java/eu/kanade/tachiyomi/network/
               # HTTP client, Cloudflare handling, interceptors
```

---

## Support and contributing

- **Bug reports and feature requests:** [GitHub Issues](https://github.com/dacy472/Radiant/issues)
- **Source code:** you’re already here

When reporting issues, include your Android version, the extension/source name, and steps to reproduce.

---

## Disclaimer

Radiant is a reader tool only. Users are responsible for complying with the terms of service of any website they access through extensions. The developers do not provide extensions or hosted content.
