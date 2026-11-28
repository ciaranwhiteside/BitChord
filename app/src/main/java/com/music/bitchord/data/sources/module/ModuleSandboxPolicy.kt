package com.music.bitchord.data.sources.module

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Network and resource policy for untrusted module JavaScript.
 *
 * A module is remote code. Its `fetch` bridge must not become an SSRF pivot
 * into the phone's LAN or cloud metadata endpoints, and a remote module must
 * not be able to use plain HTTP. A module served from a local/LAN index keeps
 * local-network access, because that is the only way a self-hosted module can
 * reach its own backend.
 *
 * Two layers, because a host *name* says nothing about where it points:
 *  - [allowsDestination] judges the URL as written — scheme, and hosts that
 *    are IP literals or `localhost`.
 *  - [SandboxDns] judges every address a name resolves to, at connect time.
 *    OkHttp connects only to the addresses a [Dns] returns, so a name that
 *    resolves (or re-resolves) to a private address, a decimal IP like
 *    `2130706433`, or an IPv4-mapped IPv6 literal cannot slip past.
 */
internal object ModuleSandboxPolicy {

    const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024
    const val MAX_MODULE_BYTES = 2L * 1024 * 1024
    const val MAX_REDIRECTS = 3
    const val MEMORY_LIMIT_BYTES = 64L * 1024 * 1024
    const val EVALUATION_TIMEOUT_MS = 60_000L

    /**
     * [host] as an address when it is an IP literal, else null. Never touches
     * DNS: only dotted-quad IPv4 and colon-bearing IPv6 forms are parsed, so a
     * domain like `fdroid.org` or `fcc.gov` is a name, not an fc00::/7 address.
     */
    fun ipLiteral(host: String): InetAddress? {
        val h = host.removePrefix("[").removeSuffix("]")
        val looksLiteral = ':' in h || IPV4_LITERAL.matches(h)
        if (!looksLiteral) return null
        return runCatching { InetAddress.getByName(h) }.getOrNull()
    }

    /** Loopback, private (RFC 1918 / ULA), CGNAT, unspecified or link-local. */
    fun isPrivateAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress) return true
        if (address.isSiteLocalAddress) return true
        val bytes = address.address
        return when (address) {
            is Inet4Address -> {
                val first = bytes[0].toInt() and 0xff
                val second = bytes[1].toInt() and 0xff
                // 100.64.0.0/10 carrier-grade NAT, and 0.0.0.0/8.
                (first == 100 && second in 64..127) || first == 0
            }
            // fc00::/7 unique-local (Java's isSiteLocal only covers fec0::/10).
            is Inet6Address -> (bytes[0].toInt() and 0xfe) == 0xfc
            else -> false
        }
    }

    /** 169.254.0.0/16 and fe80::/10 — cloud metadata lives here, and it is never reachable. */
    fun isLinkLocalAddress(address: InetAddress): Boolean = address.isLinkLocalAddress

    fun isLocalOrPrivateHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("[").removeSuffix("]")
        if (h == "localhost" || h.endsWith(".localhost")) return true
        return ipLiteral(h)?.let(::isPrivateAddress) ?: false
    }

    fun isLinkLocal(host: String): Boolean = ipLiteral(host)?.let(::isLinkLocalAddress) ?: false

    /**
     * Whether a module may fetch [targetUrl].
     *
     * Public HTTPS destinations are always allowed. A private/loopback target
     * is allowed only when the module itself was served from the same host or
     * from another local/private host. Link-local metadata is never allowed.
     */
    fun allowsDestination(targetUrl: String, fetchBase: String): Boolean {
        val target = targetUrl.toHttpUrlOrNull() ?: return false
        if (target.scheme != "http" && target.scheme != "https") return false
        if (target.host.isBlank()) return false
        if (isLinkLocal(target.host)) return false
        if (target.scheme == "http" && !isLocalOrPrivateHost(target.host)) return false
        if (!isLocalOrPrivateHost(target.host)) return true
        val base = fetchBase.toHttpUrlOrNull() ?: return false
        return target.host == base.host || isLocalOrPrivateHost(base.host)
    }

    /** Cross-host redirects keep only the generic User-Agent. */
    fun headersForRedirect(
        fromUrl: String,
        toUrl: String,
        headers: Map<String, String>,
    ): Map<String, String> {
        val fromHost = fromUrl.toHttpUrlOrNull()?.host
        val toHost = toUrl.toHttpUrlOrNull()?.host
        if (fromHost != null && toHost != null && fromHost.equals(toHost, ignoreCase = true)) {
            return headers
        }
        return headers.filterKeys { it.equals("User-Agent", ignoreCase = true) }
    }

    /**
     * Whether a module served from [fetchBase] may reach private addresses:
     * only when that base is written as a private literal or `localhost`.
     * See [SandboxDns] for why this is never decided by DNS.
     */
    fun baseAllowsPrivate(fetchBase: String): Boolean =
        fetchBase.toHttpUrlOrNull()?.host?.let(::isLocalOrPrivateHost) == true

    /** Module code and indexes must be HTTPS unless self-hosted locally. */
    fun allowsModuleDownload(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.scheme == "https" || isLocalOrPrivateHost(parsed.host)
    }

    private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")
}

/**
 * The resolver a module's requests go through: the address-level half of
 * [ModuleSandboxPolicy].
 *
 * Link-local addresses are refused outright. Private and loopback addresses
 * are allowed only for a module whose base is local *as written* — a private
 * IP literal or `localhost` ([allowPrivate]). That is deliberately not decided
 * by DNS: a module served from a public name controls that name's answers, and
 * could make it (or itself) re-resolve to the LAN between fetches. Any private
 * address among a name's answers counts — one public and one private answer is
 * how rebinding is done.
 */
internal class SandboxDns(
    private val allowPrivate: Boolean,
    private val system: Dns = Dns.SYSTEM,
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = system.lookup(hostname)
        if (addresses.any(ModuleSandboxPolicy::isLinkLocalAddress)) {
            throw UnknownHostException("Module network destination is not permitted")
        }
        if (allowPrivate || addresses.none(ModuleSandboxPolicy::isPrivateAddress)) return addresses
        throw UnknownHostException("Module network destination is not permitted")
    }
}
