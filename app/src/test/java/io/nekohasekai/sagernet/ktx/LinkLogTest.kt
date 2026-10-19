package io.nekohasekai.sagernet.ktx

import io.nekohasekai.sagernet.fmt.http.parseHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Log lines about proxy links must not contain any part of the link but its scheme. */
class LinkLogTest {

    private val secrets = listOf(
        "user", "s3cr3t-pass", "7a70e94b-1c76-4bee-9c19-c41dd9675952", "dQ15_1AUOb5WoNC0Cn",
        "1.2.3.4", "example.com",
    )

    private val links = listOf(
        "http://user:s3cr3t-pass@1.2.3.4:8080",
        "vless://7a70e94b-1c76-4bee-9c19-c41dd9675952@example.com:443?security=reality&pbk=dQ15_1AUOb5WoNC0Cn#node",
        "ss://YWVzLTI1Ni1nY206czNjcjN0LXBhc3M@1.2.3.4:8388#user",
        "hy2://s3cr3t-pass@example.com:443",
        "trojan://s3cr3t-pass@1.2.3.4:443",
        "socks5://user:s3cr3t-pass@1.2.3.4:1080",
        "naive+https://user:s3cr3t-pass@example.com",
    )

    private fun assertNoSecret(line: String) {
        for (secret in secrets) {
            assertFalse("\"$secret\" leaked in: $line", line.contains(secret))
        }
    }

    @Test
    fun schemeOnly() {
        assertEquals("http", linkSchemeForLog(links[0]))
        assertEquals("vless", linkSchemeForLog(links[1]))
        assertEquals("naive+https", linkSchemeForLog(links[6]))
        links.forEach { assertNoSecret(linkSchemeForLog(it)) }
    }

    @Test
    fun noSchemeIsUnknown() {
        assertEquals("unknown", linkSchemeForLog("s3cr3t-pass"))
        assertEquals("unknown", linkSchemeForLog("user:s3cr3t-pass@1.2.3.4://x"))
        assertEquals("unknown", linkSchemeForLog(""))
    }

    @Test
    fun failureLogDropsTheParserMessage() {
        // parsers put the whole link into their messages
        val link = "https://user:s3cr3t-pass@example.com/path"
        val e = runCatching { parseHttp(link) }.exceptionOrNull()!!
        val line = linkParseFailureLog(link, e)
        assertEquals("Failed to parse https link: IllegalStateException", line)
        assertNoSecret(line)
        for (each in links) {
            assertNoSecret(linkParseFailureLog(each, IllegalArgumentException("invalid link $each")))
        }
    }
}
