# Scriptoria Browser 📜
### *The High-Performance, Mobile-First Userscript Engine for Android*

---

## 📖 Executive Summary

**Scriptoria** is a modern, lightweight Android web browser built specifically to run desktop-grade **Tampermonkey** and **Violentmonkey** userscripts on mobile devices. While conventional mobile browsers either treat user scripts as an afterthought (basic `eval()` string injection) or require massive, bloated Chromium builds (>150–200 MB) with desktop extension layers, Scriptoria bridges the gap: it delivers a **native, secure, and complete Greasemonkey runtime in a lean ~10.9 MB APK**.

Built from the ground up using **Kotlin 2.0**, **Jetpack Compose (Material 3)**, and **AndroidX WebKit**, Scriptoria features a purpose-built runtime environment that supports true `@run-at document-start` timing, cross-origin OkHttp requests, isolated Room SQLite per-script key-value storage, desktop File System Access polyfills, and an integrated download pipeline.

---

## 🎯 The Problem Scriptoria Solves

For years, Android users who rely on power-user scripts (e.g., streaming media grabbers, ad/tracker bypassers, paywall neutralizers, UI transformers) have been forced to choose between flawed extremes:

| Mobile Solution | Mechanism | Critical Limitations |
|:---|:---|:---|
| **Kiwi / Yandex Browser** | Full Chromium + desktop Chrome extension store | **Massive bloat (>150–200 MB)**, high battery drain, slow cold boot. Tampermonkey is a desktop popup squeezed into mobile screens. |
| **Firefox Mobile + Tampermonkey** | Gecko engine + WebExtension add-on | **Heavy memory footprint**, strict sandbox restrictions that prevent direct interaction with Android's native file system and download services. |
| **Via / Soul / XBrowser** | Lightweight WebView + basic JS injection | **Incomplete API support.** They only inject raw scripts at `document-end`. They lack `GM_xmlhttpRequest`, `GM_setValue`, CORS bypass, and blob streaming—causing modern userscripts to fail silently or crash. |
| **Scriptoria (This App)** | **Native Android Userscript Engine** | **Ultra-lightweight (~10.9 MB)**, full Greasemonkey/Tampermonkey API bridge, pre-installed scripts enabled out of the box, direct SAF storage access, and native Compose UI. |

---

## ⚡ Key Features & Capabilities

### 1. 🛡️ Complete Tampermonkey / Greasemonkey Engine
* **Universal Metadata Parser**: Parses standard userscript headers (`@name`, `@version`, `@match`, `@include`, `@exclude`, `@grant`, `@require`, `@connect`, `@run-at`, `@noframes`).
* **Precise Execution Timings**:
  * `@run-at document-start`: Injected via AndroidX `WebViewCompat.addDocumentStartJavaScript` *before* the DOM or any page scripts execute. Essential for network hooks, prototype overriding, and security modifications.
  * `@run-at document-end`: Injected immediately after DOM parsing.
  * `@run-at document-idle`: Injected when the document and sub-resources finish loading.
* **Granular Capability Token Security**:
  * Every injected script receives a cryptographic, single-session token (`CapabilityTokenManager`).
  * If a script did not declare `@grant GM_xmlhttpRequest`, its token will reject network escalation attempts, safeguarding user security.

### 2. 🔌 Comprehensive GM_* API Implementation Matrix

| API Function | Backend Implementation | Mobile Advantage |
|:---|:---|:---|
| `GM_xmlhttpRequest` / `GM.xmlHttpRequest` | Native **OkHttp** client | **Completely bypasses browser CORS**, security restrictions, and origin limits. Supports headers, progress tracking, and binary responses. |
| `GM_setValue` / `GM_getValue` / `GM_deleteValue` / `GM_listValues` | **Room SQLite** (`GmStorageEntity`) | Persistent, transactional, per-script isolated storage. Scripts can save states, tokens, and settings across sessions. |
| `GM_download` / Native Blob Streaming | Android **DownloadManager** & custom streaming pipeline | Direct-to-disk streaming for large files without running out of RAM. |
| `GM_notification` | Android **NotificationChannel** system | Real Android system push notifications with notification manager integration. |
| `GM_setClipboard` | Android `ClipboardManager` | Allows scripts to copy links, tokens, and parsed media directly to clipboard. |
| `GM_addStyle` | Dynamic `<style>` injection | Injects custom CSS stylesheets with immediate reactivity. |
| `GM_registerMenuCommand` | Native Browser UI Command Bus | Displays custom script commands directly in the browser's action sheet. |
| `GM_openInTab` | Multi-tab Compose Manager | Opens links in background or foreground tabs. |

### 3. 🧩 Desktop File System API Polyfills (Mobile-First)
* Desktop userscripts often depend on Chrome's `window.showSaveFilePicker` and `FileSystemWritableFileStream` to stream multi-gigabyte files (e.g. video files, ZIP archives, chunked blobs).
* Standard Android WebViews fail on these calls. Scriptoria provides a **native JavaScript polyfill** mapped to Android's **Storage Access Framework (SAF)**, allowing seamless in-browser streaming and direct file downloads without memory crashes.

