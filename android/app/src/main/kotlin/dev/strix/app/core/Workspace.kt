package dev.strix.app.core

import kotlinx.serialization.Serializable

enum class ChangeKind { ADDED, MODIFIED, DELETED }

data class StagedChange(val path: String, val kind: ChangeKind, val old: String?, val new: String?) {
    val diff by lazy { Diff.unified(old.orEmpty(), new.orEmpty()) }
}

@Serializable
data class OverlayEntry(val path: String, val content: String?)

class WorkspaceException(message: String) : Exception(message)

/**
 * A GitHub repo held in memory plus the edits the agent has staged on top of it.
 * Nothing leaves the phone until the user commits.
 */
class Workspace(
    val repo: String,
    val baseBranch: String,
    baseSha: String,
    snapshot: Snapshot,
) {
    var baseSha: String = baseSha; private set
    var workBranch: String? = null
    val truncated = snapshot.truncated

    private var base: MutableMap<String, String> = snapshot.files.toMutableMap()
    private val others: MutableSet<String> = snapshot.otherPaths.toMutableSet()
    private val staged = LinkedHashMap<String, String?>() // null = deleted

    // ---- reading ---------------------------------------------------------
    fun allPaths(): List<String> = ((base.keys + others + staged.filterValues { it != null }.keys) - staged.filterValues { it == null }.keys).sorted()

    fun exists(path: String): Boolean = when {
        staged.containsKey(path) -> staged[path] != null
        else -> path in base || path in others
    }

    fun isBinary(path: String) = path in others && !staged.containsKey(path)

    fun read(path: String): String? = if (staged.containsKey(path)) staged[path] else base[path]

    fun textPaths(): List<String> = allPaths().filter { !isBinary(it) }

    fun list(dir: String): List<String> {
        val d = normalize(dir).trimEnd('/')
        val prefix = if (d.isEmpty()) "" else "$d/"
        val seen = LinkedHashSet<String>()
        for (p in allPaths()) {
            if (!p.startsWith(prefix)) continue
            val rest = p.removePrefix(prefix)
            val slash = rest.indexOf('/')
            seen += if (slash < 0) rest else rest.substring(0, slash + 1)
        }
        return seen.sortedWith(compareBy({ !it.endsWith("/") }, { it }))
    }

    // ---- writing ---------------------------------------------------------
    fun write(path: String, content: String) {
        val p = checkWritable(path)
        if (content.length > 1_000_000) throw WorkspaceException("file too large (max 1 MB)")
        stage(p, content)
    }

    fun delete(path: String) {
        val p = checkWritable(path)
        if (!exists(p)) throw WorkspaceException("$p does not exist")
        stage(p, null)
    }

    /** Drop one file's staged edit and go back to the repo version. */
    fun revert(path: String) { staged.remove(path) }

    fun revertAll() = staged.clear()

    private fun stage(p: String, content: String?) {
        // Staging the same text the repo already has is a no-op, so the change list stays honest.
        val inRepo = p in base || p in others
        if (content == null) {
            if (inRepo) staged[p] = null else staged.remove(p)
        } else if (content == base[p]) {
            staged.remove(p)
        } else {
            staged[p] = content
        }
    }

    private fun checkWritable(path: String): String {
        val p = normalize(path)
        if (p.isEmpty()) throw WorkspaceException("empty path")
        if (p == ".git" || p.startsWith(".git/")) throw WorkspaceException(".git is read-only")
        if (isBinary(p)) throw WorkspaceException("$p is a binary or very large file and cannot be edited here")
        return p
    }

    // ---- review ----------------------------------------------------------
    fun changes(): List<StagedChange> = staged.map { (p, new) ->
        val old = base[p]
        StagedChange(p, when { new == null -> ChangeKind.DELETED; old == null -> ChangeKind.ADDED; else -> ChangeKind.MODIFIED }, old, new)
    }.sortedBy { it.path }

    fun hasChanges() = staged.isNotEmpty()

    fun toFileChanges(): List<FileChange> = staged.map { (p, c) -> FileChange(p, c) }

    /** After a commit the staged edits become the new baseline. */
    fun markCommitted(newSha: String, branch: String) {
        for ((p, c) in staged) {
            if (c == null) { base.remove(p); others.remove(p) } else base[p] = c
        }
        staged.clear()
        baseSha = newSha
        workBranch = branch
    }

    fun exportOverlay(): List<OverlayEntry> = staged.map { OverlayEntry(it.key, it.value) }

    fun importOverlay(entries: List<OverlayEntry>) {
        for (e in entries) {
            if (e.content != null && isBinary(e.path)) continue
            staged[e.path] = e.content
        }
    }

    // ---- search ----------------------------------------------------------
    fun grep(regex: Regex, pathPrefix: String?, glob: Regex?, limit: Int = 100): List<String> {
        val out = ArrayList<String>()
        val prefix = pathPrefix?.let { normalize(it).trimEnd('/') }.orEmpty()
        for (p in textPaths()) {
            if (prefix.isNotEmpty() && p != prefix && !p.startsWith("$prefix/")) continue
            if (glob != null && !glob.matches(p) && !glob.matches(p.substringAfterLast('/'))) continue
            val text = read(p) ?: continue
            var n = 0
            for (line in text.split("\n")) {
                n++
                if (regex.containsMatchIn(line)) {
                    out += "$p:$n: ${line.trim().take(200)}"
                    if (out.size >= limit) return out
                }
            }
        }
        return out
    }

    fun glob(pattern: String): List<String> {
        val rx = globToRegex(pattern)
        return allPaths().filter { rx.matches(it) || (!pattern.contains('/') && rx.matches(it.substringAfterLast('/'))) }
    }

    /** A compact map of the repo that fits in a prompt: expands directories one level at a time while it stays small. */
    fun repoMap(maxLines: Int = 120): String {
        val paths = allPaths()
        if (paths.isEmpty()) return "(empty repository)"
        var best: List<String> = emptyList()
        for (depth in 1..8) {
            val lines = renderDepth(paths, depth)
            if (lines.size > maxLines && best.isNotEmpty()) break
            best = lines
            if (lines.size > maxLines) break
        }
        return best.take(maxLines + 20).joinToString("\n") + if (truncated) "\n(repo is large: only part of it was downloaded)" else ""
    }

    private fun renderDepth(paths: List<String>, depth: Int): List<String> {
        val out = LinkedHashMap<String, Int>() // entry -> file count (0 for plain files)
        for (p in paths) {
            val parts = p.split('/')
            if (parts.size <= depth) out[p] = 0
            else {
                val dir = parts.take(depth).joinToString("/") + "/"
                out[dir] = (out[dir] ?: 0) + 1
            }
        }
        return out.map { (k, n) -> if (n > 0) "$k ($n files)" else k }
    }

    companion object {
        fun normalize(path: String): String {
            val parts = path.trim().replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.any { it == ".." }) throw WorkspaceException("path may not contain '..'")
            return parts.joinToString("/")
        }

        fun globToRegex(glob: String): Regex {
            val sb = StringBuilder()
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                        i++
                        if (i + 1 < glob.length && glob[i + 1] == '/') { i++; sb.append("(?:.*/)?") } else sb.append(".*")
                    }
                    c == '*' -> sb.append("[^/]*")
                    c == '?' -> sb.append("[^/]")
                    c == '{' -> sb.append("(?:")
                    c == '}' -> sb.append(")")
                    c == ',' && glob.take(i).count { it == '{' } > glob.take(i).count { it == '}' } -> sb.append("|")
                    c in ".()+|^$\\[]" -> sb.append('\\').append(c)
                    else -> sb.append(c)
                }
                i++
            }
            return Regex(sb.toString())
        }
    }
}
