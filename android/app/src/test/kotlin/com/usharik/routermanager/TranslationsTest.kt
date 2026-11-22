package com.usharik.routermanager

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Все языки содержат одни и те же строки с теми же подстановками: пропуск или опечатка в %1$s роняет приложение. */
class TranslationsTest {
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private val languages = listOf("", "-ru", "-uk", "-cs", "-de")
    private val placeholder = Regex("""%(\d+\$)?[sdf]""")

    private fun load(suffix: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "values$suffix/strings.xml"))
        val out = sortedMapOf<String, String>()
        val strings = doc.getElementsByTagName("string")
        for (i in 0 until strings.length) (strings.item(i) as Element).let { out[it.getAttribute("name")] = it.textContent }
        val plurals = doc.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) {
            val p = plurals.item(i) as Element
            val items = p.getElementsByTagName("item")
            for (j in 0 until items.length) (items.item(j) as Element).let { out["${p.getAttribute("name")}#${it.getAttribute("quantity")}"] = it.textContent }
        }
        return out
    }

    @Test fun sameKeysAndPlaceholdersInEveryLanguage() {
        val base = load("")
        val baseStrings = base.keys.filterNot { '#' in it }.toSet()
        assertTrue(baseStrings.size > 150)
        for (lang in languages.drop(1)) {
            val tr = load(lang)
            assertEquals("ключи values$lang", baseStrings, tr.keys.filterNot { '#' in it }.toSet())
            for (key in baseStrings) {
                assertEquals("подстановки $key в values$lang", placeholder.findAll(base.getValue(key)).map { it.value }.toSet(), placeholder.findAll(tr.getValue(key)).map { it.value }.toSet())
                assertTrue("пустая строка $key в values$lang", tr.getValue(key).isNotBlank())
            }
            assertTrue("в values$lang есть форма other у каждого plurals", base.keys.filter { it.endsWith("#other") }.all { it in tr.keys })
        }
    }

    @Test fun russianIsNotLeftInOtherLanguages() {
        val cyrillicOnlyRu = Regex("[ыэъё]", RegexOption.IGNORE_CASE)
        for (lang in listOf("", "-cs", "-de")) {
            val leftovers = load(lang).filter { (_, v) -> v.any { it in 'а'..'я' || it in 'А'..'Я' } }.keys
            assertEquals("кириллица в values$lang", emptySet<String>(), leftovers)
        }
        val uk = load("-uk").filter { (_, v) -> cyrillicOnlyRu.containsMatchIn(v) }.keys
        assertEquals("русские буквы в украинском", emptySet<String>(), uk)
    }

    /** Формы множественного числа по CLDR: в ru и uk нужны one/few/many, в cs — one/few/other; без них число выйдет с ошибкой. */
    @Test fun pluralFormsCompleteForEachLanguage() {
        val required = mapOf("" to setOf("one", "other"), "-de" to setOf("one", "other"), "-ru" to setOf("one", "few", "many", "other"),
            "-uk" to setOf("one", "few", "many", "other"), "-cs" to setOf("one", "few", "other"))
        val base = load("")
        val plurals = base.keys.filter { '#' in it }.map { it.substringBefore('#') }.toSet()
        assertTrue(plurals.isNotEmpty())
        for ((lang, forms) in required) {
            val tr = load(lang)
            for (name in plurals) {
                val have = tr.keys.filter { it.startsWith("$name#") }.map { it.substringAfter('#') }.toSet()
                assertTrue("$name в values$lang: есть $have, нужны $forms", have.containsAll(forms))
                val expected = placeholder.findAll(base.getValue("$name#other")).map { it.value }.toSet()
                for (form in have) assertEquals("подстановки $name#$form в values$lang", expected, placeholder.findAll(tr.getValue("$name#$form")).map { it.value }.toSet())
            }
        }
    }

    @Test fun localeTagMatchesFolder() {
        for ((lang, tag) in mapOf("" to "en", "-ru" to "ru", "-uk" to "uk", "-cs" to "cs", "-de" to "de")) assertEquals(tag, load(lang).getValue("locale_tag"))
    }
}
