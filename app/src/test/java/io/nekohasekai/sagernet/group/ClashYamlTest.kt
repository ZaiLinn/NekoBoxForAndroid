package io.nekohasekai.sagernet.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClashYamlTest {

    @Test
    fun parsesProxies() {
        val yaml = newClashYaml().loadAs(
            """
            proxies:
              - name: a
                type: ss
                server: 1.2.3.4
                port: 8388
            """.trimIndent(), Map::class.java
        )
        val proxies = yaml["proxies"] as List<*>
        assertEquals("a", (proxies[0] as Map<*, *>)["name"])
        assertEquals(8388, (proxies[0] as Map<*, *>)["port"])
    }

    @Test
    fun rejectsGlobalTags() {
        // CVE-2022-1471: SnakeYAML 1.x built any class named by a global tag
        val result = runCatching {
            newClashYaml().loadAs(
                "proxies: !!java.io.File [\"/data/local/tmp/x\"]", Map::class.java
            )
        }
        assertTrue("global tag accepted", result.isFailure)
    }

    @Test
    fun allowsManyAliases() {
        // more than the default limit of 50 (upstream #1042)
        val text = buildString {
            appendLine("common: &c {type: ss, server: 1.2.3.4, port: 1}")
            appendLine("proxies:")
            repeat(200) { appendLine("  - *c") }
        }
        val proxies = newClashYaml().loadAs(text, Map::class.java)["proxies"] as List<*>
        assertEquals(200, proxies.size)
    }

    @Test
    fun stillBoundsAliases() {
        // the alias limit is what stops alias bombs: more than 1000 is refused
        val text = buildString {
            appendLine("common: &c [x]")
            appendLine("proxies:")
            repeat(1001) { appendLine("  - *c") }
        }
        assertTrue(runCatching { newClashYaml().loadAs(text, Map::class.java) }.isFailure)
    }
}
