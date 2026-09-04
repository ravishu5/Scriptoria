package com.scriptoria.browser.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import jp.hazuki.yuzubrowser.adblock.repository.abp.AbpEntity
import jp.hazuki.yuzubrowser.adblock.repository.abp.abpEntityFromString

/**
 * Blocklist metadata and the user's allowlist. There are only a handful of lists and hosts here,
 * so this deliberately stays out of Room: AppDatabase is on fallbackToDestructiveMigration, and a
 * schema bump for a few rows of metadata would wipe the user's installed scripts.
 */
class AdblockPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** Hosts the user has explicitly turned blocking off for. */
    var allowlist: Set<String>
        get() = prefs.getStringSet(KEY_ALLOWLIST, emptySet()) ?: emptySet()
        set(value) = prefs.edit { putStringSet(KEY_ALLOWLIST, value) }

    fun isAllowlisted(host: String?): Boolean = matches(host, allowlist)

    fun setAllowlisted(host: String, allowed: Boolean) {
        allowlist = allowlist.toMutableSet().apply { if (allowed) add(host) else remove(host) }
    }

    fun getLists(): List<AbpEntity> {
        val stored = prefs.getStringSet(KEY_LISTS, null)?.mapNotNull { abpEntityFromString(it) }
        if (stored.isNullOrEmpty()) return DEFAULT_LISTS

        // A default list added in a later version is unknown to an existing install, whose stored
        // set is authoritative; merging by id lets one appear without disturbing the user's own
        // enabled/disabled choices for the lists they already have.
        val known = stored.map { it.entityId }.toSet()
        return (stored + DEFAULT_LISTS.filterNot { it.entityId in known }).sortedBy { it.entityId }
    }

    fun saveLists(lists: List<AbpEntity>) {
        prefs.edit { putStringSet(KEY_LISTS, lists.map { it.toString() }.toSet()) }
    }

    fun updateList(entity: AbpEntity) {
        val lists = getLists().toMutableList()
        val index = lists.indexOfFirst { it.entityId == entity.entityId }
        if (index >= 0) lists[index] = entity else lists.add(entity)
        saveLists(lists)
    }

    companion object {
        /**
         * A rule for example.com also covers cdn.example.com, which is what a user toggling off
         * "this site" expects; without it the page loads but its own subdomains stay blocked.
         */
        fun matches(host: String?, allowlist: Set<String>): Boolean {
            if (host == null || allowlist.isEmpty()) return false
            var candidate: String = host
            while (true) {
                if (candidate in allowlist) return true
                val dot = candidate.indexOf('.')
                if (dot < 0) return false
                candidate = candidate.substring(dot + 1)
            }
        }

        private const val PREFS_NAME = "scriptoria_adblock_prefs"
        private const val KEY_ENABLED = "adblock_enabled"
        private const val KEY_ALLOWLIST = "adblock_allowlist"
        private const val KEY_LISTS = "adblock_lists"

        /**
         * EasyList and EasyPrivacy are the ABP-syntax lists that carry the cosmetic rules, the
         * StevenBlack hosts file is the domain-only fallback, the AdGuard list supplies the
         * URL-parameter rules and uBlock's own list the scriptlets. All are on by default and each
         * can be turned off in settings.
         */
        val DEFAULT_LISTS: List<AbpEntity> = listOf(
            AbpEntity(
                entityId = 1,
                title = "EasyList",
                url = "https://easylist.to/easylist/easylist.txt",
                homePage = "https://easylist.to",
            ),
            AbpEntity(
                entityId = 2,
                title = "EasyPrivacy",
                url = "https://easylist.to/easylist/easyprivacy.txt",
                homePage = "https://easylist.to",
            ),
            AbpEntity(
                entityId = 3,
                title = "StevenBlack hosts",
                url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                homePage = "https://github.com/StevenBlack/hosts",
            ),
            // EasyList and EasyPrivacy between them carry only a handful of $removeparam rules;
            // this list is almost entirely made of them, and is what makes stripping tracking
            // parameters from a URL do anything noticeable.
            AbpEntity(
                entityId = 4,
                title = "AdGuard URL Tracking",
                // Served from the source repository rather than filters.adtidy.org, which rejects
                // non-browser clients with a 403 and was intermittently unreachable.
                url = "https://raw.githubusercontent.com/AdguardTeam/AdguardFilters/master/" +
                    "TrackParamFilter/sections/general_url.txt",
                homePage = "https://github.com/AdguardTeam/AdguardFilters",
            ),
            // Where the "+js(...)" scriptlet rules live — EasyList carries none and EasyPrivacy
            // only a couple of dozen, so without this the scriptlet engine has nothing to run.
            AbpEntity(
                entityId = 5,
                title = "uBlock filters",
                url = "https://ublockorigin.github.io/uAssets/filters/filters.txt",
                homePage = "https://github.com/uBlockOrigin/uAssets",
            ),
        )
    }
}
