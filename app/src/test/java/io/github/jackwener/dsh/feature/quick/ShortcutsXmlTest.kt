package io.github.jackwener.dsh.feature.quick

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * #82: every static shortcut points at ShortcutActivity with an action it handles, under the id it reports.
 * A typo there fails silently on the device (the shortcut does nothing, or the system drops it).
 */
class ShortcutsXmlTest {
    private val res = File("src/main/res")

    @Test fun shortcutsMatchTheTrampoline() {
        val xml = File(res, "xml/shortcuts.xml").readText()
        val shortcuts = Regex("""<shortcut\b(.*?)</shortcut>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1] }.toList()
        fun attr(block: String, name: String) = Regex("""android:$name="([^"]+)"""").find(block)?.groupValues?.get(1)
        val byAction = shortcuts.associate { s ->
            assertEquals("io.github.jackwener.dsh", attr(s, "targetPackage"))
            assertEquals(ShortcutActivity::class.java.name, attr(s, "targetClass"))
            attr(s, "action") to attr(s, "shortcutId")
        }
        assertEquals(ShortcutActivity.SHORTCUT_IDS, byAction)
        // Labels exist in every shipped language.
        val labels = shortcuts.flatMap { s -> listOf("shortcutShortLabel", "shortcutLongLabel").map { attr(s, it)!!.removePrefix("@string/") } }
        res.listFiles().orEmpty().map { File(it, "strings.xml") }.filter { it.exists() }.forEach { f ->
            val text = f.readText()
            labels.forEach { l -> assert(text.contains("name=\"$l\"")) { "$l missing in ${f.parentFile.name}" } }
        }
    }
}
