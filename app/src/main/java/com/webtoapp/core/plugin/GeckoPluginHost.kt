package com.webtoapp.core.plugin

import org.json.JSONObject

/**
 * Page plugins on GeckoView. The engine has no `addJavascriptInterface`, so
 * HCJ talks to the host through `prompt()` tokens that [handlePrompt] claims
 * before a dialog is shown. Config, badges, and panel messages use that
 * channel. Chrome extensions and the Greasemonkey bridge stay on System WebView.
 */
object GeckoPluginHost {
    const val CONFIG_PROMPT = "__wta_hcj_cfg__"
    const val BADGE_PROMPT = "__wta_hcj_badge__"
    const val PANEL_PROMPT = "__wta_hcj_panel__"
    const val ACTION_PROMPT = "__wta_hcj_action__"

    fun runnable(plugins: List<PluginSession.Resolved>): List<PluginSession.Resolved> =
        plugins.filter { it.plugin.kind == PluginKind.HCJ || it.plugin.kind == PluginKind.USERSCRIPT }

    fun configSeed(store: PluginConfigStore, plugins: List<PluginSession.Resolved>): String {
        val root = JSONObject()
        for (resolved in runnable(plugins)) {
            if (!resolved.plugin.hasPermission(PluginPermission.STORAGE)) continue
            val raw = store.all(resolved.plugin.id)
            val bag = try {
                JSONObject(raw)
            } catch (_: Exception) {
                JSONObject()
            }
            root.put(resolved.plugin.id, bag)
        }
        return root.toString()
    }

    fun pageScript(
        plugins: List<PluginSession.Resolved>,
        url: String,
        appLang: String,
        configSeedJson: String,
        pluginTokens: Map<String, String> = emptyMap()
    ): String {
        val matching = runnable(plugins).filter { it.plugin.matchesUrl(url) }
        if (matching.isEmpty()) return ""
        val hcj = matching.filter { it.plugin.kind == PluginKind.HCJ }
        val userscripts = matching.filter { it.plugin.kind == PluginKind.USERSCRIPT }
        val parts = ArrayList<String>()
        parts += CLEANUP_JS
        if (hcj.isNotEmpty()) {
            parts += bridgeStub(configSeedJson, pluginTokens)
            parts += PluginInjection.BOOTSTRAP_JS
            parts += "window.__wtaAppLang=${jsString(appLang)};"
            for (resolved in hcj) {
                if (resolved.plugin.runAt == PluginRunAt.DOCUMENT_START && resolved.css.isNotBlank()) {
                    parts += PluginInjection.wrapCss(resolved.plugin.id, resolved.css)
                }
            }
            for (resolved in hcj) {
                if (resolved.mainJs.isBlank()) continue
                parts += PluginInjection.wrapPluginCode(
                    plugin = resolved.plugin,
                    code = resolved.mainJs,
                    token = pluginTokens[resolved.plugin.id].orEmpty(),
                    appLang = appLang
                )
            }
        }
        if (userscripts.isNotEmpty()) {
            parts += USERSCRIPT_STUB
            for (resolved in userscripts) {
                if (resolved.mainJs.isBlank()) continue
                parts += "(function(){try{\n${resolved.mainJs}\n}catch(e){}})();"
            }
        }
        return parts.joinToString("\n")
    }

    /**
     * @return true when [message] is a plugin channel and the prompt must not
     * be shown. Config writes land in [store]. Badge and panel calls update
     * the host surface through [session].
     */
    fun handlePrompt(
        message: String?,
        value: String?,
        store: PluginConfigStore,
        session: PluginSession?
    ): Boolean {
        val payload = value.orEmpty()
        when (message) {
            CONFIG_PROMPT -> {
                if (!isPromptAuthorized(payload, session, PluginPermission.STORAGE)) return true
                applyConfig(store, payload)
                return true
            }
            BADGE_PROMPT -> {
                if (!isPromptAuthorized(payload, session, PluginPermission.BADGE)) return true
                applyBadge(payload)
                return true
            }
            PANEL_PROMPT -> {
                if (!isPromptAuthorized(payload, session)) return true
                val json = parsePanel(payload) ?: return true
                session?.deliverPanelMessageToPanel(json)
                return true
            }
            ACTION_PROMPT -> {
                dispatchAction(payload, session)
                return true
            }
            else -> return false
        }
    }

