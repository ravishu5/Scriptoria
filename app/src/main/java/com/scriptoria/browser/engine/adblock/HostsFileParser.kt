package com.scriptoria.browser.engine.adblock

import jp.hazuki.yuzubrowser.adblock.core.ContentRequest
import jp.hazuki.yuzubrowser.adblock.filter.unified.HostFilter
import jp.hazuki.yuzubrowser.adblock.filter.unified.NO_PARTY_PREFERENCE
import jp.hazuki.yuzubrowser.adblock.filter.unified.UnifiedFilter
import java.io.BufferedReader

/**
 * Turns a hosts file into engine filters.
 *
 * Hosts files name exact FQDNs, so each line becomes an exact-host match rather than a domain
 * rule: the lists already spell out ad.example.com alongside example.com where they mean both,
 * and widening them to cover subdomains would block hosts their authors deliberately left out.
 */
internal object HostsFileParser {

    /** Entries every hosts file carries for the loopback interface, which must not be blocked. */
    private val SKIPPED = setOf(
        "localhost",
        "localhost.localdomain",
        "local",
        "broadcasthost",
        "ip6-localhost",
        "ip6-loopback",
        "ip6-localnet",
        "ip6-mcastprefix",
        "ip6-allnodes",
        "ip6-allrouters",
        "ip6-allhosts",
        "0.0.0.0",
    )

    fun parse(reader: BufferedReader): List<UnifiedFilter> {
        val filters = mutableListOf<UnifiedFilter>()
        val seen = HashSet<String>()

        while (true) {
            val raw = reader.readLine() ?: break
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue

            val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
            // A hosts line is "<address> <host>...". Anything else is not one.
            if (parts.size < 2 || !parts[0].isRedirectAddress()) continue

            for (i in 1 until parts.size) {
                val host = parts[i].lowercase()
                if (host in SKIPPED || !host.contains('.')) continue
                if (!seen.add(host)) continue
                filters += HostFilter(
                    filter = host,
                    contentType = ContentRequest.TYPE_ALL,
                    domains = null,
                    thirdParty = NO_PARTY_PREFERENCE,
                )
            }
        }
        return filters
    }

    /** Only lines pointing a name at a black-hole address are blocking entries. */
    private fun String.isRedirectAddress(): Boolean =
        this == "0.0.0.0" || this == "127.0.0.1" || this == "::1" || this == "::"
}
