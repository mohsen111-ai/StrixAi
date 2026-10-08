package dev.strix.app.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ToolsTest {
    private fun run(tb: ToolBox, name: String, json: String) = tb.execute(name, tb.parseArgs(json)!!)
    private val code = "fun a() {\n    return 1\n}\n\nfun b() {\n    return 1\n}\n"

    @Test fun readFileNumbersLinesAndPages() {
        val tb = ToolBox(ws("big.txt" to (1..1000).joinToString("\n") { "line$it" }))
        val r = run(tb, "read_file", """{"path":"big.txt"}""")
        assertTrue(r.ok); assertTrue(r.text.contains("  1| line1")); assertTrue(r.text.contains("start_line=401"))
        val r2 = run(tb, "read_file", """{"path":"big.txt","start_line":998,"end_line":1000}""")
        assertTrue(r2.text.contains("998| line998")); assertFalse(r2.text.contains("more lines"))
        assertFalse(run(tb, "read_file", """{"path":"nope.txt"}""").ok)
    }

    @Test fun readMissingSuggestsSimilarPaths() {
        val tb = ToolBox(ws("src/Main.kt" to "x"))
        assertTrue(run(tb, "read_file", """{"path":"Main.kt"}""").text.contains("src/Main.kt"))
    }

    @Test fun editRequiresUniqueMatch() {
        val w = ws("a.kt" to code); val tb = ToolBox(w)
        val dup = run(tb, "edit_file", """{"path":"a.kt","old":"    return 1","new":"    return 2"}""")
        assertFalse(dup.ok); assertTrue(dup.text.contains("2 places"))
        val one = run(tb, "edit_file", """{"path":"a.kt","old":"fun a() {\n    return 1","new":"fun a() {\n    return 2"}""")
        assertTrue(one.ok)
        assertEquals(1, w.changes().single().diff.added)
        val all = run(tb, "edit_file", """{"path":"a.kt","old":"return 1","new":"return 3","replace_all":true}""")
        assertTrue(all.ok); assertEquals(1, w.read("a.kt")!!.split("return 3").size - 1)
    }

    @Test fun editFindsLooselyIndentedText() {
        val w = ws("a.kt" to code); val tb = ToolBox(w)
        val r = run(tb, "edit_file", """{"path":"a.kt","old":"fun b() {\nreturn 1\n}","new":"fun b() {\n    return 9\n}"}""")
        assertTrue(r.text, r.ok)
        assertTrue(w.read("a.kt")!!.contains("return 9"))
    }

    @Test fun editNotFoundGivesHint() {
        val tb = ToolBox(ws("a.kt" to code))
        val r = run(tb, "edit_file", """{"path":"a.kt","old":"fun a() { nothing }","new":"x"}""")
        assertFalse(r.ok); assertTrue(r.text.contains("not found"))
    }

    @Test fun editKeepsCrlf() {
        val w = ws("w.txt" to "one\r\ntwo\r\nthree\r\n"); val tb = ToolBox(w)
        assertTrue(run(tb, "edit_file", """{"path":"w.txt","old":"two\nthree","new":"2\nthree"}""").ok)
        assertEquals("one\r\n2\r\nthree\r\n", w.read("w.txt"))
    }

    @Test fun writeDeleteMove() {
        val w = ws("a.txt" to "x"); val tb = ToolBox(w)
        assertTrue(run(tb, "write_file", """{"path":"d/new.txt","content":"hi"}""").text.startsWith("created"))
        assertTrue(run(tb, "move_file", """{"from":"a.txt","to":"b.txt"}""").ok)
        assertTrue(w.exists("b.txt")); assertFalse(w.exists("a.txt"))
        assertFalse(run(tb, "delete_file", """{"path":"zzz"}""").ok)
        assertFalse(run(tb, "write_file", """{"path":"../x","content":"c"}""").ok)
    }

    @Test fun grepFallsBackToLiteralOnBadRegex() {
        val tb = ToolBox(ws("a.txt" to "call foo(\nbar"))
        assertTrue(run(tb, "grep", """{"pattern":"foo("}""").text.contains("a.txt:1"))
    }

    @Test fun todoUpdatesList() {
        var seen: List<TodoItem> = emptyList()
        val tb = ToolBox(ws(), onTodo = { seen = it })
        run(tb, "todo", """{"items":[{"text":"a","done":true},{"text":"b"},"c"]}""")
        assertEquals(listOf(true, false, false), seen.map { it.done })
    }

    @Test fun argumentRepair() {
        val tb = ToolBox(ws())
        assertEquals("a", tb.parseArgs("```json\n{\"path\":\"a\"}\n```")!!["path"]!!.jsonPrimitive.content)
        assertEquals("a", tb.parseArgs("sure! {\"path\":\"a\",}")!!["path"]!!.jsonPrimitive.content)
        assertEquals("a", tb.parseArgs("{\"path\":\"a\"")!!["path"]!!.jsonPrimitive.content)
        assertNull(tb.parseArgs("not json at all"))
        assertTrue(tb.parseArgs("")!!.isEmpty())
    }

    @Test fun validation() {
        val tb = ToolBox(ws())
        assertNotNull(tb.validate("read_file", JsonObject(emptyMap())))
        assertNotNull(tb.validate("nope", JsonObject(emptyMap())))
        assertNull(tb.validate("read_file", tb.parseArgs("""{"path":"a","start_line":3}""")!!))
        assertNotNull(tb.validate("read_file", tb.parseArgs("""{"path":5}""")!!))
    }

    @Test fun schemasAreWellFormed() {
        val s = ToolBox(ws()).schemas()
        assertEquals(9, s.size)
        s.forEach {
            val f = it.jsonObject["function"]!!.jsonObject
            assertTrue(f["name"]!!.jsonPrimitive.content.isNotEmpty())
            val p = f["parameters"]!!.jsonObject
            assertEquals("object", p["type"]!!.jsonPrimitive.content)
            p["required"]!!.jsonArray.forEach { r -> assertTrue(p["properties"]!!.jsonObject.containsKey(r.jsonPrimitive.content)) }
        }
    }
}