    private fun bridgeStub(configSeedJson: String, pluginTokens: Map<String, String>): String {
        val literal = jsString(configSeedJson)
        val tokensJson = pluginTokens.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "${jsonQuote(key)}:${jsonQuote(value)}"
        }
        val tokensLiteral = jsString(tokensJson)
        return """
            try { delete window.__hcj; delete window.__hcjBridge; } catch (e) {}
            window.__hcjBridge = (function() {
              var seed = {};
              var tokens = {};
              try { seed = JSON.parse($literal) || {}; } catch (e) {}
              try { tokens = JSON.parse($tokensLiteral) || {}; } catch (e) {}
              function send(channel, body) {
                try { prompt(channel, JSON.stringify(body)); } catch (e) {}
              }
              function auth(pid, token) { return !!pid && !!token && tokens[pid] === token; }
              function noop() {}
              return {
                configGet: function(pid, token, k) {
                  if (!auth(pid, token) || !Object.prototype.hasOwnProperty.call(seed, pid)) return null;
                  var bag = seed[pid];
                  if (!bag || !Object.prototype.hasOwnProperty.call(bag, k)) return null;
                  return JSON.stringify(bag[k]);
                },
                configSet: function(pid, token, k, json) {
                  if (!auth(pid, token) || !Object.prototype.hasOwnProperty.call(seed, pid)) return;
                  try { seed[pid][k] = JSON.parse(json); } catch (e) { seed[pid][k] = json; }
                  send('${CONFIG_PROMPT}', { pid: pid, token: token, k: k, v: json });
                },
                configRemove: function(pid, token, k) {
                  if (!auth(pid, token) || !Object.prototype.hasOwnProperty.call(seed, pid)) return;
                  delete seed[pid][k];
                  send('${CONFIG_PROMPT}', { pid: pid, token: token, k: k, v: null });
                },
                configAll: function(pid, token) {
                  return auth(pid, token) && Object.prototype.hasOwnProperty.call(seed, pid) ? JSON.stringify(seed[pid]) : '{}';
                },
                action: function(pid, token, method, args) {
                  if (!auth(pid, token)) return;
                  send('${ACTION_PROMPT}', { pid: pid, token: token, method: method, args: args || [] });
                },
                fetch: function(pid, token, url, opts, cbId) {
                  this.action(pid, token, 'fetch', [String(url), opts || '{}', cbId]);
                },
                notify: function(pid, token, title, body) {
                  this.action(pid, token, 'notify', [String(title), String(body || '')]);
                },
                badge: function(pid, token, text, color) {
                  if (!auth(pid, token)) return;
                  send('${BADGE_PROMPT}', { pid: pid, token: token, text: text == null ? '' : String(text), color: color || '' });
                },
                panelOpen: function(pid, token) { this.action(pid, token, 'panelOpen', []); },
                panelClose: function(pid, token) { this.action(pid, token, 'panelClose', []); },
                panelSend: function(pid, token, json) {
                  if (!auth(pid, token)) return;
                  send('${PANEL_PROMPT}', { pid: pid, token: token, json: json });
                },
                share: function(pid, token, title, text, url) {
                  this.action(pid, token, 'share', [String(title || ''), String(text || ''), String(url || '')]);
                },
                openExternal: function(pid, token, url) {
                  this.action(pid, token, 'openExternal', [String(url)]);
                },
                clearData: function(pid, token) { this.action(pid, token, 'clearData', []); },
                exitApp: function(pid, token) { this.action(pid, token, 'exitApp', []); },
                log: function(pid, token, m) { try { console.log('[hcj]', m); } catch (e) {} }
              };
            })();
        """.trimIndent()
    }

    private fun dispatchAction(payload: String, session: PluginSession?) {
        val obj = try { JSONObject(payload) } catch (_: Exception) { return }
        val pid = obj.optString("pid")
        val token = obj.optString("token")
        val method = obj.optString("method")
        val args = obj.optJSONArray("args") ?: org.json.JSONArray()
        val bridge = session?.bridge ?: return
        if (pid.isBlank() || token.isBlank() || !bridge.isAuthorized(pid, token)) return
        fun arg(index: Int): String = args.optString(index, "")
        when (method) {
            "fetch" -> if (args.length() >= 3) bridge.fetch(pid, token, arg(0), arg(1), arg(2))
            "notify" -> if (args.length() >= 2) bridge.notify(pid, token, arg(0), arg(1))
            "panelOpen" -> bridge.panelOpen(pid, token)
            "panelClose" -> bridge.panelClose(pid, token)
            "share" -> if (args.length() >= 3) bridge.share(pid, token, arg(0), arg(1), arg(2))
            "openExternal" -> if (args.length() >= 1) bridge.openExternal(pid, token, arg(0))
            "clearData" -> bridge.clearData(pid, token)
            "exitApp" -> bridge.exitApp(pid, token)
        }
    }

    private fun isPromptAuthorized(
        payload: String,
        session: PluginSession?,
        permission: PluginPermission? = null
    ): Boolean {
        val obj = try { JSONObject(payload) } catch (_: Exception) { return false }
        val pid = obj.optString("pid")
        val token = obj.optString("token")
        return pid.isNotBlank() && token.isNotBlank() &&
            session?.bridge?.isAuthorized(pid, token, permission) == true
    }

    private fun applyConfig(store: PluginConfigStore, payload: String) {
        val obj = try {
            JSONObject(payload)
        } catch (_: Exception) {
            return
        }
        val pid = obj.optString("pid")
        val key = obj.optString("k")
        if (pid.isBlank() || key.isBlank()) return
        if (obj.isNull("v")) {
            store.remove(pid, key)
        } else {
            store.set(pid, key, obj.optString("v"))
        }
    }

    private fun applyBadge(payload: String) {
        val obj = try {
            JSONObject(payload)
        } catch (_: Exception) {
            return
        }
        val pid = obj.optString("pid")
        if (pid.isBlank()) return
        PluginHostState.updateBadge(pid, obj.optString("text"), obj.optString("color"))
    }

    /** Serialize a string as a JSON string without Android's org.json runtime. */
    private fun jsonQuote(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) {
                    append("\\u")
                    append(ch.code.toString(16).padStart(4, '0'))
                } else append(ch)
            }
        }
        append('"')
    }

    private fun jsString(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(ch)
            }
        }
        append('"')
    }

    private fun parsePanel(payload: String): String? {
        val obj = try {
            JSONObject(payload)
        } catch (_: Exception) {
            return null
        }
        return if (obj.isNull("json")) null else obj.optString("json")
    }
}

