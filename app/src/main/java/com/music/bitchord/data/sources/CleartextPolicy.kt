package com.music.bitchord.data.sources

import android.security.NetworkSecurityPolicy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Whether Android will let this app send plain HTTP to [url]'s host.
 *
 * Asked of the platform rather than inferred from the build type: cleartext
 * is a *flavor* setting here (the dev flavor's manifest turns
 * `usesCleartextTraffic` on, prod leaves it off), so `BuildConfig.DEBUG`
 * refused http:// on devRelease, where it works, and let it through on
 * prodDebug, where it fails. Per host, so a network-security config that
 * allows a LAN name is honoured too.
 */
internal fun cleartextPermitted(url: String): Boolean {
    val host = url.trim().toHttpUrlOrNull()?.host ?: return true
    return runCatching { NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host) }
        .getOrDefault(true)
}
