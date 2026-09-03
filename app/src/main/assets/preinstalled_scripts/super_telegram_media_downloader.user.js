// ==UserScript==
// @name         Super Telegram media downloader
// @version      2.5
// @description  Supercharged media downloader for Telegram Web (WebK & WebZ/WebA). Features reliable chunked streaming, universal Telegram blue in-chat & viewer download buttons, instant blob saving, and a sleek glassmorphic download manager.
// @match        *://web.telegram.org/*
// @match        *://webk.telegram.org/*
// @match        *://webz.telegram.org/*
// @match        *://*.telegram.org/*
// @include      *://web.telegram.org/*
// @include      *://webk.telegram.org/*
// @include      *://webz.telegram.org/*
// @include      *://*.telegram.org/*
// @icon         https://img.icons8.com/color/452/telegram-app--v5.png
// @grant        none
// @run-at       document-idle
// @noframes
// ==/UserScript==

(function () {
    "use strict";

    // ─── Config ──────────────────────────────────────────────────────────
    const CONFIG = {
        MAX_CONCURRENT: 2,
        RETRY_DELAY: 30000,
        TOAST_LINGER: 8000,
        OBSERVER_DEBOUNCE: 200,
        SCROLL_STEP_RATIO: 0.75,   // fraction of viewport height to scroll per step
        SCROLL_WAIT_MS: 450,       // base ms to wait after each scroll
        SETTLE_TRIES: 6,           // extra settle passes waiting for media srcs to populate
        SETTLE_WAIT_MS: 150,       // ms between settle passes
        TOP_CONFIRM_TRIES: 4,      // consecutive no-movement steps before declaring "top reached"
        ZIP_BUFFER_BYTES: 512 * 1024 * 1024,  // pause fetching once this much is waiting to be written
        MIN_BLOB_BYTES: 32768,     // below this an object URL is a thumbnail, not a file
        DOC_PROBE_MS: 2500,        // how long to wait for a click to show any sign of life
        DOC_IDLE_TIMEOUT: 25000,   // give up if Telegram shows no transfer activity for this long
        DOC_MAX_WAIT: 600000,      // absolute ceiling for one file Telegram is fetching itself
    };

    // Anything that means "a transfer is in flight", across both web clients.
    const BUSY_SELECTOR = [
        ".preloader-container", ".preloader", ".media-loader", "[class*='progress']",
        "[class*='ProgressSpinner']", ".icon-cancel", ".action-icon.icon-cancel",
        "svg.ProgressSpinner",
    ].join(", ");

    // ─── Logger ──────────────────────────────────────────────────────────
    const log = {
        _fmt: (msg, file) => `[TGDl] ${file ? `${file}: ` : ""}${msg}`,
        info: (msg, file) => console.log(log._fmt(msg, file)),
        warn: (msg, file) => console.warn(log._fmt(msg, file)),
        error: (msg, file) => console.error(log._fmt(msg, file)),
    };

    // ─── Utilities ───────────────────────────────────────────────────────
    const Utils = {
        hash(s) {
            let h = 0;
            for (let i = 0; i < s.length; i++) h = ((h << 5) - h + s.charCodeAt(i)) | 0;
            return (h >>> 0).toString(36);
        },
        uid() {
            return Math.random().toString(36).slice(2, 10) + "_" + Date.now().toString(36);
        },
        isDark() {
            const html = document.documentElement;
            return html.classList.contains("night") || html.classList.contains("theme-dark");
        },
        fileNameFromUrl(url, fallbackExt = "mp4") {
            try {
                const last = decodeURIComponent(url.split("/").pop());
                const meta = JSON.parse(last);
                if (meta.fileName) return meta.fileName;
            } catch { /* not JSON */ }
            return `${Utils.hash(url)}.${fallbackExt}`;
        },
        cleanFileName(raw) {
            return (raw || "")
                .replace(/[\u200B-\u200D\uFEFF\u2060]/g, "")
                .replace(/\u2026/g, "")
                .replace(/\s+/g, " ")
                .trim();
        },
        padNum(n, width) {
            return String(n).padStart(width, "0");
        },
        typeFromName(name) {
            const n = (name || "").toLowerCase();
            if (/\.(mp4|mkv|avi|mov|webm|flv|wmv|m4v|3gp|mpe?g|ts|m2ts)$/.test(n)) return "video";
            if (/\.(mp3|ogg|oga|wav|flac|aac|m4a|opus|wma)$/.test(n)) return "audio";
            if (/\.(jpe?g|png|gif|webp|bmp|svg|tiff?|heic|avif)$/.test(n)) return "image";
            return "document";
        },
        parseMime(contentType) {
            const mime = (contentType || "").split(";")[0].trim();
            const [category, ext] = mime.split("/");
            return { mime, category, ext: ext || "bin" };
        },
        parseRange(header) {
            const m = (header || "").match(/^bytes (\d+)-(\d+)\/(\d+)$/);
            if (!m) return null;
            return { start: +m[1], end: +m[2], total: +m[3] };
        },
        formatBytes(bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1048576) return (bytes / 1024).toFixed(1) + " KB";
            if (bytes < 1073741824) return (bytes / 1048576).toFixed(1) + " MB";
            return (bytes / 1073741824).toFixed(2) + " GB";
        },
        typeIcon(type) {
            switch (type) {
                case "video": return "\u{1F3AC}";
                case "image": return "\u{1F5BC}";
                case "audio": return "\u{1F3B5}";
                case "document": return "\u{1F4C4}";
                default: return "\u{1F4CE}";
            }
        },
        truncate(str, max = 35) {
            if (str.length <= max) return str;
            const ext = str.lastIndexOf(".");
            if (ext > 0 && str.length - ext <= 6) {
                const keep = max - (str.length - ext) - 3;
                return str.slice(0, Math.max(1, keep)) + "..." + str.slice(ext);
            }
            return str.slice(0, max - 3) + "...";
        },
        sleep(ms) { return new Promise(r => setTimeout(r, ms)); },
    };

    // ─── UI: Styles + Toast ──────────────────────────────────────────────
    const UI = {
        _container: null,
        _stylesInjected: false,

        init() {
            if (UI._stylesInjected) return;
            UI._stylesInjected = true;

            const style = document.createElement("style");
            style.textContent = `
        /* ── Toast container ── */
        #tgdl-container {
          position: fixed; bottom: 80px; right: 16px; z-index: 999999 !important;
          display: flex; flex-direction: column-reverse; gap: 8px;
          max-height: calc(100vh - 160px); overflow-y: auto;
          pointer-events: none;
        }
        .tgdl-toast {
          pointer-events: auto; width: 280px; padding: 10px 12px;
          border-radius: 10px;
          font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
          font-size: 13px; color: #fff;
          background: rgba(30,30,30,0.92); backdrop-filter: blur(12px);
          box-shadow: 0 4px 16px rgba(0,0,0,0.3);
          animation: tgdl-slide-in 0.3s ease-out;
        }
        .tgdl-toast.light { background: rgba(255,255,255,0.92); color: #1a1a1a; box-shadow: 0 4px 16px rgba(0,0,0,0.12); }
        @keyframes tgdl-slide-in {
          from { opacity: 0; transform: translateX(40px); }
          to   { opacity: 1; transform: translateX(0); }
        }
        .tgdl-toast-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
        .tgdl-toast-filename { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 200px; }
        .tgdl-toast-close {
          cursor: pointer; font-size: 16px; opacity: 0.6;
          background: none; border: none; color: inherit; padding: 0 0 0 8px; line-height: 1;
        }
        .tgdl-toast-close:hover { opacity: 1; }
        .tgdl-toast-bar-track { width: 100%; height: 6px; border-radius: 3px; background: rgba(255,255,255,0.15); overflow: hidden; }
        .tgdl-toast.light .tgdl-toast-bar-track { background: rgba(0,0,0,0.08); }
        .tgdl-toast-bar-fill { height: 100%; width: 0%; border-radius: 3px; background: #5EB5F7; transition: width 0.25s ease; }
        .tgdl-toast-bar-fill.queued { background: #888; width: 100%; }
        .tgdl-toast-bar-fill.done   { background: #5DC264; width: 100%; }
        .tgdl-toast-bar-fill.error  { background: #E05555; width: 100%; }
        .tgdl-toast-status { margin-top: 4px; font-size: 11px; opacity: 0.7; display: flex; justify-content: space-between; }

        /* ── Per-message inline download button (Universal Telegram Blue) ── */
        .tgdl-inline-dl {
          position: absolute; top: 6px; right: 6px; z-index: 10;
          width: 34px; height: 34px; border-radius: 50%;
          border: none; cursor: pointer;
          background: #5EB5F7; color: #fff;
          display: flex; align-items: center; justify-content: center;
          font-size: 16px; line-height: 1;
          opacity: 1 !important; transition: transform 0.2s, background 0.2s;
          box-shadow: 0 3px 10px rgba(0,0,0,0.35);
        }
        .tgdl-inline-dl:hover { background: #4A9FE0; transform: scale(1.08); }
        .tgdl-inline-dl.light { background: #3390EC; }
        .tgdl-inline-dl.light:hover { background: #2B7DD6; }
        .tgdl-media-wrap { position: relative; }

        /* ── Viewer header button (Universal Telegram Blue) ── */
        .tel-download {
          background: #5EB5F7 !important; color: #fff !important;
          border-radius: 50% !important;
          box-shadow: 0 3px 10px rgba(0,0,0,0.3) !important;
          transition: transform 0.2s, background 0.2s !important;
        }
        .tel-download:hover { background: #4A9FE0 !important; transform: scale(1.08) !important; }
        .tel-download.light { background: #3390EC !important; }
        .tel-download .tgico, .tel-download .icon { color: #fff !important; fill: #fff !important; }

        /* ── Batch panel (slide-out) ── */
        .tgdl-batch-overlay {
          position: fixed; inset: 0; z-index: 10000;
          background: rgba(0,0,0,0.5); display: none;
          animation: tgdl-fade-in 0.2s ease;
        }
        .tgdl-batch-overlay.open { display: flex; justify-content: flex-end; }
        @keyframes tgdl-fade-in { from { opacity: 0; } to { opacity: 1; } }

        .tgdl-batch-panel {
          width: 400px; max-width: 90vw; height: 100%;
          display: flex; flex-direction: column;
          font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
          font-size: 13px;
          background: #1e1e1e; color: #e0e0e0;
          box-shadow: -4px 0 24px rgba(0,0,0,0.4);
          animation: tgdl-slide-panel 0.25s ease-out;
        }
        .tgdl-batch-panel.light { background: #fff; color: #1a1a1a; box-shadow: -4px 0 24px rgba(0,0,0,0.1); }
        @keyframes tgdl-slide-panel {
          from { transform: translateX(100%); } to { transform: translateX(0); }
        }

        .tgdl-batch-header {
          padding: 16px; display: flex; justify-content: space-between; align-items: center;
          border-bottom: 1px solid rgba(255,255,255,0.08);
        }
        .tgdl-batch-panel.light .tgdl-batch-header { border-bottom-color: rgba(0,0,0,0.08); }
        .tgdl-batch-header h3 { margin: 0; font-size: 16px; font-weight: 700; }
        .tgdl-batch-close {
          background: none; border: none; color: inherit; font-size: 22px;
          cursor: pointer; opacity: 0.6; padding: 0; line-height: 1;
        }
        .tgdl-batch-close:hover { opacity: 1; }

        .tgdl-batch-toolbar {
          padding: 10px 16px; display: flex; align-items: center; gap: 10px;
          border-bottom: 1px solid rgba(255,255,255,0.06);
          flex-wrap: wrap;
        }
        .tgdl-batch-panel.light .tgdl-batch-toolbar { border-bottom-color: rgba(0,0,0,0.06); }
        .tgdl-batch-toolbar label {
          display: flex; align-items: center; gap: 5px; cursor: pointer; font-weight: 600; font-size: 12px;
        }
        .tgdl-batch-toolbar input[type="checkbox"] { width: 16px; height: 16px; accent-color: #5EB5F7; cursor: pointer; }
        .tgdl-batch-filters { display: flex; gap: 4px; flex: 1; flex-wrap: wrap; }
        .tgdl-filter-chip {
          padding: 3px 10px; border-radius: 12px; border: 1px solid rgba(255,255,255,0.15);
          background: transparent; color: inherit; cursor: pointer; font-size: 11px;
          font-family: inherit; transition: background 0.15s;
        }
        .tgdl-batch-panel.light .tgdl-filter-chip { border-color: rgba(0,0,0,0.15); }
        .tgdl-filter-chip.active { background: #5EB5F7; color: #fff; border-color: #5EB5F7; }
        .tgdl-batch-count { font-size: 11px; opacity: 0.6; white-space: nowrap; }

        .tgdl-batch-list { flex: 1; overflow-y: auto; padding: 4px 0; }
        .tgdl-batch-item {
          display: flex; align-items: center; gap: 10px;
          padding: 8px 16px; cursor: pointer; transition: background 0.12s;
          user-select: none;
        }
        .tgdl-batch-item:hover { background: rgba(255,255,255,0.04); }
        .tgdl-batch-panel.light .tgdl-batch-item:hover { background: rgba(0,0,0,0.03); }
        .tgdl-batch-item input[type="checkbox"] { width: 16px; height: 16px; accent-color: #5EB5F7; cursor: pointer; flex-shrink: 0; }
        .tgdl-batch-item-thumb {
          width: 44px; height: 44px; border-radius: 6px;
          background: rgba(255,255,255,0.06); flex-shrink: 0;
          display: flex; align-items: center; justify-content: center; font-size: 22px;
          overflow: hidden;
        }
        .tgdl-batch-panel.light .tgdl-batch-item-thumb { background: rgba(0,0,0,0.04); }
        .tgdl-batch-item-thumb img { width: 100%; height: 100%; object-fit: cover; border-radius: 6px; }
        .tgdl-batch-item-info { flex: 1; min-width: 0; }
        .tgdl-batch-item-name { font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        .tgdl-batch-item-meta { font-size: 11px; opacity: 0.5; margin-top: 2px; }

        .tgdl-batch-panel { position: relative; }
        .tgdl-batch-panel.zipping .tgdl-batch-list { padding-bottom: 45%; }
        .tgdl-batch-footer {
          padding: 12px 16px; border-top: 1px solid rgba(255,255,255,0.08);
          display: flex; align-items: center; justify-content: space-between;
        }
        .tgdl-batch-panel.light .tgdl-batch-footer { border-top-color: rgba(0,0,0,0.08); }
        .tgdl-batch-dl-btn {
          padding: 10px 24px; border-radius: 8px; border: none;
          font-size: 14px; font-weight: 600; cursor: pointer;
          background: #5EB5F7; color: #fff;
          font-family: inherit; transition: background 0.15s, opacity 0.15s;
        }
        .tgdl-batch-dl-btn:hover { background: #4A9FE0; }
        .tgdl-batch-dl-btn:disabled { opacity: 0.4; cursor: default; }
        .tgdl-batch-zip-btn {
          background: #4FBF7B; color: #fff; border: none; border-radius: 8px;
          padding: 8px 16px; font-size: 13px; font-weight: 600; cursor: pointer;
          margin-left: auto; margin-right: 8px; transition: background 0.15s;
        }
        .tgdl-batch-zip-btn:hover { background: #45A96C; }
        .tgdl-batch-zip-btn:disabled { opacity: 0.4; cursor: default; }
        .tgdl-native-btn {
          background: rgba(128,128,128,0.2); color: inherit; border: none;
          border-radius: 7px; padding: 5px 10px; font-size: 11px;
          font-weight: 600; cursor: pointer; margin-left: 6px;
        }
        .tgdl-native-btn:hover { background: rgba(128,128,128,0.32); }
        .tgdl-native-btn:disabled { opacity: 0.4; cursor: default; }
        .tgdl-batch-toolbar label { display: inline-flex; align-items: center; gap: 4px; }
        #tgdl-concurrency {
          background: rgba(128,128,128,0.18); color: inherit; border: none;
          border-radius: 6px; padding: 2px 4px; font-size: 11px; cursor: pointer;
        }
        .tgdl-batch-sel-count { font-size: 12px; opacity: 0.6; }

        /* Scanning state */
        .tgdl-scan-status {
          padding: 12px 16px; text-align: center; font-size: 12px; opacity: 0.7;
          border-bottom: 1px solid rgba(255,255,255,0.06);
          display: flex; align-items: center; justify-content: center; gap: 8px;
        }
        .tgdl-batch-panel.light .tgdl-scan-status { border-bottom-color: rgba(0,0,0,0.06); }
        .tgdl-scan-spinner {
          display: inline-block; width: 16px; height: 16px;
          border: 2px solid rgba(255,255,255,0.2); border-top-color: #5EB5F7;
          border-radius: 50%; animation: tgdl-spin 0.7s linear infinite;
        }
        .tgdl-batch-panel.light .tgdl-scan-spinner { border-color: rgba(0,0,0,0.1); border-top-color: #3390EC; }
        @keyframes tgdl-spin { to { transform: rotate(360deg); } }

        /* ── ZIP progress ── */
        .tgdl-zip-overlay {
          position: absolute; left: 0; right: 0; bottom: 0; z-index: 5; display: none;
          background: rgba(12,14,18,0.96); backdrop-filter: blur(6px);
          border-top: 1px solid rgba(255,255,255,0.1);
          padding: 12px 14px; max-height: 55%;
        }
        .tgdl-batch-panel.light .tgdl-zip-overlay {
          background: rgba(252,252,252,0.97); border-top-color: rgba(0,0,0,0.1);
        }
        .tgdl-zip-card {
          width: 100%; display: flex; flex-direction: column; gap: 8px;
          font-size: 13px; color: inherit;
        }
        .tgdl-zip-title { font-size: 15px; font-weight: 700; }
        .tgdl-zip-bar, .tgdl-zip-filebar {
          height: 8px; border-radius: 999px; overflow: hidden;
          background: rgba(128,128,128,0.25);
        }
        .tgdl-zip-filebar { height: 5px; }
        .tgdl-zip-bar-fill {
          height: 100%; width: 0%; border-radius: 999px;
          background: linear-gradient(90deg, #4FBF7B, #3390EC);
          transition: width 0.25s ease;
        }
        .tgdl-zip-filebar-fill {
          height: 100%; width: 0%; border-radius: 999px; background: #3390EC;
          transition: width 0.2s ease;
        }
        .tgdl-zip-filebar.indeterminate .tgdl-zip-filebar-fill {
          background: repeating-linear-gradient(
            90deg, #3390EC 0 12px, rgba(51,144,236,0.35) 12px 24px);
          background-size: 24px 100%;
          animation: tgdl-zip-march 0.9s linear infinite;
        }
        @keyframes tgdl-zip-march { to { background-position: 24px 0; } }

        /* ── per-row capture state ── */
        .tgdl-batch-item-meta { display: flex; gap: 8px; align-items: baseline; }
        .tgdl-item-status { font-size: 11px; opacity: 0.85; font-variant-numeric: tabular-nums; }
        .tgdl-batch-item[data-state="done"] .tgdl-item-status { color: #4FBF7B; }
        .tgdl-batch-item[data-state="failed"] .tgdl-item-status { color: #E0715F; }
        .tgdl-batch-item[data-state="done"] { opacity: 0.6; }
        .tgdl-batch-item[data-state="asking"],
        .tgdl-batch-item[data-state="downloading"],
        .tgdl-batch-item[data-state="storing"] {
          background: rgba(51,144,236,0.12); border-radius: 8px;
        }
        .tgdl-item-bar {
          display: none; height: 3px; margin-top: 5px; border-radius: 999px;
          overflow: hidden; background: rgba(128,128,128,0.25);
        }
        .tgdl-item-bar-fill {
          height: 100%; width: 0%; background: #3390EC; border-radius: 999px;
          transition: width 0.2s ease;
        }
        .tgdl-batch-item[data-state="done"] .tgdl-item-bar-fill { background: #4FBF7B; }
        .tgdl-item-bar.indeterminate .tgdl-item-bar-fill {
          background: repeating-linear-gradient(
            90deg, #3390EC 0 10px, rgba(51,144,236,0.3) 10px 20px);
          background-size: 20px 100%;
          animation: tgdl-zip-march 0.9s linear infinite;
        }
        .tgdl-zip-sub {
          display: flex; justify-content: space-between; gap: 8px;
          font-size: 11px; opacity: 0.7; font-variant-numeric: tabular-nums;
        }
        .tgdl-zip-file {
          font-size: 12px; font-weight: 600; line-height: 1.4;
          word-break: break-word; min-height: 17px;
        }
        .tgdl-zip-log {
          max-height: 72px; overflow-y: auto; font-size: 11px; line-height: 1.6;
          background: rgba(128,128,128,0.1); border-radius: 8px; padding: 6px 8px;
        }
        .tgdl-zip-log:empty { display: none; }
        .tgdl-zip-log-row { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        .tgdl-zip-log-row.ok { opacity: 0.65; }
        .tgdl-zip-log-row.bad { color: #E0715F; }
        .tgdl-zip-actions { display: flex; justify-content: flex-end; }
        .tgdl-zip-cancel {
          background: rgba(128,128,128,0.22); color: inherit; border: none;
          border-radius: 8px; padding: 7px 14px; font-size: 12px;
          font-weight: 600; cursor: pointer;
        }
        .tgdl-zip-cancel:hover { background: rgba(128,128,128,0.34); }
        .tgdl-zip-cancel:disabled { opacity: 0.5; cursor: default; }
        .tgdl-scan-stop {
          margin-left: 8px; padding: 2px 10px; border-radius: 10px;
          border: 1px solid rgba(255,255,255,0.2); background: transparent;
          color: inherit; cursor: pointer; font-size: 11px; font-family: inherit;
        }
        .tgdl-batch-panel.light .tgdl-scan-stop { border-color: rgba(0,0,0,0.15); }
        .tgdl-scan-stop:hover { background: rgba(255,255,255,0.08); }

        .tgdl-empty { padding: 40px; text-align: center; opacity: 0.5; }
      `;
            document.head.appendChild(style);

            // Toast container
            const container = document.createElement("div");
            container.id = "tgdl-container";
            document.body.appendChild(container);
            UI._container = container;

            // Batch panel overlay
            const overlay = document.createElement("div");
            overlay.id = "tgdl-batch-overlay";
            overlay.className = "tgdl-batch-overlay";
            overlay.onclick = (e) => { if (e.target === overlay) BatchPanel.close(); };
            document.body.appendChild(overlay);
        },

        createToast(id, fileName) {
            UI.init();
            const isLight = !Utils.isDark();
            const toast = document.createElement("div");
            toast.className = "tgdl-toast" + (isLight ? " light" : "");
            toast.id = "tgdl-toast-" + id;
            toast.innerHTML = `
        <div class="tgdl-toast-header">
          <span class="tgdl-toast-filename" title="${fileName}">${Utils.truncate(fileName)}</span>
          <button class="tgdl-toast-close">&times;</button>
        </div>
        <div class="tgdl-toast-bar-track"><div class="tgdl-toast-bar-fill"></div></div>
        <div class="tgdl-toast-status">
          <span class="tgdl-toast-pct">Queued</span>
          <span class="tgdl-toast-size"></span>
        </div>`;
            toast.querySelector(".tgdl-toast-close").onclick = () => { Downloader.cancel(id); toast.remove(); };
            UI._container.appendChild(toast);
            return toast;
        },

        updateToast(id, { fileName, pct, downloaded, total, state }) {
            const toast = document.getElementById("tgdl-toast-" + id);
            if (!toast) return;
            const fill = toast.querySelector(".tgdl-toast-bar-fill");
            const pctEl = toast.querySelector(".tgdl-toast-pct");
            const sizeEl = toast.querySelector(".tgdl-toast-size");
            if (fileName) toast.querySelector(".tgdl-toast-filename").textContent = Utils.truncate(fileName);
            fill.className = "tgdl-toast-bar-fill";
            if (state === "queued") { fill.classList.add("queued"); pctEl.textContent = "Queued"; }
            else if (state === "done") { fill.classList.add("done"); pctEl.textContent = "Done"; }
            else if (state === "error") { fill.classList.add("error"); pctEl.textContent = "Failed — retrying…"; }
            else { fill.style.width = pct + "%"; pctEl.textContent = pct + "%"; }
            if (downloaded != null && total != null) {
                sizeEl.textContent = `${Utils.formatBytes(downloaded)} / ${Utils.formatBytes(total)}`;
            }
        },

        removeToast(id, delay = CONFIG.TOAST_LINGER) {
            setTimeout(() => { const t = document.getElementById("tgdl-toast-" + id); if (t) t.remove(); }, delay);
        },

        showBatchFab(visible) {
            // Blue batch downloader button removed as requested
        },
    };

    // ─── Network Sniffer ─────────────────────────────────────────────────
    const NetSniffer = {
        blobs: new Map(),
        blobTimes: new Map(),
        urls: new Map(),
        _installed: false,

        install() {
            if (this._installed) return;
            this._installed = true;

            const origCreate = URL.createObjectURL.bind(URL);
            URL.createObjectURL = function (obj) {
                const u = origCreate(obj);
                try {
                    if (obj instanceof Blob) {
                        NetSniffer.blobs.set(u, obj);
                        NetSniffer.blobTimes.set(u, Date.now());
                    }
                } catch { /* ignore */ }
                return u;
            };

            const origFetch = window.fetch;
            if (typeof origFetch === "function") {
                window.fetch = function (input, init) {
                    try {
                        NetSniffer.note(typeof input === "string" ? input : input?.url);
                    } catch { /* ignore */ }
                    return origFetch.apply(this, arguments);
                };
            }

            const origOpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function (method, url) {
                try { NetSniffer.note(url); } catch { /* ignore */ }
                return origOpen.apply(this, arguments);
            };

            log.info("Network sniffer installed");
        },

        isMediaUrl(u) {
            if (!u || typeof u !== "string") return false;
            return /\/(stream|download|progressive)\//.test(u) ||
                /\bdcId\b/.test(decodeURIComponent(u.slice(0, 400)));
        },

        note(u) {
            if (!this.isMediaUrl(u)) return null;
            const abs = u.startsWith("http") ? u : new URL(u, location.origin).href;
            if (this.urls.has(abs)) return this.urls.get(abs);
            const fileName = Utils.fileNameFromUrl(abs, "bin");
            const named = !fileName.endsWith(".bin");
            const type = Utils.typeFromName(fileName);
            const rec = { url: abs, type, fileName, named, at: Date.now() };
            this.urls.set(abs, rec);
            return rec;
        },

        since(ts) {
            return [...this.urls.values()].filter(r => r.at >= ts).sort((a, b) => b.at - a.at);
        },

        getBlob(url) {
            return this.blobs.get(url) || null;
        },

        blobsSince(ts) {
            return [...this.blobTimes.entries()]
                .filter(([, t]) => t >= ts)
                .sort((a, b) => b[1] - a[1])
                .map(([u]) => u);
        },
    };

    // ─── Save Interceptor ────────────────────────────────────────────────
    const SaveInterceptor = {
        _armed: false,
        _global: false,
        captured: [],
        _installed: false,
        _pending: 0,

        get armed() { return this._armed || this._global; },

        _shouldSwallow(a) {
            if (!a || a.dataset?.tgdlOwn === "1") return false;
            const href = a.href || "";
            return a.hasAttribute("download") ||
                href.startsWith("blob:") ||
                NetSniffer.isMediaUrl(href);
        },

        install() {
            if (this._installed) return;
            this._installed = true;

            const origClick = HTMLAnchorElement.prototype.click;
            HTMLAnchorElement.prototype.click = function () {
                if (SaveInterceptor.armed && SaveInterceptor._shouldSwallow(this)) {
                    SaveInterceptor._capture(this.href, this.getAttribute("download") || "");
                    return;
                }
                return origClick.apply(this, arguments);
            };

            const origDispatch = EventTarget.prototype.dispatchEvent;
            EventTarget.prototype.dispatchEvent = function (evt) {
                if (
                    SaveInterceptor.armed &&
                    evt?.type === "click" &&
                    this instanceof HTMLAnchorElement &&
                    SaveInterceptor._shouldSwallow(this)
                ) {
                    SaveInterceptor._capture(this.href, this.getAttribute("download") || "");
                    return true;
                }
                return origDispatch.apply(this, arguments);
            };

            document.addEventListener("click", (e) => {
                if (!SaveInterceptor.armed) return;
                const a = e.target?.closest?.("a");
                if (!SaveInterceptor._shouldSwallow(a)) return;
                e.preventDefault();
                e.stopPropagation();
                SaveInterceptor._capture(a.href, a.getAttribute("download") || "");
            }, true);

            const origOpen2 = window.open;
            window.open = function (url) {
                if (SaveInterceptor.armed && typeof url === "string" && NetSniffer.isMediaUrl(url)) {
                    SaveInterceptor._capture(url, "");
                    return null;
                }
                return origOpen2.apply(this, arguments);
            };

            const origPicker = window.showSaveFilePicker;
            if (typeof origPicker === "function") {
                window.showSaveFilePicker = async function (opts) {
                    if (!SaveInterceptor.armed) return origPicker.apply(this, arguments);
                    const chunks = [];
                    const name = opts?.suggestedName || "file";
                    return {
                        kind: "file",
                        name,
                        createWritable: async () => ({
                            write: async (data) => {
                                chunks.push(data?.data !== undefined ? data.data : data);
                            },
                            seek: async () => { },
                            truncate: async () => { },
                            close: async () => {
                                SaveInterceptor.captured.push({ fileName: name, blob: new Blob(chunks) });
                            },
                        }),
                    };
                };
            }

            log.info("Save interceptor installed");
        },

        async _capture(href, fileName) {
            this._pending++;
            try {
                let blob = href.startsWith("blob:") ? NetSniffer.getBlob(href) : null;
                if (!blob) blob = await (await fetch(href)).blob();
                if (blob && blob.size > 0) {
                    this.captured.push({ fileName, blob });
                    log.info(`Intercepted a save: ${fileName || "(unnamed)"} ${Utils.formatBytes(blob.size)}`);
                }
            } catch (e) {
                log.warn(`Could not capture an intercepted save: ${e.message}`, fileName);
            } finally {
                this._pending--;
            }
        },

        arm() { this.captured = []; this._armed = true; },
        disarm() { this._armed = false; },

        armGlobal() { this._global = true; },
        disarmGlobal() { this._global = false; this.captured = []; },

        take(fileName) {
            if (!this.captured.length) return null;
            let idx = this.captured.findIndex(c => c.fileName === fileName);
            if (idx < 0) idx = this.captured.findIndex(c => c.fileName && fileName && (
                c.fileName.includes(fileName) || fileName.includes(c.fileName)
            ));
            if (idx < 0 && this.captured.length === 1) idx = 0;
            if (idx < 0) return null;
            return this.captured.splice(idx, 1)[0];
        },

        async settle(ms = 3000) {
            const until = Date.now() + ms;
            while (this._pending > 0 && Date.now() < until) await Utils.sleep(100);
        },
    };

    // ─── Streaming ZIP writer ────────────────────────────────────────────
    const CRC32 = {
        _table: null,
        _build() {
            const t = new Uint32Array(256);
            for (let i = 0; i < 256; i++) {
                let c = i;
                for (let k = 8; k > 0; k--) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
                t[i] = c >>> 0;
            }
            this._table = t;
        },
        update(crc, bytes) {
            if (!this._table) this._build();
            const t = this._table;
            let c = crc ^ 0xFFFFFFFF;
            for (let i = 0; i < bytes.length; i++) c = t[(c ^ bytes[i]) & 0xFF] ^ (c >>> 8);
            return (c ^ 0xFFFFFFFF) >>> 0;
        },
    };

    const U32_MAX = 0xFFFFFFFF;

    class ZipWriter {
        constructor() {
            this.entries = [];
            this.offset = 0;
            this.writable = null;
            this.parts = null;
            this.names = new Set();
        }

        async open(suggestedName) {
            if (typeof window.showSaveFilePicker === "function") {
                const handle = await window.showSaveFilePicker({
                    suggestedName,
                    types: [{ description: "ZIP archive", accept: { "application/zip": [".zip"] } }],
                });
                this.writable = await handle.createWritable();
                return "stream";
            }
            log.warn("File System Access API unavailable — buffering the archive in memory.");
            this.parts = [];
            return "memory";
        }

        async _write(chunk) {
            const buf = chunk instanceof Uint8Array ? chunk : new Uint8Array(chunk);
            if (this.writable) await this.writable.write(buf);
            else this.parts.push(buf);
            this.offset += buf.length;
        }

        _uniqueName(name) {
            let clean = (name || "file").replace(/[\\/:*?"<>|\u0000-\u001f]/gu, "_").slice(0, 180);
            if (!this.names.has(clean)) { this.names.add(clean); return clean; }
            const dot = clean.lastIndexOf(".");
            const stem = dot > 0 ? clean.slice(0, dot) : clean;
            const ext = dot > 0 ? clean.slice(dot) : "";
            let n = 2;
            while (this.names.has(`${stem} (${n})${ext}`)) n++;
            const out = `${stem} (${n})${ext}`;
            this.names.add(out);
            return out;
        }

        static _dosTime(d) {
            return ((d.getHours() << 11) | (d.getMinutes() << 5) | (d.getSeconds() >> 1)) & 0xFFFF;
        }
        static _dosDate(d) {
            return (((d.getFullYear() - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate()) & 0xFFFF;
        }

        async addBlob(fileName, blob) {
            const name = this._uniqueName(fileName);
            const nameBytes = new TextEncoder().encode(name);
            const size = blob.size;
            const zip64 = size >= U32_MAX;
            const now = new Date();
            const localOffset = this.offset;

            let crc = 0;
            const SLICE = 4 * 1024 * 1024;
            for (let pos = 0; pos < size; pos += SLICE) {
                const buf = await blob.slice(pos, Math.min(pos + SLICE, size)).arrayBuffer();
                crc = CRC32.update(crc, new Uint8Array(buf));
            }

            const extraLen = zip64 ? 20 : 0;
            const header = new DataView(new ArrayBuffer(30));
            header.setUint32(0, 0x04034b50, true);
            header.setUint16(4, zip64 ? 45 : 20, true);
            header.setUint16(6, 0x0800, true);
            header.setUint16(8, 0, true);
            header.setUint16(10, ZipWriter._dosTime(now), true);
            header.setUint16(12, ZipWriter._dosDate(now), true);
            header.setUint32(14, crc, true);
            header.setUint32(18, zip64 ? U32_MAX : size, true);
            header.setUint32(22, zip64 ? U32_MAX : size, true);
            header.setUint16(26, nameBytes.length, true);
            header.setUint16(28, extraLen, true);
            await this._write(new Uint8Array(header.buffer));
            await this._write(nameBytes);

            if (zip64) {
                const ex = new DataView(new ArrayBuffer(20));
                ex.setUint16(0, 0x0001, true);
                ex.setUint16(2, 16, true);
                ex.setBigUint64(4, BigInt(size), true);
                ex.setBigUint64(12, BigInt(size), true);
                await this._write(new Uint8Array(ex.buffer));
            }

            for (let pos = 0; pos < size; pos += SLICE) {
                const buf = await blob.slice(pos, Math.min(pos + SLICE, size)).arrayBuffer();
                await this._write(new Uint8Array(buf));
            }

            this.entries.push({ name: nameBytes, crc, size, localOffset, time: ZipWriter._dosTime(now), date: ZipWriter._dosDate(now) });
        }

        async close() {
            const cdStart = this.offset;

            for (const e of this.entries) {
                const bigSize = e.size >= U32_MAX;
                const bigOffset = e.localOffset >= U32_MAX;
                const extraVals = [];
                if (bigSize) extraVals.push(BigInt(e.size), BigInt(e.size));
                if (bigOffset) extraVals.push(BigInt(e.localOffset));
                const extraLen = extraVals.length ? 4 + extraVals.length * 8 : 0;

                const cd = new DataView(new ArrayBuffer(46));
                cd.setUint32(0, 0x02014b50, true);
                cd.setUint16(4, 45, true);
                cd.setUint16(6, extraLen ? 45 : 20, true);
                cd.setUint16(8, 0x0800, true);
                cd.setUint16(10, 0, true);
                cd.setUint16(12, e.time, true);
                cd.setUint16(14, e.date, true);
                cd.setUint32(16, e.crc, true);
                cd.setUint32(20, bigSize ? U32_MAX : e.size, true);
                cd.setUint32(24, bigSize ? U32_MAX : e.size, true);
                cd.setUint16(28, e.name.length, true);
                cd.setUint16(30, extraLen, true);
                cd.setUint16(32, 0, true);
                cd.setUint16(34, 0, true);
                cd.setUint16(36, 0, true);
                cd.setUint32(38, 0, true);
                cd.setUint32(42, bigOffset ? U32_MAX : e.localOffset, true);
                await this._write(new Uint8Array(cd.buffer));
                await this._write(e.name);

                if (extraLen) {
                    const ex = new DataView(new ArrayBuffer(extraLen));
                    ex.setUint16(0, 0x0001, true);
                    ex.setUint16(2, extraLen - 4, true);
                    extraVals.forEach((v, i) => ex.setBigUint64(4 + i * 8, v, true));
                    await this._write(new Uint8Array(ex.buffer));
                }
            }

            const cdSize = this.offset - cdStart;
            const needZip64 =
                this.entries.length > 0xFFFF || cdStart >= U32_MAX || cdSize >= U32_MAX ||
                this.entries.some(e => e.size >= U32_MAX || e.localOffset >= U32_MAX);

            if (needZip64) {
                const eocd64Offset = this.offset;
                const z = new DataView(new ArrayBuffer(56));
                z.setUint32(0, 0x06064b50, true);
                z.setBigUint64(4, BigInt(44), true);
                z.setUint16(12, 45, true);
                z.setUint16(14, 45, true);
                z.setUint32(16, 0, true);
                z.setUint32(20, 0, true);
                z.setBigUint64(24, BigInt(this.entries.length), true);
                z.setBigUint64(32, BigInt(this.entries.length), true);
                z.setBigUint64(40, BigInt(cdSize), true);
                z.setBigUint64(48, BigInt(cdStart), true);
                await this._write(new Uint8Array(z.buffer));

                const loc = new DataView(new ArrayBuffer(20));
                loc.setUint32(0, 0x07064b50, true);
                loc.setUint32(4, 0, true);
                loc.setBigUint64(8, BigInt(eocd64Offset), true);
                loc.setUint32(16, 1, true);
                await this._write(new Uint8Array(loc.buffer));
            }

            const count = Math.min(this.entries.length, 0xFFFF);
            const eocd = new DataView(new ArrayBuffer(22));
            eocd.setUint32(0, 0x06054b50, true);
            eocd.setUint16(4, 0, true);
            eocd.setUint16(6, 0, true);
            eocd.setUint16(8, count, true);
            eocd.setUint16(10, count, true);
            eocd.setUint32(12, Math.min(cdSize, U32_MAX), true);
            eocd.setUint32(16, Math.min(cdStart, U32_MAX), true);
            eocd.setUint16(20, 0, true);
            await this._write(new Uint8Array(eocd.buffer));

            if (this.writable) {
                await this.writable.close();
                return null;
            }
            return new Blob(this.parts, { type: "application/zip" });
        }
    }

    // ─── Download Engine ─────────────────────────────────────────────────
    const Downloader = {
        _queue: [],
        _active: new Map(),

        enqueue(url, type = "video", nameOverride = null) {
            return new Promise((resolve, reject) => {
                const id = Utils.uid();
                const fallbackExt = { audio: "ogg", image: "jpeg", document: "bin", video: "mp4" }[type] || "mp4";
                const fileName = nameOverride || Utils.fileNameFromUrl(url, fallbackExt);
                const item = { id, url, type, fileName, resolve, reject };
                this._queue.push(item);
                UI.createToast(id, fileName);
                this._tick();
            });
        },

        async fetchToBlob(url, type = "video", { onProgress, signal, retries = 4 } = {}) {
            if (url.startsWith("blob:")) {
                const cached = NetSniffer.getBlob(url);
                if (cached) return cached;
                return await (await fetch(url, { signal })).blob();
            }

            const mimeMap = { audio: "audio/ogg", video: "video/mp4", image: "image/jpeg", document: "application/octet-stream" };
            let attempt = 0;

            while (true) {
                try {
                    const parts = [];
                    let offset = 0, totalSize = null;

                    while (true) {
                        const res = await fetch(url, { method: "GET", headers: { Range: `bytes=${offset}-` }, signal });
                        if (![200, 206].includes(res.status)) throw new Error(`HTTP ${res.status}`);

                        if (res.status === 200) {
                            const blob = await res.blob();
                            parts.push(blob);
                            offset = blob.size;
                            totalSize = blob.size;
                            onProgress?.(offset, totalSize);
                            break;
                        }

                        const range = Utils.parseRange(res.headers.get("Content-Range"));
                        if (!range) throw new Error("Missing Content-Range on a 206 response");
                        if (range.start !== offset) throw new Error("Gap in byte range");
                        totalSize = range.total;
                        offset = range.end + 1;
                        parts.push(await res.blob());
                        onProgress?.(offset, totalSize);
                        if (offset >= totalSize) break;
                    }

                    const blob = new Blob(parts, { type: mimeMap[type] || "application/octet-stream" });
                    if (blob.size === 0) throw new Error("Empty response");
                    return blob;
                } catch (err) {
                    if (signal?.aborted) throw err;
                    if (++attempt > retries) throw err;
                    log.warn(`fetchToBlob retry ${attempt}/${retries}: ${err.message}`, url.slice(0, 60));
                    await Utils.sleep(1500 * attempt);
                }
            }
        },

        cancel(id) {
            this._queue = this._queue.filter(i => i.id !== id);
            const active = this._active.get(id);
            if (active?.abortCtrl) active.abortCtrl.abort();
            this._active.delete(id);
            this._tick();
        },

        _tick() {
            while (this._active.size < CONFIG.MAX_CONCURRENT && this._queue.length > 0) {
                this._start(this._queue.shift());
            }
            for (const item of this._queue) UI.updateToast(item.id, { state: "queued" });
        },

        async _start(item) {
            const abortCtrl = new AbortController();
            this._active.set(item.id, { abortCtrl, item });

            if (item.url.startsWith("blob:")) {
                const cached = NetSniffer.getBlob(item.url);
                try {
                    const blob = cached || await (await fetch(item.url)).blob();
                    await this._saveBlob(blob, item.fileName);
                    UI.updateToast(item.id, { state: "done", fileName: item.fileName });
                    this._active.delete(item.id);
                    item.resolve(item.fileName);
                    this._tick();
                } catch (e) {
                    log.error(`Blob download failed: ${e.message}`, item.fileName);
                    UI.updateToast(item.id, { state: "error" });
                    this._active.delete(item.id);
                    item.reject(e);
                    this._tick();
                }
                return;
            }

            if (item.type === "image") { this._downloadDirect(item); return; }

            let blobs = [], offset = 0, totalSize = null, realFileName = item.fileName;

            const fetchChunk = async (retries = 3) => {
                if (abortCtrl.signal.aborted) return;
                try {
                    const res = await fetch(item.url, {
                        method: "GET", headers: { Range: `bytes=${offset}-` }, signal: abortCtrl.signal,
                    });
                    if (![200, 206].includes(res.status)) throw new Error(`HTTP ${res.status}`);

                    const { ext } = Utils.parseMime(res.headers.get("Content-Type"));
                    const dot = realFileName.lastIndexOf(".");
                    if (dot > 0 && ext !== "bin") realFileName = realFileName.slice(0, dot + 1) + ext;

                    const range = Utils.parseRange(res.headers.get("Content-Range"));
                    if (range) {
                        if (range.start !== offset) throw new Error("Gap in byte range");
                        totalSize = range.total; offset = range.end + 1;
                    } else if (res.status === 200) {
                        const blob = await res.blob();
                        totalSize = blob.size; offset = totalSize; blobs.push(blob);
                        UI.updateToast(item.id, { fileName: realFileName, pct: 100, downloaded: offset, total: totalSize });
                        finalize(); return;
                    }

                    blobs.push(await res.blob());
                    const pct = totalSize ? Math.floor((offset * 100) / totalSize) : 0;
                    UI.updateToast(item.id, { fileName: realFileName, pct, downloaded: offset, total: totalSize });

                    if (offset < totalSize) await fetchChunk();
                    else finalize();
                } catch (err) {
                    if (abortCtrl.signal.aborted) return;
                    if (retries > 0) {
                        log.warn(`Retry (${retries}): ${err.message}`, realFileName);
                        await new Promise(r => setTimeout(r, 3000));
                        await fetchChunk(retries - 1);
                    } else {
                        UI.updateToast(item.id, { state: "error" });
                        log.error(`Failed: ${err.message}`, realFileName);
                        setTimeout(() => { offset = 0; blobs = []; totalSize = null; UI.updateToast(item.id, { pct: 0, state: null }); fetchChunk(); }, CONFIG.RETRY_DELAY);
                    }
                }
            };

            const finalize = () => {
                const mimeMap = { audio: "audio/ogg", video: "video/mp4", document: "application/octet-stream" };
                this._saveBlob(new Blob(blobs, { type: mimeMap[item.type] || "application/octet-stream" }), realFileName);
                UI.updateToast(item.id, { state: "done", fileName: realFileName });
                UI.removeToast(item.id);
                this._active.delete(item.id);
                item.resolve(realFileName);
                this._tick();
            };

            fetchChunk();
        },

        _downloadDirect(item) {
            const a = document.createElement("a");
            a.href = item.url; a.download = item.fileName;
            a.dataset.tgdlOwn = "1";
            document.body.appendChild(a); a.click(); a.remove();
            UI.updateToast(item.id, { state: "done" });
            UI.removeToast(item.id, 3000);
            this._active.delete(item.id);
            item.resolve(item.fileName);
            this._tick();
        },

        async _saveBlob(blob, fileName) {
            const supportsFS = typeof window.showSaveFilePicker === "function";
            if (supportsFS) {
                try {
                    const handle = await window.showSaveFilePicker({ suggestedName: fileName });
                    const writable = await handle.createWritable();
                    await writable.write(blob); await writable.close();
                    log.info("Saved via FS API", fileName); return;
                } catch (err) {
                    if (err.name === "AbortError") return;
                }
            }
            const url = URL.createObjectURL(blob);
            const a = document.createElement("a");
            a.href = url; a.download = fileName;
            a.dataset.tgdlOwn = "1";
            document.body.appendChild(a); a.click(); a.remove();
            URL.revokeObjectURL(url);
        },
    };

    // ─── Media Scanner ───────────────────────────────────────────────────
    const MediaScanner = {
        _midCounter: 0,
        _seq: 0,

        _midOf(bubble) {
            let mid = bubble.dataset.mid || bubble.dataset.messageId || bubble.getAttribute("data-message-id");
            if (!mid && bubble.id) mid = bubble.id;
            if (!mid) {
                mid = bubble.dataset.tgdlMid;
                if (!mid) {
                    mid = "gen" + (++this._midCounter);
                    bubble.dataset.tgdlMid = mid;
                }
            }
            return String(mid);
        },

        _bubbles() {
            return document.querySelectorAll(
                ".bubble[data-mid], .bubble, .Message[data-message-id], .Message"
            );
        },

        scanNew(seen) {
            const results = [];

            const add = (bubble, slot, url, type, name, thumbUrl, docEl) => {
                const mid = this._midOf(bubble);
                const key = `${mid}#${slot}`;
                if (seen.has(key)) return;
                if (name && seen.has(`name#${name}`)) return;
                if (url && url.startsWith("data:")) return;
                if (!url && !docEl) return;
                seen.add(key);
                if (name) seen.add(`name#${name}`);
                const fallbackExt = { video: "mp4", image: "jpeg", audio: "ogg", document: "bin" }[type] || "bin";
                const fileName = name || (url ? Utils.fileNameFromUrl(url, fallbackExt) : `${key}.${fallbackExt}`);

                const digits = String(mid).replace(/\D+/g, "");
                const orderKey = digits ? parseInt(digits, 10) : null;

                results.push({
                    key, mid, url: url || null, type, fileName,
                    label: name || null,
                    thumbUrl: thumbUrl || null,
                    pending: !url,
                    orderKey,
                    slot,
                    seq: ++MediaScanner._seq,
                });
            };

            const isChrome = (el) =>
                el.closest(".user-avatar, .Avatar, .peer-avatar, .avatar, .chat-info, .sidebar, #tgdl-batch-overlay, .tgdl-batch-panel");

            for (const bubble of this._bubbles()) {
                if (isChrome(bubble)) continue;

                bubble.querySelectorAll("video").forEach((v, i) => {
                    const src = v.src || v.currentSrc || v.querySelector("source")?.src || null;
                    add(bubble, `v${i}`, src, "video", null, v.poster || null, v);
                });

                bubble.querySelectorAll(
                    ".media-inner img, .media-container img, .attachment img, .media-photo, " +
                    "img.media-photo, .thumbnail img, .sticker-container img, .Message .media-inner img"
                ).forEach((img, i) => {
                    if (isChrome(img)) return;
                    const src = img.src || img.currentSrc;
                    if (!src || src.startsWith("data:")) return;
                    if (/avatar/i.test(src)) return;
                    const w = img.naturalWidth;
                    if (w > 0 && w <= 60) return;
                    add(bubble, `i${i}`, src, "image", null, src);
                });

                bubble.querySelectorAll("audio").forEach((a, i) => {
                    add(bubble, `a${i}`, a.src || a.getAttribute("src") || null, "audio", null, null, a);
                });

                const docMatches = [...bubble.querySelectorAll(
                    ".document, .document-container, .document-wrapper, .audio, " +
                    ".Message .File, .File, .Audio"
                )];
                const docEls = docMatches.filter(
                    el => !docMatches.some(other => other !== el && el.contains(other))
                );

                const namesInBubble = new Set();
                docEls.forEach((doc, i) => {
                    if (isChrome(doc)) return;

                    const nameEl = doc.querySelector(
                        ".document-name, .document-name span, .file-title, .File-name, " +
                        ".audio-title, .Audio-title, .text-bold, .file-name"
                    );
                    const name = Utils.cleanFileName(
                        nameEl?.getAttribute("title") ||
                        doc.getAttribute("title") ||
                        nameEl?.textContent || ""
                    );
                    if (!name) return;

                    if (namesInBubble.has(name)) return;
                    namesInBubble.add(name);

                    const type = Utils.typeFromName(name);

                    let url = null;
                    for (const a of doc.querySelectorAll("a[href]")) {
                        const h = a.href;
                        if (h && !h.startsWith("javascript:") && NetSniffer.isMediaUrl(h)) { url = h; break; }
                    }
                    if (!url) {
                        const media = doc.querySelector("video, audio");
                        url = media?.src || media?.currentSrc || null;
                    }

                    add(bubble, `d${i}`, url, type, name, null, doc);
                });
            }

            for (const rec of NetSniffer.urls.values()) {
                if (!rec.named) continue;
                const key = `net#${rec.url}`;
                if (seen.has(key)) continue;
                if (seen.has(`name#${rec.fileName}`)) continue;
                seen.add(key);
                seen.add(`name#${rec.fileName}`);
                results.push({
                    key, mid: null, url: rec.url, type: rec.type,
                    fileName: rec.fileName, label: rec.fileName, thumbUrl: null, pending: false,
                    orderKey: null, slot: "net", seq: ++MediaScanner._seq,
                });
            }

            return results;
        },

        viewerMedia() {
            const wk = document.querySelector(".media-viewer-whole");
            if (wk) {
                const aspecter = wk.querySelector(".media-viewer-movers .media-viewer-aspecter") || wk;
                const v = aspecter.querySelector("video");
                const src = v?.src || v?.currentSrc || v?.querySelector("source")?.src;
                if (src && !src.startsWith("data:")) return { url: src, type: "video" };
                const img = aspecter.querySelector("img.thumbnail, img.media-photo, img");
                if (img?.src && !img.src.startsWith("data:")) return { url: img.src, type: "image" };
            }

            const slide = document.querySelector("#MediaViewer .MediaViewerSlide--active") ||
                document.querySelector("#MediaViewer");
            if (slide) {
                const isVideo = !!(slide.querySelector("video") || slide.querySelector(".VideoPlayer"));
                const v = slide.querySelector("video");
                const src = v?.src || v?.currentSrc;
                if (src && !src.startsWith("data:")) return { url: src, type: "video" };
                if (!isVideo) {
                    const img = slide.querySelector(".MediaViewerContent img, img");
                    if (img?.src && !img.src.startsWith("data:")) return { url: img.src, type: "image" };
                }
            }

            const stories = document.getElementById("stories-viewer");
            if (stories) {
                const v = stories.querySelector("video.media-video");
                const src = v?.src || v?.currentSrc;
                if (src) return { url: src, type: "video" };
            }
            return null;
        },

        _harvestBlob(ts) {
            let best = null;
            for (const url of NetSniffer.blobsSince(ts)) {
                const blob = NetSniffer.getBlob(url);
                if (!blob || blob.size < CONFIG.MIN_BLOB_BYTES) continue;
                if (!best || blob.size > best.size) best = blob;
            }
            return best;
        },

        viewerIsOpen() {
            return !!(document.querySelector(".media-viewer-whole") ||
                document.querySelector("#MediaViewer") ||
                document.getElementById("stories-viewer"));
        },

        async closeViewer() {
            if (!this.viewerIsOpen()) return;
            const closeBtn = document.querySelector(
                ".media-viewer-topbar .btn-icon.tgico-close, .media-viewer-buttons .tgico-close, " +
                "#MediaViewer .MediaViewerActions button[aria-label*='lose'], .media-viewer-close"
            );
            if (closeBtn) closeBtn.click();
            else {
                for (const type of ["keydown", "keyup"]) {
                    document.dispatchEvent(new KeyboardEvent(type, {
                        key: "Escape", code: "Escape", keyCode: 27, which: 27, bubbles: true,
                    }));
                }
            }
            for (let i = 0; i < 10 && this.viewerIsOpen(); i++) await Utils.sleep(100);
        },

        async resolveFromElement(el, fileName) {
            if (!el || !el.isConnected) return null;

            el.scrollIntoView({ block: "center" });
            await Utils.sleep(350);

            const candidates = [];
            const push = (node, label) => {
                if (node && !candidates.some(c => c.node === node)) candidates.push({ node, label });
            };

            for (const sel of [
                ".file-icon-container .action-icon",
                ".file-icon-container .icon-download",
                ".icon-download",
                ".action-icon",
                ".file-icon-container",
                ".file-icon",
            ]) push(el.querySelector(sel), sel);

            for (const sel of [
                ".document-download", ".File-download", ".Audio-download", ".audio-download",
                ".tgico-download", ".document-ico", ".document-icon",
            ]) push(el.querySelector(sel), sel);

            push(el.querySelector(".preloader-container"), ".preloader-container");
            push(el.querySelector(".document, .document-wrapper, .file-info"), "inner row");
            push(el, "element");

            for (const cand of candidates) {
                if (!el.isConnected) break;

                const before = Date.now() - 50;
                SaveInterceptor.arm();
                try {
                    cand.node.click();

                    let active = false;
                    const probeEnd = Date.now() + CONFIG.DOC_PROBE_MS;
                    while (Date.now() < probeEnd) {
                        await Utils.sleep(150);
                        if (SaveInterceptor.captured.length) { active = true; break; }
                        if (NetSniffer.since(before).length) { active = true; break; }
                        if (NetSniffer.blobsSince(before).length) { active = true; break; }
                        if (this.viewerIsOpen()) { active = true; break; }
                        if (el.isConnected) {
                            if (el.querySelector(BUSY_SELECTOR)) {
                                active = true; break;
                            }
                            const m = el.querySelector("video, audio");
                            if (m?.src || m?.currentSrc) { active = true; break; }
                        }
                    }

                    if (!active) {
                        log.warn(`No response from "${cand.label}"`, fileName);
                        SaveInterceptor.disarm();
                        continue;
                    }

                    const hardDeadline = Date.now() + CONFIG.DOC_MAX_WAIT;
                    let lastActivity = Date.now();

                    while (Date.now() < hardDeadline) {
                        const grabbed = SaveInterceptor.take(fileName);
                        if (grabbed) return { blob: grabbed.blob };

                        const shown = this.viewerMedia();
                        if (shown) {
                            await this.closeViewer();
                            return { url: shown.url };
                        }

                        if (el.isConnected) {
                            const media = el.querySelector("video, audio");
                            const direct = media?.src || media?.currentSrc;
                            if (direct && !direct.startsWith("data:")) return { url: direct };
                            for (const a of el.querySelectorAll("a[href]")) {
                                if (NetSniffer.isMediaUrl(a.href)) return { url: a.href };
                            }
                        }

                        const harvested = this._harvestBlob(before);
                        if (harvested) return { blob: harvested };

                        const fresh = NetSniffer.since(before);
                        const named = fresh.find(r => r.fileName === fileName);
                        if (named) return { url: named.url };

                        const wanted = Utils.typeFromName(fileName);
                        const sameKind = fresh.filter(r => r.type === wanted);
                        if (sameKind.length === 1) return { url: sameKind[0].url };

                        const busy = el.isConnected && el.querySelector(
                            BUSY_SELECTOR
                        );
                        if (busy) lastActivity = Date.now();
                        if (Date.now() - lastActivity > CONFIG.DOC_IDLE_TIMEOUT) break;

                        await Utils.sleep(300);
                    }

                    await SaveInterceptor.settle();
                    const late = SaveInterceptor.take(fileName);
                    if (late) return { blob: late.blob };
                    const lateBlob = this._harvestBlob(before);
                    if (lateBlob) return { blob: lateBlob };

                    log.warn(`"${cand.label}" started something but produced no file`, fileName);
                } finally {
                    SaveInterceptor.disarm();
                    await this.closeViewer();
                }
            }

            try {
                const skeleton = [...el.querySelectorAll("*")]
                    .slice(0, 40)
                    .map(n => n.className && typeof n.className === "string" ? `.${n.className.trim().split(/\s+/).join(".")}` : n.tagName.toLowerCase())
                    .join(" ");
                log.error(`No control produced a download. Markup: ${el.className} >> ${skeleton}`, fileName);
            } catch {
                log.error("No control on this message produced a download", fileName);
            }
            return null;
        },

        renderedDocs() {
            const matches = [...document.querySelectorAll(
                ".document, .document-container, .document-wrapper, .audio, .File, .Audio"
            )];
            const innermost = matches.filter(
                el => !matches.some(other => other !== el && el.contains(other))
            );

            const out = [];
            for (const el of innermost) {
                if (el.closest("#tgdl-batch-overlay, .sidebar, .chat-info")) continue;
                const nameEl = el.querySelector(
                    ".document-name, .document-name span, .file-title, .File-name, " +
                    ".audio-title, .Audio-title, .text-bold, .file-name"
                );
                const name = Utils.cleanFileName(
                    nameEl?.getAttribute("title") || el.getAttribute("title") || nameEl?.textContent || ""
                );
                if (name) out.push({ el, name });
            }
            return out;
        },
    };

    // ─── Media Detector (MutationObserver) ───────────────────────────────
    const MediaDetector = {
        _observer: null,
        _debounceTimer: null,

        init() {
            this._scan();
            this._observer = new MutationObserver(() => {
                clearTimeout(this._debounceTimer);
                this._debounceTimer = setTimeout(() => this._scan(), CONFIG.OBSERVER_DEBOUNCE);
            });
            this._observer.observe(document.body, { childList: true, subtree: true });
            log.info("MutationObserver active");
        },

        _scan() {
            this._injectInlineButtons();
            this._scanViewerWebZ();
            this._scanViewerWebK();
        },

        // ── Per-message inline download buttons (Universal Telegram Blue) ──
        _injectInlineButtons() {
            const mediaSelectors = [
                ".Message .media-inner",
                ".Message .VideoPlayer",
                ".Message .File",
                ".Message .Audio",
                ".Message .RoundVideo",
                ".bubble .attachment",
                ".bubble .media-container",
                ".bubble .media-photo-container",
                ".bubble .document-container",
                ".bubble .document-wrapper",
                ".bubble .audio",
                ".bubble .round-video",
            ];

            for (const sel of mediaSelectors) {
                document.querySelectorAll(sel).forEach(container => {
                    if (container.querySelector(".tgdl-inline-dl")) return;
                    if (container.closest(".user-avatar, .Avatar, .peer-avatar, .avatar")) return;

                    let url = null, type = "image";
                    const video = container.querySelector("video");
                    const img = container.querySelector("img");
                    const audio = container.querySelector("audio") || container.closest("audio-element")?.audio;
                    const docLink = container.querySelector("a[href]");
                    const nameEl = container.querySelector(".document-name, .text-bold, .file-title, .File-name");
                    const name = nameEl?.textContent?.trim() || "";

                    const msgEl = container.closest(".Message, .bubble");
                    const hasDuration = !!(
                        container.querySelector(".video-time, .badge-duration, [class*='duration'], [class*='video-time']") ||
                        msgEl?.querySelector(".video-time, .badge-duration, [class*='duration'], [class*='video-time']")
                    );
                    const isVideo = !!(
                        video ||
                        container.classList.contains("VideoPlayer") ||
                        container.querySelector(".VideoPlayer, .round-video, video") ||
                        msgEl?.querySelector(".VideoPlayer, .round-video") ||
                        container.querySelector(".icon-play, button.action-icon, .play-icon, svg.icon-play") ||
                        hasDuration ||
                        (name && /\.(mp4|mkv|avi|mov|webm|flv|wmv|m4v|3gp)$/i.test(name))
                    );

                    if (isVideo) {
                        type = "video";
                        url = (video && (video.src || video.currentSrc || video.querySelector("source")?.src)) || "telegram://video-pending";
                    } else if (audio?.src) {
                        url = audio.src;
                        type = "audio";
                    } else if (docLink?.href && (
                        container.classList.contains("document-container") ||
                        container.classList.contains("document-wrapper") ||
                        container.classList.contains("File")
                    )) {
                        url = docLink.href;
                        const lowerName = (name || docLink.href).toLowerCase();
                        if (/\.(mp4|mkv|avi|mov|webm|flv|wmv|m4v|3gp)$/i.test(lowerName)) type = "video";
                        else if (/\.(mp3|ogg|wav|flac|aac|m4a|opus|wma)$/i.test(lowerName)) type = "audio";
                        else if (/\.(jpe?g|png|gif|webp|bmp|svg|tiff?)$/i.test(lowerName)) type = "image";
                        else type = "document";
                    } else if (img && !img.src.includes("avatar")) {
                        const w = img.naturalWidth;
                        if (w > 0 && w <= 60) return;
                        url = img.src;
                        type = "image";
                    }

                    if (!url || url.startsWith("data:")) return;

                    if (!container.classList.contains("tgdl-media-wrap")) {
                        container.classList.add("tgdl-media-wrap");
                    }

                    const btn = document.createElement("button");
                    btn.className = "tgdl-inline-dl" + (Utils.isDark() ? "" : " light");
                    btn.title = "Download";
                    btn.innerHTML = "\u{2B07}";
                    btn.onclick = async (e) => {
                        e.stopPropagation();
                        e.preventDefault();

                        if (type === "video") {
                            // Check if video element already has a valid stream URL attached
                            const curVideo = container.querySelector("video");
                            let directSrc = curVideo?.currentSrc || curVideo?.src;
                            if (directSrc && !directSrc.startsWith("data:") && (directSrc.startsWith("http") || directSrc.startsWith("blob:"))) {
                                Downloader.enqueue(directSrc, "video", name || null);
                                return;
                            }

                            // The video is not playing/buffering yet. Resolve the stream.
                            Toast.show("Loading video stream…", "info", 3000);
                            const before = Date.now() - 50;

                            // Trigger Telegram to start streaming this video
                            const trigger = container.querySelector(".action-icon, .icon-play, .play-icon, .media-inner, .video-player") || container;
                            trigger.click();

                            // Poll for video src or NetSniffer stream URL for up to 6 seconds
                            const deadline = Date.now() + 6000;
                            let resolved = null;
                            while (Date.now() < deadline) {
                                await Utils.sleep(250);

                                // Check container's video element
                                const vEl = container.querySelector("video");
                                const vSrc = vEl?.currentSrc || vEl?.src;
                                if (vSrc && !vSrc.startsWith("data:") && (vSrc.startsWith("http") || vSrc.startsWith("blob:"))) {
                                    resolved = vSrc;
                                    break;
                                }

                                // Check MediaViewer's video element if opened
                                const viewer = document.querySelector("#MediaViewer, .media-viewer-whole");
                                if (viewer) {
                                    const vView = viewer.querySelector("video");
                                    const viewSrc = vView?.currentSrc || vView?.src;
                                    if (viewSrc && !viewSrc.startsWith("data:") && (viewSrc.startsWith("http") || viewSrc.startsWith("blob:"))) {
                                        resolved = viewSrc;
                                        await MediaScanner.closeViewer();
                                        break;
                                    }
                                }

                                // Check NetSniffer for intercepted video stream
                                const fresh = NetSniffer.since(before).filter(r => r.type === "video");
                                if (fresh.length > 0) {
                                    resolved = fresh[0].url;
                                    await MediaScanner.closeViewer();
                                    break;
                                }

                                // Check SaveInterceptor for captured blob
                                const grabbed = SaveInterceptor.take(name || "video.mp4");
                                if (grabbed) {
                                    Downloader._saveBlob(grabbed.blob, name || "video.mp4");
                                    await MediaScanner.closeViewer();
                                    return;
                                }
                            }

                            if (resolved) {
                                Downloader.enqueue(resolved, "video", name || null);
                            } else {
                                Toast.show("Tap play on the video to start download", "warn", 4000);
                            }
                            return;
                        }

                        // For images, documents, audio
                        let currentUrl = url;
                        if (currentUrl && !currentUrl.startsWith("data:") && currentUrl !== "telegram://video-pending") {
                            Downloader.enqueue(currentUrl, type, name || null);
                        }
                    };
                    container.appendChild(btn);
                });
            }
        },

        _makeViewerBtn(classes, onClick, style = "webz") {
            const btn = document.createElement("button");
            btn.className = classes + " tel-download" + (Utils.isDark() ? "" : " light");
            btn.setAttribute("type", "button");
            btn.setAttribute("title", "Download");
            btn.setAttribute("aria-label", "Download");
            if (style === "webk") {
                btn.innerHTML = `<span class="tgico"></span>`;
            } else {
                const icon = document.createElement("i");
                icon.className = "icon icon-download";
                btn.appendChild(icon);
            }
            btn.addEventListener("click", onClick);
            return btn;
        },


        _scanViewerWebZ() {
            const stories = document.getElementById("StoryViewer");
            if (stories) {
                const header = stories.querySelector(".GrsJNw3y") || stories.querySelector(".DropdownMenu")?.parentNode;
                if (header && !header.querySelector(".tel-download")) {
                    header.insertBefore(
                        this._makeViewerBtn("Button TkphaPyQ tiny round", () => {
                            const vid = stories.querySelector("video");
                            const src = vid?.src || vid?.currentSrc;
                            if (src) Downloader.enqueue(src, "video");
                            else {
                                const imgs = stories.querySelectorAll("img.PVZ8TOWS");
                                const last = imgs[imgs.length - 1]?.src;
                                if (last) Downloader.enqueue(last, "image");
                            }
                        }),
                        header.querySelector("button")
                    );
                }
            }

            const slide = document.querySelector("#MediaViewer .MediaViewerSlide--active");
            const actions = document.querySelector("#MediaViewer .MediaViewerActions");
            if (!slide || !actions || actions.querySelector(".tel-download")) return;

            const video = slide.querySelector(".MediaViewerContent > .VideoPlayer video");
            const img = slide.querySelector(".MediaViewerContent > div > img");

            if (video?.currentSrc) {
                actions.prepend(this._makeViewerBtn("Button smaller round", () => {
                    Downloader.enqueue(video.currentSrc, "video");
                }));
            } else if (img?.src) {
                actions.prepend(this._makeViewerBtn("Button smaller round", () => {
                    Downloader.enqueue(img.src, "image");
                }));
            }
        },

        _scanViewerWebK() {
            const stories = document.getElementById("stories-viewer");
            if (stories) {
                for (const sel of ["[class^='_ViewerStoryHeaderRight']", "[class^='_ViewerStoryFooterRight']"]) {
                    const parent = stories.querySelector(sel);
                    if (parent && !parent.querySelector(".tel-download")) {
                        parent.prepend(this._makeViewerBtn("btn-icon rp", () => {
                            const vid = stories.querySelector("video.media-video");
                            const src = vid?.src || vid?.currentSrc;
                            if (src) Downloader.enqueue(src, "video");
                            else {
                                const imgSrc = stories.querySelector("img.media-photo")?.src;
                                if (imgSrc) Downloader.enqueue(imgSrc, "image");
                            }
                        }, "webk"));
                    }
                }
            }

            const viewer = document.querySelector(".media-viewer-whole");
            if (!viewer) return;
            const aspecter = viewer.querySelector(".media-viewer-movers .media-viewer-aspecter");
            const buttons = viewer.querySelector(".media-viewer-topbar .media-viewer-buttons");
            if (!aspecter || !buttons) return;

            buttons.querySelectorAll("button.btn-icon.hide").forEach(btn => btn.classList.remove("hide"));

            if (buttons.querySelector(".tgico-download") || buttons.querySelector(".tel-download")) return;

            const videoEl = aspecter.querySelector("video");
            const imgEl = aspecter.querySelector("img.thumbnail");

            if (videoEl?.src) {
                buttons.prepend(this._makeViewerBtn("btn-icon tgico-download", () => Downloader.enqueue(videoEl.src, "video"), "webk"));
            } else if (imgEl?.src) {
                buttons.prepend(this._makeViewerBtn("btn-icon tgico-download", () => Downloader.enqueue(imgEl.src, "image"), "webk"));
            }
        },
    };

    // ─── Bootstrap ───────────────────────────────────────────────────────
    NetSniffer.install();
    SaveInterceptor.install();
    UI.init();
    MediaDetector.init();
    log.info("Telegram Media Downloader ready (all buttons universal blue).");
})();