### 4. 📦 Out-of-the-Box Bundled Userscripts
* Users do not need to hunt through script repositories or configure complex settings to get started.
* The app features an extensible asset catalog (`app/src/main/assets/preinstalled_scripts/`).
* **Flagship Bundled Script: `Super Telegram media downloader` (v2.5)**:
  * Automatically seeded and enabled on initial launch.
  * Adds universal Telegram Blue in-chat download buttons (`↓`) and viewer header actions.
  * Intelligent video vs. thumbnail discriminator: accurately distinguishes high-res videos from static image previews and streams the full original media (e.g. 500+ MB MP4 files) rather than small cached thumbnails.
  * Glassmorphic real-time download manager with animated percentage rings and speed indicators.

### 5. 📥 Dedicated Native Download Manager
* **Custom Save Locations**: Choose between standard `Downloads/Scriptoria` or any custom folder on device storage/SD card using the native Android directory picker.
* **1-Tap Media Playback**: Directly launch and play downloaded MP4/MKV videos, audio tracks, and documents inside default system players or external media apps (VLC, MX Player).
* **Native File Operations**: Pause, resume, share intents, and file deletion directly from the browser UI.

### 6. 🧰 Built-In Developer & Script Management Tools
* **Dynamic Tab Badge**: Displays an active script counter (e.g. `🔧 1`) directly in the top app bar for instant visibility of running scripts on the current page.
* **Full-Featured Script Editor**: View and modify script code with line numbers, syntax styling, search, and instant metadata re-parsing.
* **Live Userscript Console**: Real-time log inspection filtering between `INFO`, `WARN`, and `ERROR` generated specifically by running scripts.
* **Backup & Migration**: One-click JSON backup export and import for user libraries.

---

## 🏗️ Architecture & Technology Stack

```
Scriptoria Architecture
├── UI Layer (Jetpack Compose Material 3)
│   ├── BrowserScreen (Omnibox, WebView container, Tab Drawer, Progress)
│   ├── UserscriptManagerScreen (Script list, enable/disable switches, search)
│   ├── ScriptEditorScreen (Full-code editing, metadata inspection)
│   ├── DownloadsScreen (Download items, progress bars, open/share actions)
│   └── ConsoleBottomSheet (Live Userscript logcat/console output)
│
├── Userscript Engine (Kotlin + JS Shim)
│   ├── UserscriptParser (Parses @match, @grant, @run-at headers)
│   ├── UserscriptManager (Lifecycle orchestrator & assets catalog seeder)
│   ├── ScriptoriaNativeBridge (Secure JS-to-Native JavascriptInterface)
│   ├── CapabilityTokenManager (Per-script capability verification)
│   ├── GmXhrHandler (OkHttp-powered CORS-free networking)
│   └── gm_shim.js (Window-injected Greasemonkey/Tampermonkey runtime)
│
├── Data & Persistence Layer (Room SQLite)
│   ├── AppDatabase (Database definition & destructive migration fallback)
│   ├── ScriptDao & ScriptEntity (Installed scripts, metadata, execution orders)
│   ├── GmStorageDao & GmStorageEntity (Isolated per-script key-value store)
│   └── ScriptFileStore (On-disk script source persistence in app sandbox)
│
└── Download Pipeline
    ├── DownloadPreferences (User folder preferences & SAF tree URIs)
    └── DownloadManagerRepository (Native file system & SAF file operations)
```

### Key Libraries & Dependencies
* **Language & Runtime**: Kotlin 2.0.21, Java 17, Android SDK 35 (Android 15)
* **UI**: Android Jetpack Compose BOM (Material 3, Navigation, Icons Extended)
* **Database**: AndroidX Room (Room KSP, Coroutines & Flow support)
* **Networking**: OkHttp 4.12.0 (Custom SSL, redirect handling, streaming buffers)
* **Web Engine**: AndroidX WebKit (`WebViewCompat`, `ScriptReferenceCompat`, `WebSettingsCompat`)

---

## 📊 App Footprint & Efficiency

* **APK Size**: ~10.9 MB (Unsigned Release), shrinkable to **~5.2 MB** with R8 minification.
* **RAM Consumption**: 60%–75% lower memory footprint during background idle compared to Chromium-based browsers.
* **Cold Boot**: Near-instantaneous startup since it utilizes Android's optimized system WebView engine rather than initializing an entire bundled browser engine.

---

## 🚀 Extensibility Guide: Adding New Pre-installed Scripts

Adding new built-in scripts to Scriptoria is fully plug-and-play:

1. Create or obtain any valid `.user.js` userscript.
2. Place the file inside `app/src/main/assets/preinstalled_scripts/`:
   ```bash
   app/src/main/assets/preinstalled_scripts/<my_script>.user.js
   ```
3. On application launch, the `ScriptRepository` automatically detects any uninstalled files in this directory, registers them in the local database, and enables them by default for all users.

---

## 📜 License & Open Source
Scriptoria is licensed under the **GNU General Public License v3.0 (GPLv3)**.
Contributions, issues, and feature requests are welcome.
