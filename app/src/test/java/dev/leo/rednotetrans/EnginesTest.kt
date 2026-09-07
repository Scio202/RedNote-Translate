package dev.leo.rednotetrans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Both cloud engines return shapes nobody guarantees: Google's is an undocumented nested
 * array, DeepSeek's is whatever the model felt like emitting. Pin the parsing of each,
 * including the ways they fail.
 */
class EnginesTest {

    // --- Google web endpoint ---------------------------------------------------

    @Test
    fun `joins google sentence chunks`() {
        val body = """[[["Your guts are so fat","x",null,null,3],""" +
            """[" Really.","y",null,null,3]],null,"zh-CN"]"""
        assertEquals("Your guts are so fat Really.", GoogleWebEngine.parse(body))
    }

    @Test
    fun `treats a bot-block page as failure, not success`() {
        // The block page arrives with HTTP 200, so only the body distinguishes it.
        assertNull(GoogleWebEngine.parse("<html><head><title>Sorry...</title></head></html>"))
    }

    @Test
    fun `returns null on an empty or malformed google payload`() {
        assertNull(GoogleWebEngine.parse("""[null,null,"zh-CN"]"""))
        assertNull(GoogleWebEngine.parse("not json at all"))
    }

    // --- DeepSeek --------------------------------------------------------------

    private fun reply(content: String) =
        """{"choices":[{"message":{"role":"assistant","content":${quote(content)}}}]}"""

    private fun quote(s: String) =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\t", "\\t")
            .replace("\n", "\\n") + "\""

    @Test
    fun `pairs deepseek lines back to their source by index`() {
        val texts = listOf("你好", "早安", "晚安")
        val out = DeepSeekEngine.parse(reply("0\tHello\n1\tGood morning\n2\tGood night"), texts)
        assertEquals(mapOf("你好" to "Hello", "早安" to "Good morning", "晚安" to "Good night"), out)
    }

    @Test
    fun `a dropped line does not shift every later translation onto the wrong text`() {
        val texts = listOf("你好", "早安", "晚安")
        // The model skipped index 1 entirely - index 2 must still map to 晚安.
        val out = DeepSeekEngine.parse(reply("0\tHello\n2\tGood night"), texts)
        assertEquals(mapOf("你好" to "Hello", "晚安" to "Good night"), out)
    }

    @Test
    fun `ignores commentary lines the model adds anyway`() {
        val texts = listOf("你好")
        val out = DeepSeekEngine.parse(reply("Sure, here you go:\n0\tHello"), texts)
        assertEquals(mapOf("你好" to "Hello"), out)
    }

    @Test
    fun `returns null when nothing usable came back`() {
        assertNull(DeepSeekEngine.parse(reply("I cannot help with that."), listOf("你好")))
        assertNull(DeepSeekEngine.parse("""{"error":{"message":"bad key"}}""", listOf("你好")))
    }
}
