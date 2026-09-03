# Scriptoria 📜

> **A production-quality Android web browser built from the ground up for running Tampermonkey and Violentmonkey-compatible userscripts directly on Android.**

---

## 🌟 Key Features

* **⚡ Tampermonkey & Violentmonkey Compatibility**:
  * Full `@match`, `@include`, and `@exclude` URL pattern matching (supporting `*`, `tld`, glob patterns).
  * Injects at `@run-at document-start`, `document-end`, and `document-idle`.
  * External `@require` library caching and dependency resolution.
* **🛡️ Isolated Scriptoria Bridge & GM API**:
  * Per-script secure token capabilities (`CapabilityTokenManager`).
  * `GM_setValue`, `GM_getValue`, `GM_deleteValue`, `GM_listValues` backed by SQLite Room database.
  * Cross-origin HTTP requests with `GM_xmlhttpRequest` / `GM.xmlHttpRequest` via OkHttp (bypassing browser CORS).
  * `GM_addStyle`, `GM_registerMenuCommand`, `GM_openInTab`, `GM_notification`, `GM_setClipboard`.
  * `GM_download` and native blob streaming engine.
* **📥 Advanced Downloads Manager**:
  * Dedicated Downloads screen with 1-tap media playback for downloaded videos and files.
  * Customizable download storage: use default `Downloads/Scriptoria` or pick any directory via Android's native Storage Access Framework (SAF).
  * Direct file sharing and deletion from within the browser.
* **🔧 Userscript Management**:
  * Automatic `.user.js` link interception with permission dialogs before installation.
  * In-browser Userscript Manager to view, toggle, search, edit, and update scripts.
  * Omnibox active script badge (`🔧 N`) showing scripts currently active on the page.
  * Built-in Userscript Developer Console for inspecting logs, warnings, and errors.
  * Full JSON backup export and import.
* **📱 Modern Android Architecture**:
  * Jetpack Compose UI with Material 3 dark theme.
  * Hardware-accelerated WebView container with desktop site toggle and tab switcher.
  * Built with Kotlin 2.0 and targeted for Android 15 (SDK 35).

---

## 🚀 Building & Running

### Requirements
* Android Studio Ladybug / Meerkat or later
* JDK 17
* Android SDK 35 (Android 15)

### Build Debug APK
```bash
./gradlew assembleDebug
```

### Install directly to device
```bash
./gradlew installDebug
```

---

## 📄 License
GNU General Public License v3.0 (GPLv3)
