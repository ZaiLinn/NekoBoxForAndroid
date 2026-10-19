package io.nekohasekai.sagernet.ktx

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.Serializable
import io.nekohasekai.sagernet.fmt.http.parseHttp
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria2
import io.nekohasekai.sagernet.fmt.naive.parseNaive
import io.nekohasekai.sagernet.fmt.parseUniversal
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.socks.parseSOCKS
import io.nekohasekai.sagernet.fmt.trojan.parseTrojan
import io.nekohasekai.sagernet.fmt.tuic.parseTuic
import io.nekohasekai.sagernet.fmt.trojan_go.parseTrojanGo
import io.nekohasekai.sagernet.fmt.v2ray.parseV2Ray
import moe.matsuri.nb4a.proxy.anytls.parseAnytls
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// JSON & Base64

fun JSONObject.toStringPretty(): String {
    return gson.toJson(JsonParser.parseString(this.toString()))
}

inline fun <reified T : Any> JSONArray.filterIsInstance(): List<T> {
    val list = mutableListOf<T>()
    for (i in 0 until this.length()) {
        if (this[i] is T) list.add(this[i] as T)
    }
    return list
}

inline fun JSONArray.forEach(action: (Int, Any) -> Unit) {
    for (i in 0 until this.length()) {
        action(i, this[i])
    }
}

inline fun JSONObject.forEach(action: (String, Any) -> Unit) {
    for (k in this.keys()) {
        action(k, this.get(k))
    }
}

fun isJsonObjectValid(j: Any): Boolean {
    if (j is JSONObject) return true
    if (j is JSONArray) return true
    try {
        JSONObject(j as String)
    } catch (ex: JSONException) {
        try {
            JSONArray(j)
        } catch (ex1: JSONException) {
            return false
        }
    }
    return true
}

// wtf hutool
fun JSONObject.getStr(name: String): String? {
    val obj = this.opt(name) ?: return null
    if (obj is String) {
        if (obj.isBlank()) {
            return null
        }
        return obj
    } else {
        return null
    }
}

fun JSONObject.getBool(name: String): Boolean? {
    return try {
        getBoolean(name)
    } catch (ignored: Exception) {
        null
    }
}


// 重名了喵
fun JSONObject.getIntNya(name: String): Int? {
    return try {
        getInt(name)
    } catch (ignored: Exception) {
        null
    }
}


fun String.decodeBase64UrlSafe(): String {
    return String(Util.b64Decode(this))
}

// Sub

class SubscriptionFoundException(val link: String) : RuntimeException()

/** Why a line of imported text was not imported. */
enum class ImportSkipReason {
    /** A link of a supported proxy scheme that failed to parse. */
    INVALID_PROXY_LINK,

    /** A subscription link next to proxies; a subscription is only added from a link alone. */
    SUBSCRIPTION_LINK,

    /** An http(s) link that is not an http proxy, such as a web page. */
    WEB_LINK,

    /** Text that is not a supported link. */
    UNSUPPORTED,
}

class ProxyParseResult<T>(
    val proxies: List<T>,
    /** Skipped non-blank lines by reason. */
    val skipped: Map<ImportSkipReason, Int> = emptyMap(),
) {
    val skippedCount get() = skipped.values.sum()
}

sealed class LinkParseResult<out T> {
    class Proxy<T>(val bean: T) : LinkParseResult<T>()
    class Skipped(val reason: ImportSkipReason) : LinkParseResult<Nothing>()
}

class ProxyLinks<T>(
    val result: ProxyParseResult<T>,
    /**
     * The link to add as a subscription, set only when no proxy was found and the text is a
     * single http(s) link that is not a proxy, or holds exactly one explicit subscription link.
     */
    val subscriptionLink: String?,
)

private fun isExplicitSubscriptionLink(link: String) =
    link.startsWith("clash://install-config?") || link.startsWith("sn://subscription?")

/**
 * Parses each link of [text] with [parseLink], once split by spaces and once by lines, and keeps
 * the split that finds more proxies. A link that fails to parse is skipped and does not stop
 * the links after it.
 */
