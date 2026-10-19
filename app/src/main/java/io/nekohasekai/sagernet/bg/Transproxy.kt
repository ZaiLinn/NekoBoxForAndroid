package io.nekohasekai.sagernet.bg

import android.os.Process
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.utils.PackageCache
import java.io.File
import java.io.IOException

/**
 * Root transparent proxy: iptables (nat REDIRECT) sends TCP and DNS of the selected apps to the
 * redirect / DNS inbounds of the core.
 *
 * The core runs inside the app without root, so TPROXY (which needs CAP_NET_ADMIN on the listening
 * socket) can't be used: non-DNS UDP is not proxied. QUIC (UDP 443) is rejected so apps fall back
 * to TCP. If the kernel has no IPv6 nat, IPv6 traffic of proxied apps is rejected instead of leaking.
 */
object Transproxy {

    private const val CHAIN_NAT = "NBZ_OUT"
    private const val CHAIN_NAT_PROXY = "NBZ_PROXY"
    private const val CHAIN_FILTER = "NBZ_FILTER"
    private const val CHAIN_FILTER_REJECT = "NBZ_REJECT"

    private const val SU_TIMEOUT_MS = 30_000L

    private val PRIVATE_V4 = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
        "172.16.0.0/12", "192.0.0.0/24", "192.168.0.0/16", "224.0.0.0/4", "240.0.0.0/4",
    )
    private val PRIVATE_V6 = listOf("::1/128", "fc00::/7", "fe80::/10", "ff00::/8")

    // exists while rules may be installed, so leftovers (e.g. after a crash) get removed
    private val flagFile get() = File(app.noBackupFilesDir, "transproxy_active")

    class RootException(message: String) : IOException(message), BaseService.ExpectedException

    fun apply(proxyPort: Int, dnsPort: Int) {
        flagFile.createNewFile()
        val script = buildString {
            appendLine(cleanupScript())
            appendLine("set -e")
            appendLine(natRules("iptables", proxyPort, dnsPort, PRIVATE_V4))
            appendLine(filterRules("iptables", rejectAll = false, PRIVATE_V4))
            // IPv6 nat is missing on some kernels: reject proxied IPv6 instead, apps fall back to IPv4
            appendLine("set +e")
            appendLine("if (set -e; ${natRules("ip6tables", proxyPort, dnsPort, PRIVATE_V6).replace("\n", "; ")}) 2>/dev/null; then")
            appendLine("  ${filterRules("ip6tables", rejectAll = false, PRIVATE_V6).replace("\n", "\n  ")}")
            appendLine("else")
            appendLine("  ${cleanupScript(listOf("ip6tables")).replace("\n", "\n  ")}")
            appendLine("  ${filterRules("ip6tables", rejectAll = true, PRIVATE_V6).replace("\n", "\n  ")}")
            appendLine("fi")
        }
        runSu(script)
        Logs.i("transproxy: iptables rules applied (proxy port $proxyPort, dns port $dnsPort)")
    }

    fun cleanup() {
        if (!flagFile.exists()) return
        try {
            runSu(cleanupScript())
            flagFile.delete()
            Logs.i("transproxy: iptables rules removed")
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    // iptables options shared by every command: wait for the xtables lock when supported
    private fun ipt(cmd: String) = "$cmd \$W"

    private fun cleanupScript(tools: List<String> = listOf("iptables", "ip6tables")) =
        buildString {
            appendLine("iptables -w -L -n >/dev/null 2>&1 && W=-w || W=")
            for (t in tools) {
                appendLine("while ${ipt(t)} -t nat -D OUTPUT -j $CHAIN_NAT 2>/dev/null; do :; done")
                appendLine("while ${ipt(t)} -t filter -D OUTPUT -j $CHAIN_FILTER 2>/dev/null; do :; done")
                for (chain in listOf(CHAIN_NAT, CHAIN_NAT_PROXY)) {
                    appendLine("${ipt(t)} -t nat -F $chain 2>/dev/null")
                }
                for (chain in listOf(CHAIN_NAT, CHAIN_NAT_PROXY)) {
                    appendLine("${ipt(t)} -t nat -X $chain 2>/dev/null")
                }
                for (chain in listOf(CHAIN_FILTER, CHAIN_FILTER_REJECT)) {
                    appendLine("${ipt(t)} -t filter -F $chain 2>/dev/null")
                }
                for (chain in listOf(CHAIN_FILTER, CHAIN_FILTER_REJECT)) {
                    appendLine("${ipt(t)} -t filter -X $chain 2>/dev/null")
                }
            }
        }.trimEnd()

    private fun natRules(t: String, proxyPort: Int, dnsPort: Int, privateCidrs: List<String>) =
        buildString {
            val nat = "${ipt(t)} -t nat"
            appendLine("$nat -N $CHAIN_NAT_PROXY")
            // DNS first, so DNS servers in the LAN (e.g. the router) are handled by the core too
            appendLine("$nat -A $CHAIN_NAT_PROXY -p udp --dport 53 -j REDIRECT --to-ports $dnsPort")
            appendLine("$nat -A $CHAIN_NAT_PROXY -p tcp --dport 53 -j REDIRECT --to-ports $dnsPort")
            for (cidr in privateCidrs) appendLine("$nat -A $CHAIN_NAT_PROXY -d $cidr -j RETURN")
            appendLine("$nat -A $CHAIN_NAT_PROXY -p tcp -j REDIRECT --to-ports $proxyPort")
            appendLine("$nat -N $CHAIN_NAT")
            appendSelection(nat, CHAIN_NAT, CHAIN_NAT_PROXY)
            append("$nat -I OUTPUT -j $CHAIN_NAT")
        }

    private fun filterRules(t: String, rejectAll: Boolean, privateCidrs: List<String>) = buildString {
        val filter = "${ipt(t)} -t filter"
        appendLine("$filter -N $CHAIN_FILTER_REJECT")
        if (rejectAll) {
            for (cidr in privateCidrs) appendLine("$filter -A $CHAIN_FILTER_REJECT -d $cidr -j RETURN")
            appendLine("$filter -A $CHAIN_FILTER_REJECT -j REJECT")
        } else {
            // QUIC can't be proxied, make apps fall back to TCP right away
            appendLine("$filter -A $CHAIN_FILTER_REJECT -p udp --dport 443 -j REJECT")
        }
        appendLine("$filter -N $CHAIN_FILTER")
        appendSelection(filter, CHAIN_FILTER, CHAIN_FILTER_REJECT)
        append("$filter -I OUTPUT -j $CHAIN_FILTER")
    }

    /** Jump to [target] for the traffic of the apps that should be proxied. */
    private fun StringBuilder.appendSelection(table: String, chain: String, target: String) {
        // never touch our own traffic (core outbound connections), or it would loop
        appendLine("$table -A $chain -m owner --uid-owner ${Process.myUid()} -j RETURN")
        if (!DataStore.proxyApps) {
            appendLine("$table -A $chain -j $target")
            return
        }
        PackageCache.awaitLoadSync()
        val uids = DataStore.individual.split('\n').filter { it.isNotBlank() }
            .mapNotNull { PackageCache[it] }.toSortedSet()
        if (DataStore.bypass) {
            for (uid in uids) appendLine("$table -A $chain -m owner --uid-owner $uid -j RETURN")
            appendLine("$table -A $chain -j $target")
        } else {
            for (uid in uids) appendLine("$table -A $chain -m owner --uid-owner $uid -j $target")
        }
    }

    private fun runSu(script: String) {
        val process = try {
            ProcessBuilder("su").redirectErrorStream(true).start()
        } catch (e: IOException) {
            throw RootException(app.getString(R.string.transproxy_root_required))
        }
        var output = ""
        val reader = Thread {
            output = try {
                process.inputStream.bufferedReader().readText()
            } catch (_: IOException) {
                ""
            }
        }
        reader.start()
        try {
            process.outputStream.bufferedWriter().use {
                it.write(script)
                it.write("\nexit \$?\n")
            }
        } catch (e: IOException) {
            // su exited before reading the script, e.g. root was denied
            Logs.w(e)
        }
        reader.join(SU_TIMEOUT_MS)
        if (reader.isAlive) {
            process.destroy()
            throw RootException(app.getString(R.string.transproxy_root_required))
        }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            Logs.w("transproxy: su exited with $exitCode: $output")
            throw RootException(
                app.getString(R.string.transproxy_failed, output.trim().lines().lastOrNull() ?: "$exitCode")
            )
        }
    }
}
