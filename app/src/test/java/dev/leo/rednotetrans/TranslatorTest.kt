package dev.leo.rednotetrans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The feed-card description format is RedNote's, undocumented, and the thing most likely to
 * drift. Pin it here along with the "is there anything to translate" filter.
 */
class TranslatorTest {

    @Test
    fun `strips card type prefix and author suffix, keeping only the title`() {
        // "video | I hatched a little frog~ | from <author> | 5.3K likes"
        assertEquals(
            "我孵化出来的小青蛙~",
            cardTitle(
                "视频  我孵化出来的小青蛙~ " +
                    "来自小甜甜的菜园 5.3K赞"
            ),
        )
    }

    @Test
    fun `handles the note card type too`() {
        // "note | aesthetic notes | from <author> | 15.8K likes"
        assertEquals(
            "审美积累",
            cardTitle("笔记  审美积累 来自不自助 15.8K赞"),
        )
    }

    @Test
    fun `falls back to the whole description when the format does not match`() {
        assertEquals("分享", cardTitle("分享"))
        assertEquals("评论1", cardTitle("评论1"))
    }

    @Test
    fun `strips the decoration the on-device model derails on`() {
        // emoji, an emoticon token, and a hashtag run around one real sentence
        assertEquals(
            "真的好吃",
            cleanForTranslation("真的好吃 🔥 [害羞R] #美食 #探店"),
        )
    }

    @Test
    fun `collapses runaway character repeats`() {
        // "疯" x7 made the model emit an unbounded loop of the same word
        assertEquals("好吃到疯疯", cleanForTranslation("好吃到疯疯疯疯疯疯疯"))
    }

    @Test
    fun `reports blank when nothing but decoration was there`() {
        assertEquals("", cleanForTranslation("🔥🔥 #吃货"))
        assertEquals("", cleanForTranslation("[smile R]"))
    }

    @Test
    fun `leaves ordinary prose untouched`() {
        assertEquals("应该是蚂蚁的宫殿", cleanForTranslation("应该是蚂蚁的宫殿"))
    }

    @Test
    fun `only text containing ideographs is worth translating`() {
        assertTrue(hasHan("你好"))          // ni hao
        assertTrue(hasHan("OOTD 分享"))     // mixed latin + hanzi
        assertFalse(hasHan("OOTD 2024"))
        assertFalse(hasHan(""))
        assertFalse(hasHan("안녕"))          // hangul only, nothing to do
    }
}
