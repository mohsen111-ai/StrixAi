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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.zip.ZipInputStream

class GitHubError(message: String, val status: Int = 0) : Exception(message)

data class RepoInfo(val fullName: String, val description: String, val isPrivate: Boolean, val defaultBranch: String, val pushedAt: String)

data class Snapshot(
    val files: Map<String, String>,   // editable text files
    val otherPaths: Set<String>,      // binary / oversized files: visible in the tree, not readable
    val truncated: Boolean,
)

data class FileChange(val path: String, val content: String?)  // content == null deletes the file

data class CommitResult(val commitSha: String, val branch: String, val url: String)

/** Everything the app needs from GitHub, behind an interface so tests can fake it. */
interface GitHubSource {
    suspend fun branchSha(repo: String, branch: String): String
    suspend fun snapshot(repo: String, sha: String): Snapshot
    suspend fun createBranch(repo: String, name: String, fromSha: String)
    suspend fun commit(repo: String, branch: String, parentSha: String, changes: List<FileChange>, message: String): CommitResult
    suspend fun openPullRequest(repo: String, title: String, body: String, head: String, base: String): String
    suspend fun existingPullRequest(repo: String, head: String, base: String): String?
}

class GitHubApi(
    private val http: OkHttpClient,
    private val token: String,
    private val baseUrl: String = "https://api.github.com",
) : GitHubSource {

    private fun req(path: String) = Request.Builder()
        .url(if (path.startsWith("http")) path else baseUrl + path)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "Strix-Android")

    private suspend fun send(rb: Request.Builder): JsonElement {
        val (code, text) = raw(rb)
        if (code >= 400) throw GitHubError(describe(code, text), code)
        return if (text.isBlank()) JsonNull else StrixJson.parseToJsonElement(text)
    }

    private suspend fun raw(rb: Request.Builder): Pair<Int, String> = try {
        http.newCall(rb.build()).await().use { it.code to it.body?.string().orEmpty() }
    } catch (e: IOException) {
        throw GitHubError("network error: ${e.message}")
    }

    private fun describe(code: Int, body: String): String {
        val msg = try { StrixJson.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.contentOrNull } catch (e: Exception) { null }
        return when (code) {
            401 -> "GitHub rejected the token (401). Make a new one in Settings."
            403 -> "GitHub refused (403): ${msg ?: "rate limit or missing permission"}"
            404 -> "Not found (404). Check the token has access to this repo (needs Contents: read & write)."
            else -> "GitHub error $code: ${msg ?: body.take(200)}"
        }
    }

    private fun post(path: String, body: JsonElement) =
        req(path).post(body.toString().toRequestBody("application/json".toMediaType()))

    suspend fun whoAmI(): String = send(req("/user")).jsonObject["login"]!!.jsonPrimitive.content

    suspend fun listRepos(page: Int = 1): List<RepoInfo> {
        val arr = send(req("/user/repos?sort=pushed&per_page=100&page=$page&affiliation=owner,collaborator,organization_member")).jsonArray
        return arr.map { it.jsonObject }.map { o ->
            RepoInfo(
                o["full_name"]!!.jsonPrimitive.content,
                o["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                o["private"]?.jsonPrimitive?.booleanOrNull ?: false,
                o["default_branch"]?.jsonPrimitive?.contentOrNull ?: "main",
                o["pushed_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
    }

    suspend fun repoInfo(repo: String): RepoInfo {
        val o = send(req("/repos/$repo")).jsonObject
        return RepoInfo(
            o["full_name"]!!.jsonPrimitive.content,
            o["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            o["private"]?.jsonPrimitive?.booleanOrNull ?: false,
            o["default_branch"]?.jsonPrimitive?.contentOrNull ?: "main",
            o["pushed_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    suspend fun branches(repo: String): List<String> =
        send(req("/repos/$repo/branches?per_page=100")).jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    override suspend fun branchSha(repo: String, branch: String): String {
        val o = send(req("/repos/$repo/git/ref/heads/${encodePath(branch)}")).jsonObject
        return o["object"]!!.jsonObject["sha"]!!.jsonPrimitive.content
    }

    /** The whole repo as one zip: one request instead of hundreds, and grep becomes instant. */
    override suspend fun snapshot(repo: String, sha: String): Snapshot {
        val files = LinkedHashMap<String, String>()
        val others = LinkedHashSet<String>()
        var total = 0L
        var truncated = false
        try {
            http.newCall(req("/repos/$repo/zipball/$sha").build()).await().use { r ->
                if (r.code >= 400) throw GitHubError(describe(r.code, r.body?.string().orEmpty()), r.code)
                ZipInputStream(r.body!!.byteStream()).use { zip ->
                    var prefix: String? = null
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (e.isDirectory) continue
                        if (prefix == null) prefix = e.name.substringBefore('/') + "/"
                        val path = e.name.removePrefix(prefix)
                        if (path.isEmpty()) continue
                        if (truncated) { others += path; continue }
                        val bytes = readCapped(zip, MAX_FILE_BYTES + 1)
                        if (bytes.size > MAX_FILE_BYTES || !looksLikeText(bytes)) { others += path; continue }
                        total += bytes.size
                        if (total > MAX_TOTAL_BYTES) { truncated = true; others += path; continue }
                        files[path] = String(bytes, Charsets.UTF_8)
                    }
                }
            }
        } catch (e: IOException) {
            throw GitHubError("download failed: ${e.message}")
        }
        return Snapshot(files, others, truncated)
    }

    override suspend fun createBranch(repo: String, name: String, fromSha: String) {
        send(post("/repos/$repo/git/refs", buildJsonObject { put("ref", "refs/heads/$name"); put("sha", fromSha) }))
    }

    /** One commit with every change, built through the git data API (no per-file requests). */
    override suspend fun commit(repo: String, branch: String, parentSha: String, changes: List<FileChange>, message: String): CommitResult {
        val baseTree = send(req("/repos/$repo/git/commits/$parentSha")).jsonObject["tree"]!!.jsonObject["sha"]!!.jsonPrimitive.content
        val tree = send(post("/repos/$repo/git/trees", buildJsonObject {
            put("base_tree", baseTree)
            put("tree", buildJsonArray {
                changes.forEach { c ->
                    add(buildJsonObject {
                        put("path", c.path)
                        put("mode", "100644")
                        put("type", "blob")
                        if (c.content == null) put("sha", JsonNull) else put("content", c.content)
                    })
                }
            })
        })).jsonObject["sha"]!!.jsonPrimitive.content
        val commit = send(post("/repos/$repo/git/commits", buildJsonObject {
            put("message", message)
            put("tree", tree)
            put("parents", buildJsonArray { add(JsonPrimitive(parentSha)) })
        })).jsonObject
        val sha = commit["sha"]!!.jsonPrimitive.content
        send(req("/repos/$repo/git/refs/heads/${encodePath(branch)}")
            .patch(buildJsonObject { put("sha", sha) }.toString().toRequestBody("application/json".toMediaType())))
        return CommitResult(sha, branch, "https://github.com/$repo/commit/$sha")
    }

    override suspend fun openPullRequest(repo: String, title: String, body: String, head: String, base: String): String {
        val o = send(post("/repos/$repo/pulls", buildJsonObject {
            put("title", title); put("body", body); put("head", head); put("base", base)
        })).jsonObject
        return o["html_url"]!!.jsonPrimitive.content
    }

    override suspend fun existingPullRequest(repo: String, head: String, base: String): String? {
        val owner = repo.substringBefore('/')
        val arr = send(req("/repos/$repo/pulls?state=open&head=${owner}:${encodePath(head)}&base=${encodePath(base)}")).jsonArray
        return arr.firstOrNull()?.jsonObject?.get("html_url")?.jsonPrimitive?.contentOrNull
    }

    companion object {
        const val MAX_FILE_BYTES = 300_000
        const val MAX_TOTAL_BYTES = 40_000_000L

        fun encodePath(p: String) = p.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

        private fun readCapped(zip: ZipInputStream, cap: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var n = 0
            while (true) {
                val r = zip.read(buf)
                if (r < 0) break
                if (n < cap) { out.write(buf, 0, minOf(r, cap - n)) }
                n += r
            }
            return out.toByteArray().let { if (n > cap) it.copyOf(cap) else it }
        }

        fun looksLikeText(b: ByteArray): Boolean {
            val n = minOf(b.size, 4096)
            for (i in 0 until n) if (b[i] == 0.toByte()) return false
            return true
        }
    }
}