fun <T> collectProxyLinks(text: String, parseLink: (String) -> LinkParseResult<T>): ProxyLinks<T> {
    val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    fun parseLines(splitBySpace: Boolean): ProxyParseResult<T> {
        val proxies = ArrayList<T>()
        val skipped = LinkedHashMap<ImportSkipReason, Int>()
        for (line in lines) {
            val links = if (splitBySpace) line.split(' ').filter { it.isNotEmpty() } else listOf(line)
            val results = links.map(parseLink)
            val found = results.filterIsInstance<LinkParseResult.Proxy<T>>()
            if (found.isNotEmpty()) {
                found.mapTo(proxies) { it.bean }
                continue
            }
            // the most specific reason, the enum is declared in that order
            val reason = results.filterIsInstance<LinkParseResult.Skipped>().minOf { it.reason }
            skipped[reason] = (skipped[reason] ?: 0) + 1
        }
        return ProxyParseResult(proxies, skipped)
    }

    val bySpace = parseLines(true)
    val byLine = parseLines(false)
    val result = if (bySpace.proxies.size > byLine.proxies.size) bySpace else byLine

    var subscriptionLink: String? = null
    if (result.proxies.isEmpty()) {
        val links = lines.flatMap { line -> line.split(' ').filter { it.isNotEmpty() } }
        val explicit = links.filter(::isExplicitSubscriptionLink)
        subscriptionLink = when {
            explicit.size == 1 -> explicit[0]
            links.size == 1 && (parseLink(links[0]) as? LinkParseResult.Skipped)?.reason == ImportSkipReason.WEB_LINK -> links[0]
            else -> null
        }
    }
    return ProxyLinks(result, subscriptionLink)
}

/**
 * The scheme of [link], safe to log. The rest of a proxy link may hold user names, passwords,
 * UUIDs or keys, so never log the link itself or a parser's message, which often repeats it.
 */
fun linkSchemeForLog(link: String): String {
    val scheme = link.substringBefore("://", "")
    return if (scheme.isNotEmpty() && scheme.length <= 16 && scheme.all { it.isLetterOrDigit() || it in "+-." }) {
        scheme
    } else {
        "unknown"
    }
}

fun linkParseFailureLog(link: String, e: Throwable) =
    "Failed to parse ${linkSchemeForLog(link)} link: ${e.javaClass.simpleName}"

private fun parseProxyLink(link: String): LinkParseResult<AbstractBean> {
    if (isExplicitSubscriptionLink(link)) {
        return LinkParseResult.Skipped(ImportSkipReason.SUBSCRIPTION_LINK)
    }

    val isHttp = link.matches("(http|https)://.*".toRegex())
    val parser: ((String) -> AbstractBean)? = when {
        link.startsWith("sn://") -> ::parseUniversal
        link.startsWith("socks://") || link.startsWith("socks4://") ||
                link.startsWith("socks4a://") || link.startsWith("socks5://") -> ::parseSOCKS

        isHttp -> ::parseHttp
        link.startsWith("vmess://") || link.startsWith("vless://") -> ::parseV2Ray
        link.startsWith("trojan://") -> ::parseTrojan
        link.startsWith("trojan-go://") -> ::parseTrojanGo
        link.startsWith("ss://") -> ::parseShadowsocks
        link.startsWith("naive+") -> ::parseNaive
        link.startsWith("hysteria://") -> ::parseHysteria1
        link.startsWith("hysteria2://") || link.startsWith("hy2://") -> ::parseHysteria2
        link.startsWith("tuic://") -> ::parseTuic
        link.startsWith("anytls://") -> ::parseAnytls
        else -> null
    }
    if (parser == null) return LinkParseResult.Skipped(ImportSkipReason.UNSUPPORTED)

    return try {
        LinkParseResult.Proxy(parser(link))
    } catch (e: Throwable) { // as runCatching did before, a parser may throw an Error
        Logs.w(linkParseFailureLog(link, e))
        if (isHttp) {
            LinkParseResult.Skipped(ImportSkipReason.WEB_LINK)
        } else {
            LinkParseResult.Skipped(ImportSkipReason.INVALID_PROXY_LINK)
        }
    }
}

/** Wraps an http(s) link so that it can be imported as a subscription. */
fun toClashSubscriptionLink(link: String): String {
    return HttpUrl.Builder()
        .scheme("https")
        .host("install-config")
        .addQueryParameter("url", link)
        .build()
        .toString()
        .replaceFirst("https://", "clash://")
}

/**
 * Parses the proxy links in [text]. Lines that are not proxies are skipped and counted.
 *
 * @throws SubscriptionFoundException if no proxy was found and the text is a subscription link.
 */
fun parseProxiesResult(text: String): ProxyParseResult<AbstractBean> {
    val links = collectProxyLinks(text, ::parseProxyLink)
    links.subscriptionLink?.let {
        throw SubscriptionFoundException(
            if (isExplicitSubscriptionLink(it)) it else toClashSubscriptionLink(it)
        )
    }
    links.result.proxies.forEach { it.initializeDefaultValues() }
    return links.result
}

suspend fun parseProxies(text: String): List<AbstractBean> {
    return parseProxiesResult(text).proxies
}

fun <T : Serializable> T.applyDefaultValues(): T {
    initializeDefaultValues()
    return this
}