private const val CLEANUP_JS = """
(function(){
  try { delete window.__wta_gecko_userscript__; } catch (e) {}
  var dark = document.getElementById('wta-dark-mode');
  if (dark) dark.remove();
  var nodes = document.querySelectorAll('style[id^="hcj-css-"],style[data-wta-gecko="userscript"]');
  for (var i = 0; i < nodes.length; i++) nodes[i].remove();
})();
"""

private const val USERSCRIPT_STUB = """
(function(){
  if (window.__wta_gecko_userscript__) return;
  window.__wta_gecko_userscript__ = true;
  function GM_addStyle(css) {
    var s = document.createElement('style');
    s.setAttribute('data-wta-gecko', 'userscript');
    s.textContent = css == null ? '' : String(css);
    (document.head || document.documentElement).appendChild(s);
    return s;
  }
  function GM_getValue(k, d) { return d; }
  function GM_setValue() {}
  function GM_xmlhttpRequest() { return { abort: function() {} }; }
  window.GM_addStyle = GM_addStyle;
  window.GM_getValue = GM_getValue;
  window.GM_setValue = GM_setValue;
  window.GM_xmlhttpRequest = GM_xmlhttpRequest;
  window.GM = { addStyle: GM_addStyle, getValue: function(k, d) { return Promise.resolve(d); }, setValue: function() { return Promise.resolve(); } };
  window.unsafeWindow = window;
})();
"""
