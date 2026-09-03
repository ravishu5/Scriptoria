// Scriptoria Browser - Userscript GM API Shim
// Templated per-script at injection time:
// __SCRIPT_ID__ : Long ID of the script
// __GM_TOKEN__  : Random capability token verified by native layer
// __GM_INFO__   : Serialized JSON for GM_info

(function () {
    if (typeof globalThis === 'undefined') {
        try {
            (typeof self !== 'undefined' ? self : window).globalThis = (typeof self !== 'undefined' ? self : window);
        } catch (e) {}
    }

    var SCRIPT_ID = __SCRIPT_ID__;
    var GM_TOKEN = __GM_TOKEN__;
    var GM_INFO = __GM_INFO__;

    // Single shared registry per page for asynchronous event routing
    var hub = window.__scriptoriaHub;
    if (!hub) {
        hub = window.__scriptoriaHub = {
            xhrCallbacks: {},
            seq: 0,
            handleXhrEvent: function (reqId, eventName, payloadJson) {
                var self = this;
                // Dispatch on microtask/macrotask to avoid re-entrancy issues with Promise executors
                setTimeout(function () {
                    var cb = self.xhrCallbacks[reqId];
                    if (!cb) return;

                    var resp;
                    try {
                        resp = JSON.parse(payloadJson);
                    } catch (e) {
                        resp = { responseText: payloadJson, error: String(e) };
                    }

                    if (cb.responseType === 'json' && typeof resp.responseText === 'string') {
                        try {
                            resp.response = JSON.parse(resp.responseText);
                        } catch (e) {}
                    }

                    try {
                        if (eventName === 'progress' && cb.onprogress) {
                            cb.onprogress(resp);
                        } else if (eventName === 'error') {
                            if (cb.onerror) cb.onerror(resp);
                        } else if (eventName === 'timeout') {
                            if (cb.ontimeout) cb.ontimeout(resp);
                            else if (cb.onerror) cb.onerror(resp);
                        } else if (eventName === 'abort' && cb.onabort) {
                            cb.onabort(resp);
                        } else if (eventName === 'load') {
                            resp.readyState = 4;
                            if (cb.onreadystatechange) cb.onreadystatechange(resp);
                            if (cb.onload) cb.onload(resp);
                            if (cb.onloadend) cb.onloadend(resp);
                        }
                    } catch (cbErr) {
                        console.error('[Scriptoria] XHR callback error:', cbErr);
                    }

                    if (eventName === 'load' || eventName === 'error' || eventName === 'timeout' || eventName === 'abort') {
                        delete self.xhrCallbacks[reqId];
                    }
                }, 0);
            }
        };
    }

    var bridge = window.ScriptoriaNativeBridge;
    if (!bridge) {
        console.warn('[Scriptoria] Native bridge not found on window.ScriptoriaNativeBridge');
    }

    // ==========================================
    // Synchronous GM Value Storage
    // ==========================================
    function GM_getValue(key, defaultValue) {
        if (!bridge) return defaultValue;
        try {
            var raw = bridge.gmGetValue(GM_TOKEN, String(key));
            if (raw === null || raw === undefined) return defaultValue;
            return JSON.parse(raw);
        } catch (e) {
            return defaultValue;
        }
    }

    function GM_setValue(key, value) {
        if (!bridge) return;
        try {
            bridge.gmSetValue(GM_TOKEN, String(key), JSON.stringify(value));
        } catch (e) {
            console.error('[Scriptoria] GM_setValue error:', e);
        }
    }

    function GM_deleteValue(key) {
        if (!bridge) return;
        try {
            bridge.gmDeleteValue(GM_TOKEN, String(key));
        } catch (e) {
            console.error('[Scriptoria] GM_deleteValue error:', e);
        }
    }

    function GM_listValues() {
        if (!bridge) return [];
        try {
            var raw = bridge.gmListValues(GM_TOKEN);
            return JSON.parse(raw || '[]');
        } catch (e) {
            return [];
        }
    }

    // ==========================================
    // DOM & Styling Utilities
    // ==========================================
    function GM_addStyle(css) {
        var style = document.createElement('style');
        style.setAttribute('type', 'text/css');
        style.textContent = css;
        try {
            (document.head || document.documentElement).appendChild(style);
        } catch (e) {
            setTimeout(function () {
                (document.head || document.documentElement).appendChild(style);
            }, 0);
        }
        return style;
    }

    function GM_addElement(parentOrTag, tagOrAttrs, maybeAttrs) {
        var parent, tag, attrs;
        if (typeof parentOrTag === 'string') {
            parent = null;
            tag = parentOrTag;
            attrs = tagOrAttrs || {};
        } else {
            parent = parentOrTag;
            tag = tagOrAttrs;
            attrs = maybeAttrs || {};
        }

        var el = document.createElement(tag);
        for (var k in attrs) {
            if (attrs.hasOwnProperty(k)) {
                if (k === 'textContent') {
                    el.textContent = attrs[k];
                } else {
                    el.setAttribute(k, attrs[k]);
                }
            }
        }

        (parent || document.head || document.documentElement).appendChild(el);
        return el;
    }

    // ==========================================
    // GM_xmlhttpRequest (Cross-Origin Native XHR)
    // ==========================================
    function GM_xmlhttpRequest(details) {
        details = details || {};
        var reqId = SCRIPT_ID + ':' + (++hub.seq);

        hub.xhrCallbacks[reqId] = {
            onload: details.onload,
            onerror: details.onerror,
            ontimeout: details.ontimeout,
            onprogress: details.onprogress,
            onabort: details.onabort,
            onreadystatechange: details.onreadystatechange,
            onloadend: details.onloadend,
            responseType: details.responseType || ''
        };

        var wire = {
            method: (details.method || 'GET').toUpperCase(),
            url: details.url,
            headers: details.headers || {},
            data: details.data != null ? String(details.data) : null,
            timeout: details.timeout || 0
        };

        if (bridge) {
            try {
                bridge.gmXhr(GM_TOKEN, reqId, JSON.stringify(wire));
            } catch (e) {
                delete hub.xhrCallbacks[reqId];
                if (details.onerror) details.onerror({ error: String(e) });
            }
        }

        return {
            abort: function () {
                if (bridge) {
                    try { bridge.gmAbortXhr(GM_TOKEN, reqId); } catch (e) {}
                }
            }
        };
    }

    // ==========================================
    // UI, Notifications, Tabs & Clipboard
    // ==========================================
    function GM_notification(details, ondone) {
        var text = typeof details === 'object' ? (details.text || '') : String(details);
        var title = typeof details === 'object' ? (details.title || GM_INFO.script.name) : GM_INFO.script.name;
        if (bridge) {
            try {
                bridge.gmNotification(GM_TOKEN, title, text);
            } catch (e) {}
        }
        if (typeof ondone === 'function') {
            setTimeout(ondone, 1000);
        }
    }

    function GM_setClipboard(text, info) {
        if (bridge) {
            try { bridge.gmSetClipboard(GM_TOKEN, String(text)); } catch (e) {}
        }
    }

    function GM_openInTab(url, options) {
        var active = true;
        if (typeof options === 'boolean') active = !options;
        else if (options && typeof options === 'object') active = options.active !== false;

        if (bridge) {
            try { bridge.gmOpenInTab(GM_TOKEN, String(url), active); } catch (e) {}
        }
        return { closed: false, close: function () {} };
    }

    function GM_log() {
        var msg = Array.prototype.slice.call(arguments).map(function (arg) {
            return typeof arg === 'object' ? JSON.stringify(arg) : String(arg);
        }).join(' ');

        if (bridge) {
            try { bridge.gmLog(GM_TOKEN, msg); } catch (e) {}
        }
        console.log('[' + (GM_INFO.script.name || 'Userscript') + ']', msg);
    }

    function GM_registerMenuCommand(caption, fn) {
        var fnId = SCRIPT_ID + ':menu:' + (++hub.seq);
        if (bridge) {
            try { bridge.gmRegisterMenuCommand(GM_TOKEN, String(caption), fnId); } catch (e) {}
        }
        return fnId;
    }

    function GM_unregisterMenuCommand(fnId) {
        if (bridge) {
            try { bridge.gmUnregisterMenuCommand(GM_TOKEN, String(fnId)); } catch (e) {}
        }
    }

    // ==========================================
    // Promisified Helpers for modern GM.*
    // ==========================================
    function promisify(fn) {
        return function () {
            var args = arguments;
            return new Promise(function (resolve, reject) {
                try {
                    resolve(fn.apply(null, args));
                } catch (e) {
                    reject(e);
                }
            });
        };
    }

    function GM_xmlhttpRequestDual(details) {
        details = details || {};
        if (details.onload || details.onerror || details.onreadystatechange) {
            return GM_xmlhttpRequest(details);
        }
        return new Promise(function (resolve, reject) {
            var d = {};
            for (var k in details) d[k] = details[k];
            d.onload = resolve;
            d.onerror = reject;
            d.ontimeout = reject;
            GM_xmlhttpRequest(d);
        });
    }

    function GM_download(optionsOrUrl, name) {
        var url, fileName;
        if (typeof optionsOrUrl === 'object' && optionsOrUrl) {
            url = optionsOrUrl.url;
            fileName = optionsOrUrl.name;
        } else {
            url = optionsOrUrl;
            fileName = name;
        }
        if (bridge && url) {
            if (url.startsWith('blob:') || url.startsWith('data:')) {
                fetch(url)
                    .then(function(r) { return r.blob(); })
                    .then(function(blob) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            var base64 = (reader.result || '').split(',')[1];
                            if (base64) {
                                bridge.saveBlobDownload(fileName || 'download', base64, blob.type || '');
                            }
                        };
                        reader.readAsDataURL(blob);
                    });
            } else {
                bridge.downloadUrl(url, fileName || '', navigator.userAgent, '');
            }
        }
    }

    // Intercept <a download> clicks for blob:, data:, and http(s): URLs
    if (!window.__scriptoriaDownloadHook) {
        window.__scriptoriaDownloadHook = true;

        var origAnchorClick = HTMLAnchorElement.prototype.click;
        HTMLAnchorElement.prototype.click = function() {
            var href = this.href || this.getAttribute('href');
            var downloadAttr = this.download !== undefined && this.download !== null ? this.download : this.getAttribute('download');

            if (downloadAttr !== null && downloadAttr !== undefined && href && window.ScriptoriaNativeBridge) {
                var fileName = downloadAttr || 'download';

                if (href.startsWith('blob:') || href.startsWith('data:')) {
                    fetch(href)
                        .then(function(r) { return r.blob(); })
                        .then(function(blob) {
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                var base64 = (reader.result || '').split(',')[1];
                                if (base64) {
                                    var mime = blob.type || 'application/octet-stream';
                                    window.ScriptoriaNativeBridge.saveBlobDownload(fileName, base64, mime);
                                }
                            };
                            reader.readAsDataURL(blob);
                        })
                        .catch(function(err) {
                            console.error('[Scriptoria] Error saving blob download:', err);
                        });
                    return;
                } else if (href.startsWith('http://') || href.startsWith('https://')) {
                    window.ScriptoriaNativeBridge.downloadUrl(href, fileName, navigator.userAgent, '');
                    return;
                }
            }

            return origAnchorClick.apply(this, arguments);
        };
    }

    // Ensure mobile touch visibility for Telegram inline download buttons and add mobile viewer button
    if (!window.__scriptoriaMobileStylesInjected) {
        window.__scriptoriaMobileStylesInjected = true;
        try {
            var style = document.createElement('style');
            style.id = 'scriptoria-mobile-helper';
            style.textContent = '.tgdl-inline-dl { opacity: 0.9 !important; display: flex !important; visibility: visible !important; background: rgba(0,0,0,0.65) !important; box-shadow: 0 2px 6px rgba(0,0,0,0.4) !important; } .tgdl-batch-fab { bottom: 70px !important; }';
            (document.head || document.documentElement).appendChild(style);
        } catch (e) {}

        // Mobile Media Viewer floating download button
        setInterval(function() {
            var viewer = document.getElementById('MediaViewer') || document.querySelector('.media-viewer-whole, .MediaViewer');
            if (viewer && !viewer.querySelector('.tgdl-mobile-viewer-dl')) {
                var video = viewer.querySelector('video');
                var img = viewer.querySelector('img');
                var src = (video && (video.src || video.currentSrc)) || (img && img.src);
                if (src) {
                    var btn = document.createElement('button');
                    btn.className = 'tgdl-mobile-viewer-dl';
                    btn.innerHTML = '⬇️ Download Media';
                    btn.style.cssText = 'position:fixed;bottom:80px;right:20px;z-index:99999;background:#3390EC;color:#fff;padding:10px 18px;border-radius:24px;border:none;font-weight:bold;font-size:14px;box-shadow:0 4px 16px rgba(0,0,0,0.5);display:flex;align-items:center;gap:6px;cursor:pointer;';
                    btn.onclick = function(e) {
                        e.stopPropagation();
                        e.preventDefault();
                        var cur = (video && (video.src || video.currentSrc)) || src;
                        if (window.Downloader && window.Downloader.enqueue) {
                            window.Downloader.enqueue(cur, video ? 'video' : 'image');
                        } else {
                            var ext = video ? 'mp4' : 'jpg';
                            GM_download(cur, 'telegram_media_' + Date.now() + '.' + ext);
                        }
                    };
                    viewer.appendChild(btn);
                }
            }
        }, 800);
    }

    // ==========================================
    // Export Globals onto window
    // ==========================================
    var w = window;
    w.GM_getValue = GM_getValue;
    w.GM_setValue = GM_setValue;
    w.GM_deleteValue = GM_deleteValue;
    w.GM_listValues = GM_listValues;
    w.GM_addStyle = GM_addStyle;
    w.GM_addElement = GM_addElement;
    w.GM_xmlhttpRequest = GM_xmlhttpRequest;
    w.GM_notification = GM_notification;
    w.GM_setClipboard = GM_setClipboard;
    w.GM_openInTab = GM_openInTab;
    w.GM_log = GM_log;
    w.GM_registerMenuCommand = GM_registerMenuCommand;
    w.GM_unregisterMenuCommand = GM_unregisterMenuCommand;
    w.GM_download = GM_download;
    w.GM_info = GM_INFO;
    w.unsafeWindow = w;

    w.GM = {
        info: GM_INFO,
        getValue: promisify(GM_getValue),
        setValue: promisify(GM_setValue),
        deleteValue: promisify(GM_deleteValue),
        listValues: promisify(GM_listValues),
        addStyle: promisify(GM_addStyle),
        addElement: promisify(GM_addElement),
        notification: promisify(GM_notification),
        setClipboard: promisify(GM_setClipboard),
        openInTab: GM_openInTab,
        log: GM_log,
        registerMenuCommand: GM_registerMenuCommand,
        unregisterMenuCommand: GM_unregisterMenuCommand,
        download: GM_download,
        xmlHttpRequest: GM_xmlhttpRequestDual,
        xmlhttpRequest: GM_xmlhttpRequestDual
    };
})();
