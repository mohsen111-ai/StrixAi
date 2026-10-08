package dev.strix.app.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

data class ToolResult(val ok: Boolean, val text: String, val summary: String = text.lineSequence().firstOrNull().orEmpty().take(120))

data class TodoItem(val text: String, val done: Boolean)

private data class Param(val name: String, val type: String, val desc: String, val required: Boolean = true)
private data class Spec(val name: String, val desc: String, val params: List<Param>, val write: Boolean = false)

/** The agent's hands. Every tool works on the in-memory Workspace; nothing reaches GitHub until the user commits. */
class ToolBox(private val ws: Workspace, private val onTodo: (List<TodoItem>) -> Unit = {}) {
    var todos: List<TodoItem> = emptyList(); private set

    fun isWrite(name: String) = specs.firstOrNull { it.name == name }?.write == true

    fun schemas(): JsonArray = buildJsonArray {
        specs.forEach { s ->
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", s.name)
                    put("description", s.desc)
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            s.params.forEach { p ->
                                put(p.name, buildJsonObject {
                                    if (p.type == "array") {
                                        put("type", "array")
                                        put("items", buildJsonObject { put("type", "object") })
                                    } else put("type", p.type)
                                    put("description", p.desc)
                                })
                            }
                        })
                        putJsonArray("required") { s.params.filter { it.required }.forEach { add(JsonPrimitive(it.name)) } }
                    })
                })
            })
        }
    }

    /** Parse the model's argument string, repairing the usual small breakages (fences, trailing commas, prose around it). */
    fun parseArgs(raw: String): JsonObject? {
        var t = raw.trim()
        if (t.isEmpty()) return JsonObject(emptyMap())
        t = t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        fun tryParse(s: String): JsonObject? = try { StrixJson.parseToJsonElement(s) as? JsonObject } catch (e: Exception) { null }
        tryParse(t)?.let { return it }
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val core = t.substring(start, end + 1)
            tryParse(core)?.let { return it }
            tryParse(core.replace(Regex(",\\s*([}\\]])"), "$1"))?.let { return it }
        }
        if (start >= 0) tryParse(t.substring(start) + "}")?.let { return it } // truncated output
        return null
    }

    fun validate(name: String, args: JsonObject): String? {
        val spec = specs.firstOrNull { it.name == name }
            ?: return "unknown tool '$name'. Available: ${specs.joinToString { it.name }}"
        for (p in spec.params) {
            val v = args[p.name]
            if (v == null || v is JsonNull) {
                if (p.required) return "missing required argument '${p.name}' (${p.type}): ${p.desc}"
                continue
            }
            val ok = when (p.type) {
                "string" -> v is JsonPrimitive && v.isString
                "integer" -> v is JsonPrimitive && !v.isString && (v.intOrNull != null || v.doubleOrNull != null)
                "boolean" -> v is JsonPrimitive && (v.booleanOrNull != null || v.contentOrNull in setOf("true", "false"))
                "array" -> v is JsonArray
                else -> true
            }
            if (!ok) return "argument '${p.name}' must be a ${p.type}"
        }
        return null
    }

    fun execute(name: String, args: JsonObject): ToolResult = try {
        when (name) {
            "list_dir" -> listDir(args.str("path") ?: "")
            "read_file" -> readFile(args.str("path")!!, args.int("start_line"), args.int("end_line"))
            "glob" -> glob(args.str("pattern")!!)
            "grep" -> grep(args.str("pattern")!!, args.str("path"), args.str("glob"), args.bool("ignore_case") ?: false)
            "write_file" -> writeFile(args.str("path")!!, args.str("content")!!)
            "edit_file" -> editFile(args.str("path")!!, args.str("old")!!, args.str("new")!!, args.bool("replace_all") ?: false)
            "delete_file" -> deleteFile(args.str("path")!!)
            "move_file" -> moveFile(args.str("from")!!, args.str("to")!!)
            "todo" -> todo(args["items"] as JsonArray)
            else -> ToolResult(false, "unknown tool '$name'")
        }
    } catch (e: WorkspaceException) {
        ToolResult(false, "error: ${e.message}")
    }

    // ---- reading ----------------------------------------------------------
    private fun listDir(path: String): ToolResult {
        val items = ws.list(path)
        if (items.isEmpty()) return ToolResult(false, "error: '${path.ifEmpty { "/" }}' is empty or does not exist")
        val shown = items.take(300)
        return ToolResult(true, shown.joinToString("\n") + if (items.size > shown.size) "\n... ${items.size - shown.size} more" else "", "${items.size} entries")
    }

    private fun readFile(path: String, start: Int?, end: Int?): ToolResult {
        val p = Workspace.normalize(path)
        if (ws.isBinary(p)) return ToolResult(false, "error: $p is a binary or very large file; it cannot be read here")
        val text = ws.read(p) ?: return ToolResult(false, "error: $p does not exist" + similar(p))
        val lines = text.split("\n")
        val from = ((start ?: 1).coerceAtLeast(1)) - 1
        val to = (end ?: (from + MAX_READ_LINES)).coerceAtMost(lines.size).coerceAtMost(from + MAX_READ_LINES)
        if (from >= lines.size) return ToolResult(false, "error: $p has only ${lines.size} lines")
        val width = to.toString().length
        val body = (from until to).joinToString("\n") { i -> "${(i + 1).toString().padStart(width)}| ${lines[i]}" }
        val more = if (to < lines.size) "\n... (${lines.size - to} more lines; call again with start_line=${to + 1})" else ""
        val out = (if (body.length > MAX_READ_CHARS) body.take(MAX_READ_CHARS) + "\n... (truncated, narrow the line range)" else body) + more
        return ToolResult(true, out, "$p lines ${from + 1}-$to of ${lines.size}")
    }

    private fun similar(p: String): String {
        val name = p.substringAfterLast('/').lowercase()
        val hits = ws.allPaths().filter { it.substringAfterLast('/').lowercase() == name }.take(5)
        return if (hits.isEmpty()) "" else ". Did you mean: ${hits.joinToString()}?"
    }

    private fun glob(pattern: String): ToolResult {
        val hits = ws.glob(pattern)
        if (hits.isEmpty()) return ToolResult(true, "no files match '$pattern'", "0 files")
        val shown = hits.take(200)
        return ToolResult(true, shown.joinToString("\n") + if (hits.size > shown.size) "\n... ${hits.size - shown.size} more" else "", "${hits.size} files")
    }

    private fun grep(pattern: String, path: String?, glob: String?, ignoreCase: Boolean): ToolResult {
        val rx = try {
            Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
        } catch (e: Exception) {
            Regex(Regex.escape(pattern), if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
        }
        val hits = ws.grep(rx, path, glob?.let { Workspace.globToRegex(it) }, 100)
        if (hits.isEmpty()) return ToolResult(true, "no matches for '$pattern'", "0 matches")
        return ToolResult(true, hits.joinToString("\n") + if (hits.size >= 100) "\n... (limit 100 reached; narrow the search)" else "", "${hits.size} matches")
    }

    // ---- writing ----------------------------------------------------------
    private fun writeFile(path: String, content: String): ToolResult {
        val p = Workspace.normalize(path)
        val existed = ws.exists(p)
        ws.write(p, content)
        val lines = content.count { it == '\n' } + 1
        return ToolResult(true, (if (existed) "overwrote" else "created") + " $p ($lines lines)", (if (existed) "wrote " else "created ") + p)
    }

    private fun editFile(path: String, old: String, new: String, all: Boolean): ToolResult {
        val p = Workspace.normalize(path)
        if (old.isEmpty()) return ToolResult(false, "error: 'old' is empty. To create a file use write_file.")
        if (old == new) return ToolResult(false, "error: 'old' and 'new' are identical")
        val text = ws.read(p) ?: return ToolResult(false, "error: $p does not exist" + similar(p) + ". Use write_file to create it.")
        val crlf = text.contains("\r\n")
        fun fit(s: String) = if (crlf) s.replace("\r\n", "\n").replace("\n", "\r\n") else s.replace("\r\n", "\n")
        val o = fit(old)
        val n = fit(new)
        var count = countOf(text, o)
        var source = text
        var from = o
        var to = n
        if (count == 0) {
            // Models often get trailing whitespace or indentation of the first/last line wrong. Try a trimmed-line match.
            val loose = looseRange(text, o)
            if (loose != null) { from = loose; count = countOf(text, loose); to = n }
        }
        if (count == 0) return ToolResult(false, "error: 'old' text was not found in $p. Re-read the file with read_file and copy the exact text.${hint(text, old)}")
        if (count > 1 && !all) return ToolResult(false, "error: 'old' matches $count places in $p. Add more surrounding lines to make it unique, or set replace_all=true.")
        source = if (all) text.replace(from, to) else text.replaceFirst(from, to)
        ws.write(p, source)
        val line = text.substring(0, text.indexOf(from)).count { it == '\n' } + 1
        return ToolResult(true, "edited $p at line $line" + if (count > 1) " ($count replacements)" else "", "edited $p")
    }

    private fun countOf(text: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var c = 0
        var i = text.indexOf(needle)
        while (i >= 0) { c++; i = text.indexOf(needle, i + needle.length) }
        return c
    }

    /** Find the file's own text that equals [old] when each line is compared with surrounding whitespace removed. */
    private fun looseRange(text: String, old: String): String? {
        val oldLines = old.trim('\n').split("\n").map { it.trim() }
        if (oldLines.all { it.isEmpty() }) return null
        val lines = text.split("\n")
        val hits = ArrayList<Int>()
        for (i in 0..lines.size - oldLines.size) {
            if (oldLines.indices.all { k -> lines[i + k].trim() == oldLines[k] }) hits += i
        }
        if (hits.size != 1) return null
        val i = hits[0]
        return lines.subList(i, i + oldLines.size).joinToString("\n")
    }

    private fun hint(text: String, old: String): String {
        val first = old.lineSequence().map { it.trim() }.firstOrNull { it.length > 3 } ?: return ""
        val lines = text.split("\n")
        val idx = lines.indexOfFirst { it.contains(first) }
        if (idx < 0) return ""
        val from = maxOf(0, idx - 1)
        val to = minOf(lines.size, idx + 4)
        return "\nClosest match near line ${idx + 1}:\n" + (from until to).joinToString("\n") { "${it + 1}| ${lines[it]}" }
    }

    private fun deleteFile(path: String): ToolResult {
        val p = Workspace.normalize(path)
        ws.delete(p)
        return ToolResult(true, "deleted $p", "deleted $p")
    }

    private fun moveFile(from: String, to: String): ToolResult {
        val a = Workspace.normalize(from)
        val b = Workspace.normalize(to)
        val text = ws.read(a) ?: return ToolResult(false, "error: $a does not exist or is not text")
        if (ws.exists(b)) return ToolResult(false, "error: $b already exists")
        ws.write(b, text)
        ws.delete(a)
        return ToolResult(true, "moved $a -> $b", "moved $a")
    }

    private fun todo(items: JsonArray): ToolResult {
        todos = items.mapNotNull { el ->
            when (el) {
                is JsonObject -> TodoItem(
                    el["text"]?.jsonPrimitive?.contentOrNull ?: el["task"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    el["done"]?.jsonPrimitive?.booleanOrNull ?: (el["status"]?.jsonPrimitive?.contentOrNull == "done"),
                )
                is JsonPrimitive -> TodoItem(el.content, false)
                else -> null
            }
        }
        onTodo(todos)
        return ToolResult(true, "checklist updated (${todos.count { it.done }}/${todos.size} done)", "${todos.count { it.done }}/${todos.size} done")
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

    companion object {
        const val MAX_READ_LINES = 400
        const val MAX_READ_CHARS = 30_000

        private val specs = listOf(
            Spec("list_dir", "List files and folders in a directory of the repo (folders end with /). Use '' for the repo root.",
                listOf(Param("path", "string", "directory path, '' for root", required = false))),
            Spec("read_file", "Read a text file with line numbers. Long files are paged: pass start_line/end_line.",
                listOf(Param("path", "string", "file path"), Param("start_line", "integer", "first line, 1-based", false), Param("end_line", "integer", "last line", false))),
            Spec("glob", "Find files by glob pattern, e.g. '**/*.kt' or 'src/**/test_*.py'.",
                listOf(Param("pattern", "string", "glob pattern"))),
            Spec("grep", "Search file contents with a regex. Returns path:line: text.",
                listOf(Param("pattern", "string", "regex"), Param("path", "string", "limit to this folder", false), Param("glob", "string", "limit to files matching, e.g. '*.py'", false), Param("ignore_case", "boolean", "case-insensitive", false))),
            Spec("write_file", "Create a new file or fully replace an existing one. Prefer edit_file for changes to existing files.",
                listOf(Param("path", "string", "file path"), Param("content", "string", "the complete file content")), write = true),
            Spec("edit_file", "Replace exact text in a file. 'old' must match the file exactly (including indentation) and be unique unless replace_all is true.",
                listOf(Param("path", "string", "file path"), Param("old", "string", "exact existing text"), Param("new", "string", "replacement text"), Param("replace_all", "boolean", "replace every occurrence", false)), write = true),
            Spec("delete_file", "Delete a file.", listOf(Param("path", "string", "file path")), write = true),
            Spec("move_file", "Move or rename a text file.", listOf(Param("from", "string", "current path"), Param("to", "string", "new path")), write = true),
            Spec("todo", "Keep a checklist for multi-step work. Send the full list each time: [{\"text\": \"...\", \"done\": false}].",
                listOf(Param("items", "array", "the full checklist"))),
        )
    }
}
