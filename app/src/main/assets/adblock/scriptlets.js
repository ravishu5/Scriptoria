// Scriptlet implementations for Scriptoria's ad blocker.
//
// Filter lists reference scriptlets by name — "example.com##+js(set-constant, foo, false)" — so
// the names and argument shapes here follow uBlock Origin's documented interface. The code is
// Scriptoria's own; uBlock's implementations are GPL-3.0 and are deliberately not used.
//
// Everything lives in one closure and is handed to the page through a single entry point. There
// is no eval and no injected <script> tag: the whole library ships as a document-start script and
// only the arguments come from the native side, so a page's Content-Security-Policy cannot
// suppress it.

(function () {
    'use strict';

    if (window.__scriptoriaScriptlets) return;

    var noop = function () {};
    var trueFn = function () { return true; };
    var falseFn = function () { return false; };

    function randomToken() {
        return 'scriptoria_' + Math.floor(Math.random() * 1e10).toString(36);
    }

    /**
     * Builds a matcher from a filter-list needle: plain substring, /regex/flags, or either form
     * prefixed with "!" to invert it. An empty needle matches everything, which is what the
     * no-argument form of the prevent-* scriptlets means.
     */
    function toMatcher(needle) {
        if (needle === undefined || needle === null || needle === '') return trueFn;
        var invert = false;
        if (needle.charAt(0) === '!') {
            invert = true;
            needle = needle.slice(1);
        }
        var test;
        if (needle.length > 2 && needle.charAt(0) === '/' && needle.lastIndexOf('/') > 0) {
            var end = needle.lastIndexOf('/');
            try {
                var re = new RegExp(needle.slice(1, end), needle.slice(end + 1));
                test = function (s) { return re.test(s); };
            } catch (e) {
                return falseFn;
            }
        } else {
            test = function (s) { return s.indexOf(needle) !== -1; };
        }
        return invert ? function (s) { return !test(s); } : test;
    }

    /** The literal values filter lists are allowed to pass to set-constant. */
    function toValue(raw) {
        switch (raw) {
            case 'false': return false;
            case 'true': return true;
            case 'null': return null;
            case 'undefined': return undefined;
            case 'emptyArr': case '[]': return [];
            case 'emptyObj': case '{}': return {};
            case '\'\'': case '""': return '';
            case 'noopFunc': return noop;
            case 'trueFunc': return trueFn;
            case 'falseFunc': return falseFn;
            case 'noopPromiseResolve':
                return function () { return Promise.resolve(); };
            case 'noopPromiseReject':
                return function () { return Promise.reject(); };
        }
        if (/^-?\d+(\.\d+)?$/.test(raw)) return Number(raw);
        return raw;
    }

    /**
     * Applies [action] to the last link of a dotted property path, waiting for intermediate
     * objects that do not exist yet — the property a filter targets is usually created by the very
     * script the scriptlet is meant to defuse, which has not run at document-start.
     */
    function onChain(owner, path, action) {
        var dot = path.indexOf('.');
        if (dot === -1) {
            action(owner, path);
            return;
        }
        var head = path.slice(0, dot);
        var rest = path.slice(dot + 1);
        var value = owner[head];
        if (value !== null && typeof value === 'object') {
            onChain(value, rest, action);
            return;
        }
        var descriptor = Object.getOwnPropertyDescriptor(owner, head);
        if (descriptor && !descriptor.configurable) return;
        var current = value;
        try {
            Object.defineProperty(owner, head, {
                configurable: true,
                enumerable: descriptor ? descriptor.enumerable : true,
                get: function () { return current; },
                set: function (next) {
                    current = next;
                    if (next !== null && typeof next === 'object') onChain(next, rest, action);
                }
            });
        } catch (e) {}
    }

    /** Stops our own sentinel error from reaching the page's error handlers as noise. */
    function silence(token) {
        window.addEventListener('error', function (event) {
            if (event.message && event.message.indexOf(token) !== -1) {
                event.stopImmediatePropagation();
                event.preventDefault();
            }
        }, true);
    }

    /** Runs [fn] now and again on every DOM mutation, for scriptlets that edit elements. */
    function onDomChange(fn) {
        var scheduled = false;
        var run = function () {
            scheduled = false;
            try { fn(); } catch (e) {}
        };
        var schedule = function () {
            if (scheduled) return;
            scheduled = true;
            (window.requestAnimationFrame || window.setTimeout)(run, 0);
        };
        schedule();
        var start = function () {
            try {
                new MutationObserver(schedule).observe(document.documentElement, {
                    childList: true, subtree: true, attributes: true
                });
            } catch (e) {}
            schedule();
        };
        if (document.documentElement) start();
        else document.addEventListener('DOMContentLoaded', start, { once: true });
    }

    /** Reads the source of the inline script currently executing, if there is one. */
    function currentScriptText() {
        var el = document.currentScript;
        return el && el.textContent ? el.textContent : '';
    }

    var scriptlets = Object.create(null);

    // --- aborting property access ----------------------------------------------------------

    scriptlets['abort-on-property-read'] = function (path) {
        if (!path) return;
        var token = randomToken();
        silence(token);
        onChain(window, path, function (owner, prop) {
            try {
                Object.defineProperty(owner, prop, {
                    configurable: false,
                    get: function () { throw new ReferenceError(token); },
                    set: noop
                });
            } catch (e) {}
        });
    };

    scriptlets['abort-on-property-write'] = function (path) {
        if (!path) return;
        var token = randomToken();
        silence(token);
        onChain(window, path, function (owner, prop) {
            var value = owner[prop];
            try {
                Object.defineProperty(owner, prop, {
                    configurable: false,
                    get: function () { return value; },
                    set: function () { throw new ReferenceError(token); }
                });
            } catch (e) {}
        });
    };

    scriptlets['abort-current-inline-script'] = function (path, needle) {
        if (!path) return;
        var matches = toMatcher(needle);
        var token = randomToken();
        silence(token);
        onChain(window, path, function (owner, prop) {
            var descriptor = Object.getOwnPropertyDescriptor(owner, prop);
            var value = owner[prop];
            var read = descriptor && descriptor.get ? descriptor.get : function () { return value; };
            try {
                Object.defineProperty(owner, prop, {
                    configurable: true,
                    get: function () {
                        // Only the inline script the filter describes is aborted; every other
                        // reader of the same property is left working.
                        if (matches(currentScriptText())) throw new ReferenceError(token);
                        return read.call(this);
                    },
                    set: function (next) { value = next; }
                });
            } catch (e) {}
        });
    };

    // --- forcing values ---------------------------------------------------------------------

    scriptlets['set-constant'] = function (path, raw) {
        if (!path) return;
        var value = toValue(raw);
        onChain(window, path, function (owner, prop) {
            try {
                Object.defineProperty(owner, prop, {
                    configurable: false,
                    get: function () { return value; },
                    // Writes are swallowed rather than thrown on: the detector usually assigns to
                    // its own flag, and throwing there would break the page instead of the ad.
                    set: noop
                });
            } catch (e) {}
        });
    };

    scriptlets['set-cookie'] = function (name, value) {
        if (!name) return;
        try {
            document.cookie = encodeURIComponent(name) + '=' + encodeURIComponent(value || '') +
                '; path=/; expires=Fri, 31 Dec 9999 23:59:59 GMT';
        } catch (e) {}
    };

    scriptlets['remove-cookie'] = function (needle) {
        var matches = toMatcher(needle);
        var remove = function () {
            try {
                var host = document.location.hostname || '';
                document.cookie.split(';').forEach(function (pair) {
                    var name = pair.split('=')[0].trim();
                    if (!name || !matches(name)) return;
                    var expiry = '=; Max-Age=0; path=/;';
                    document.cookie = name + expiry;
                    document.cookie = name + expiry + ' domain=' + host + ';';
                    var parts = host.split('.');
                    while (parts.length > 1) {
                        document.cookie = name + expiry + ' domain=.' + parts.join('.') + ';';
                        parts.shift();
                    }
                });
            } catch (e) {}
        };
        remove();
        window.addEventListener('beforeunload', remove);
    };

    // --- editing the DOM --------------------------------------------------------------------

    scriptlets['remove-attr'] = function (attrs, selector) {
        if (!attrs) return;
        var names = attrs.split('|').map(function (s) { return s.trim(); });
        var target = selector || names.map(function (n) { return '[' + n + ']'; }).join(',');
        onDomChange(function () {
            document.querySelectorAll(target).forEach(function (el) {
                names.forEach(function (n) { el.removeAttribute(n); });
            });
        });
    };

    scriptlets['remove-class'] = function (classes, selector) {
        if (!classes) return;
        var names = classes.split('|').map(function (s) { return s.trim(); });
        var target = selector || names.map(function (n) { return '.' + CSS.escape(n); }).join(',');
        onDomChange(function () {
            document.querySelectorAll(target).forEach(function (el) {
                names.forEach(function (n) { el.classList.remove(n); });
            });
        });
    };

    // --- defusing timers and network calls --------------------------------------------------

    function preventTimer(which, needle, delayArg) {
        var matches = toMatcher(needle);
        var wantedDelay = delayArg === undefined || delayArg === '' ? null : parseInt(delayArg, 10);
        var original = window[which];
        if (typeof original !== 'function') return;
        window[which] = function (handler, delay) {
            try {
                var source = typeof handler === 'function' ? handler.toString() : String(handler);
                var delayMatches = wantedDelay === null || wantedDelay === delay;
                if (delayMatches && matches(source)) {
                    // Returning a real id keeps clearTimeout/clearInterval callers happy.
                    return original(noop, delay);
                }
            } catch (e) {}
            return original.apply(window, arguments);
        };
    }

    scriptlets['prevent-setTimeout'] = function (needle, delay) {
        preventTimer('setTimeout', needle, delay);
    };

    scriptlets['prevent-setInterval'] = function (needle, delay) {
        preventTimer('setInterval', needle, delay);
    };

    scriptlets['adjust-setInterval'] = function (needle, delayArg, boostArg) {
        var matches = toMatcher(needle);
        var wanted = delayArg ? parseInt(delayArg, 10) : 1000;
        var boost = boostArg ? parseFloat(boostArg) : 0.05;
        var original = window.setInterval;
        window.setInterval = function (handler, delay) {
            try {
                var source = typeof handler === 'function' ? handler.toString() : String(handler);
                if (delay === wanted && matches(source)) {
                    arguments[1] = delay * boost;
                }
            } catch (e) {}
            return original.apply(window, arguments);
        };
    };

    scriptlets['prevent-window-open'] = function (needle) {
        var matches = toMatcher(needle);
        var original = window.open;
        window.open = function (url) {
            try {
                if (matches(String(url || ''))) return null;
            } catch (e) {}
            return original.apply(window, arguments);
        };
    };

    scriptlets['nowebrtc'] = function () {
        ['RTCPeerConnection', 'webkitRTCPeerConnection', 'mozRTCPeerConnection'].forEach(function (n) {
            if (typeof window[n] !== 'function') return;
            try {
                window[n] = function () { throw new Error('RTCPeerConnection is disabled'); };
                window[n].prototype = { close: noop, createDataChannel: noop, createOffer: noop };
            } catch (e) {}
        });
    };

    scriptlets['noeval'] = function () {
        try { window.eval = function () {}; } catch (e) {}
    };

    scriptlets['prevent-eval-if'] = function (needle) {
        var matches = toMatcher(needle);
        var original = window.eval;
        if (typeof original !== 'function') return;
        try {
            window.eval = function (source) {
                if (matches(String(source))) return undefined;
                return original.apply(window, arguments);
            };
        } catch (e) {}
    };

    scriptlets['prevent-fetch'] = function (needle) {
        var matches = toMatcher(needle);
        var original = window.fetch;
        if (typeof original !== 'function') return;
        window.fetch = function (input) {
            try {
                var url = typeof input === 'string' ? input : (input && input.url) || '';
                if (matches(url)) {
                    return Promise.resolve(new Response('', { status: 200, statusText: 'OK' }));
                }
            } catch (e) {}
            return original.apply(window, arguments);
        };
    };

    scriptlets['prevent-xhr'] = function (needle) {
        var matches = toMatcher(needle);
        var open = XMLHttpRequest.prototype.open;
        var send = XMLHttpRequest.prototype.send;
        if (typeof open !== 'function') return;
        XMLHttpRequest.prototype.open = function (method, url) {
            this.__scriptoriaBlocked = matches(String(url || ''));
            return open.apply(this, arguments);
        };
        XMLHttpRequest.prototype.send = function () {
            if (!this.__scriptoriaBlocked) return send.apply(this, arguments);
            // Report an immediate empty success so callers take their normal path.
            var self = this;
            Object.defineProperty(self, 'readyState', { value: 4, configurable: true });
            Object.defineProperty(self, 'status', { value: 200, configurable: true });
            Object.defineProperty(self, 'responseText', { value: '', configurable: true });
            Object.defineProperty(self, 'response', { value: '', configurable: true });
            setTimeout(function () {
                try {
                    if (typeof self.onreadystatechange === 'function') self.onreadystatechange();
                    self.dispatchEvent(new Event('readystatechange'));
                    self.dispatchEvent(new Event('load'));
                    self.dispatchEvent(new Event('loadend'));
                } catch (e) {}
            }, 0);
        };
    };

    // --- reshaping data ---------------------------------------------------------------------

    scriptlets['json-prune'] = function (removeArg, requiredArg) {
        if (!removeArg) return;
        var toRemove = removeArg.split(/\s+/).filter(Boolean);
        var required = (requiredArg || '').split(/\s+/).filter(Boolean);

        var resolve = function (root, path) {
            var parts = path.split('.');
            var owner = root;
            for (var i = 0; i < parts.length - 1; i++) {
                if (owner === null || typeof owner !== 'object') return null;
                owner = owner[parts[i]];
            }
            if (owner === null || typeof owner !== 'object') return null;
            return { owner: owner, key: parts[parts.length - 1] };
        };

        var prune = function (root) {
            try {
                if (root === null || typeof root !== 'object') return root;
                // The "required" argument guards against pruning an unrelated payload that
                // happens to share a property name.
                for (var i = 0; i < required.length; i++) {
                    var probe = resolve(root, required[i]);
                    if (!probe || probe.owner[probe.key] === undefined) return root;
                }
                toRemove.forEach(function (path) {
                    var found = resolve(root, path);
                    if (found) delete found.owner[found.key];
                });
            } catch (e) {}
            return root;
        };

        var originalParse = JSON.parse;
        JSON.parse = function () {
            return prune(originalParse.apply(JSON, arguments));
        };

        if (window.Response && Response.prototype && Response.prototype.json) {
            var originalJson = Response.prototype.json;
            Response.prototype.json = function () {
                return originalJson.apply(this, arguments).then(prune);
            };
        }
    };

    scriptlets['log'] = function () {
        try {
            console.log('[Scriptoria scriptlet]', Array.prototype.slice.call(arguments).join(', '));
        } catch (e) {}
    };


    scriptlets['addEventListener-defuser'] = function (typeNeedle, handlerNeedle) {
        var typeMatches = toMatcher(typeNeedle);
        var handlerMatches = toMatcher(handlerNeedle);
        var original = EventTarget.prototype.addEventListener;
        if (typeof original !== 'function') return;
        EventTarget.prototype.addEventListener = function (type, handler) {
            try {
                var source = typeof handler === 'function'
                    ? handler.toString()
                    : String(handler);
                // Silently not registering is the point: the page keeps running, but the
                // listener that would pop the ad or the block-detector never fires.
                if (typeMatches(String(type)) && handlerMatches(source)) return undefined;
            } catch (e) {}
            return original.apply(this, arguments);
        };
    };

    scriptlets['adjust-setTimeout'] = function (needle, delayArg, boostArg) {
        var matches = toMatcher(needle);
        var wanted = delayArg ? parseInt(delayArg, 10) : 1000;
        var boost = boostArg ? parseFloat(boostArg) : 0.05;
        var original = window.setTimeout;
        window.setTimeout = function (handler, delay) {
            try {
                var source = typeof handler === 'function' ? handler.toString() : String(handler);
                if (delay === wanted && matches(source)) arguments[1] = delay * boost;
            } catch (e) {}
            return original.apply(window, arguments);
        };
    };

    scriptlets['abort-on-stack-trace'] = function (path, stackNeedle) {
        if (!path) return;
        var matches = toMatcher(stackNeedle);
        var token = randomToken();
        silence(token);
        onChain(window, path, function (owner, prop) {
            var value = owner[prop];
            try {
                Object.defineProperty(owner, prop, {
                    configurable: true,
                    get: function () {
                        // Only the caller the filter names is aborted; the property stays
                        // readable for everything else on the page.
                        var stack = '';
                        try { stack = new Error().stack || ''; } catch (e) {}
                        if (matches(stack)) throw new ReferenceError(token);
                        return value;
                    },
                    set: function (next) { value = next; }
                });
            } catch (e) {}
        });
    };

    function editNodeText(tagName, pattern, replacement) {
        var selector = (tagName || '*').trim() || '*';
        var matches = toMatcher(pattern);
        var replacer = null;
        if (replacement !== undefined) {
            if (pattern && pattern.charAt(0) === '/' && pattern.lastIndexOf('/') > 0) {
                var end = pattern.lastIndexOf('/');
                try {
                    replacer = new RegExp(pattern.slice(1, end), pattern.slice(end + 1));
                } catch (e) { return; }
            } else {
                replacer = pattern;
            }
        }
        onDomChange(function () {
            var nodes;
            try { nodes = document.querySelectorAll(selector); } catch (e) { return; }
            nodes.forEach(function (el) {
                var text = el.textContent || '';
                if (!text || !matches(text)) return;
                if (replacer === null) el.remove();
                else el.textContent = text.replace(replacer, replacement);
            });
        });
    }

    scriptlets['remove-node-text'] = function (tagName, pattern) {
        editNodeText(tagName, pattern, undefined);
    };

    scriptlets['replace-node-text'] = function (tagName, pattern, replacement) {
        editNodeText(tagName, pattern, replacement === undefined ? '' : replacement);
    };

    /**
     * BlockAdBlock and FuckAdBlock share an API, so one stand-in serves both: it answers every
     * check through the "nothing was detected" path instead of the site's paywall path.
     */
    function adBlockDetectorStub() {
        var stub = {
            setOption: function () { return stub; },
            clearEvent: function () { return stub; },
            on: function (detected, handler) {
                if (!detected && typeof handler === 'function') setTimeout(handler, 1);
                return stub;
            },
            onDetected: function () { return stub; },
            onNotDetected: function (handler) {
                if (typeof handler === 'function') setTimeout(handler, 1);
                return stub;
            },
            emitEvent: function () { return stub; },
            check: function () { return true; }
        };
        return stub;
    }

    scriptlets['nofab'] = function () {
        var stub = adBlockDetectorStub();
        var ctor = function () { return stub; };
        ['FuckAdBlock', 'BlockAdBlock', 'SniffAdBlock'].forEach(function (n) {
            try { window[n] = ctor; } catch (e) {}
        });
        ['fuckAdBlock', 'blockAdBlock', 'sniffAdBlock'].forEach(function (n) {
            try {
                Object.defineProperty(window, n, {
                    configurable: false,
                    get: function () { return stub; },
                    set: noop
                });
            } catch (e) {}
        });
    };

    scriptlets['nobab'] = scriptlets['nofab'];

    scriptlets['popads-dummy'] = function () {
        // The site only checks that its pop-under library loaded; giving it an inert object is
        // enough to stop the fallback that opens the pop-under by hand.
        ['PopAds', 'popns'].forEach(function (n) {
            try {
                Object.defineProperty(window, n, {
                    configurable: false,
                    get: function () { return { ads: [], showAd: noop, init: noop }; },
                    set: noop
                });
            } catch (e) {}
        });
    };

    scriptlets['refresh-defuser'] = function () {
        var strip = function () {
            try {
                document.querySelectorAll('meta[http-equiv="refresh" i]').forEach(function (el) {
                    el.remove();
                });
            } catch (e) {}
        };
        strip();
        document.addEventListener('DOMContentLoaded', strip, { once: true });
    };

    scriptlets['disable-newtab-links'] = function () {
        document.addEventListener('click', function (event) {
            var el = event.target;
            while (el && el.localName !== 'a') el = el.parentElement;
            if (el && el.target === '_blank') el.target = '_self';
        }, true);
    };

    // Filter lists use the short forms far more often than the long ones.
    var aliases = {
        'aopr': 'abort-on-property-read',
        'aopw': 'abort-on-property-write',
        'acis': 'abort-current-inline-script',
        'acs': 'abort-current-inline-script',
        'abort-current-script': 'abort-current-inline-script',
        'aeld': 'addEventListener-defuser',
        'addEventListener-defuser.js': 'addEventListener-defuser',
        'aost': 'abort-on-stack-trace',
        'rmnt': 'remove-node-text',
        'rpnt': 'replace-node-text',
        'nano-setTimeout-booster': 'adjust-setTimeout',
        'nano-stb': 'adjust-setTimeout',
        'nano-sib': 'adjust-setInterval',
        'set': 'set-constant',
        'ra': 'remove-attr',
        'rc': 'remove-class',
        'nostif': 'prevent-setTimeout',
        'no-setTimeout-if': 'prevent-setTimeout',
        'setTimeout-defuser': 'prevent-setTimeout',
        'nosiif': 'prevent-setInterval',
        'no-setInterval-if': 'prevent-setInterval',
        'setInterval-defuser': 'prevent-setInterval',
        'nano-setInterval-booster': 'adjust-setInterval',
        'nowoif': 'prevent-window-open',
        'window.open-defuser': 'prevent-window-open',
        'no-fetch-if': 'prevent-fetch',
        'no-xhr-if': 'prevent-xhr',
        'cookie-remover': 'remove-cookie',
        'jsonprune': 'json-prune',
        'noeval-if': 'prevent-eval-if'
    };

    /**
     * @param calls array of [name, ...args] as parsed on the native side.
     * @return the number of scriptlets that ran, for the caller to log.
     */
    window.__scriptoriaScriptlets = function (calls) {
        var ran = 0;
        for (var i = 0; i < calls.length; i++) {
            var call = calls[i];
            if (!call || !call.length) continue;
            var name = String(call[0]).replace(/\.js$/, '');
            var fn = scriptlets[name] || scriptlets[aliases[name]];
            // An unknown scriptlet is skipped, never guessed at: the filter simply has no effect,
            // which is the same outcome as before scriptlets were supported at all.
            if (typeof fn !== 'function') continue;
            try {
                fn.apply(null, call.slice(1));
                ran++;
            } catch (e) {}
        }
        return ran;
    };
})();
