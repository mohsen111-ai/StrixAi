package dev.strix.app.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GitHubApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: GitHubApi
    @Before fun up() { server = MockWebServer(); server.start(); api = GitHubApi(newHttpClient(), "tok", server.url("").toString().trimEnd('/')) }
    @After fun down() { server.shutdown() }

    private fun zip(vararg e: Pair<String, ByteArray>): Buffer {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z -> e.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return Buffer().write(bo.toByteArray())
    }

    @Test fun snapshotStripsPrefixAndSeparatesBinaries() = runBlocking {
        server.enqueue(MockResponse().setBody(zip(
            "me-app-abc123/" to ByteArray(0),
            "me-app-abc123/README.md" to "# hi\n".toByteArray(),
            "me-app-abc123/src/Main.kt" to "fun main() {}".toByteArray(),
            "me-app-abc123/logo.png" to byteArrayOf(1, 2, 0, 3),
            "me-app-abc123/huge.txt" to ByteArray(GitHubApi.MAX_FILE_BYTES + 10) { 'a'.code.toByte() },
        )))
        val s = api.snapshot("me/app", "abc123")
        assertEquals(setOf("README.md", "src/Main.kt"), s.files.keys)
        assertEquals(setOf("logo.png", "huge.txt"), s.otherPaths)
        assertFalse(s.truncated)
        val req = server.takeRequest()
        assertEquals("/repos/me/app/zipball/abc123", req.path)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
    }

    @Test fun errorsAreHumanReadable() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Bad credentials"}"""))
        assertTrue(runCatching { api.whoAmI() }.exceptionOrNull()!!.message!!.contains("rejected the token"))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))
        assertTrue(runCatching { api.branchSha("me/x", "main") }.exceptionOrNull()!!.message!!.contains("Contents"))
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"message":"Reference already exists"}"""))
        assertTrue(runCatching { api.createBranch("me/x", "b", "s") }.exceptionOrNull()!!.message!!.contains("Reference already exists"))
    }

    @Test fun listsRepos() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"full_name":"me/a","description":null,"private":true,"default_branch":"dev","pushed_at":"2026-01-01"},{"full_name":"me/b","private":false}]"""))
        val r = api.listRepos()
        assertEquals(listOf("me/a", "me/b"), r.map { it.fullName })
        assertEquals("dev", r[0].defaultBranch); assertTrue(r[0].isPrivate); assertEquals("main", r[1].defaultBranch)
        assertTrue(server.takeRequest().path!!.startsWith("/user/repos?sort=pushed"))
    }

    @Test fun commitUsesOneTreeOneCommitAndMovesTheRef() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"tree":{"sha":"T0"}}"""))
        server.enqueue(MockResponse().setBody("""{"sha":"T1"}"""))
        server.enqueue(MockResponse().setBody("""{"sha":"C1"}"""))
        server.enqueue(MockResponse().setBody("""{"ref":"x"}"""))
        val res = api.commit("me/app", "strix/feat", "C0", listOf(FileChange("a/b.kt", "code"), FileChange("old.txt", null)), "Add feature")
        assertEquals("C1", res.commitSha)
        val get = server.takeRequest(); assertEquals("/repos/me/app/git/commits/C0", get.path)
        val tree = server.takeRequest(); assertEquals("POST", tree.method)
        val tb = StrixJson.parseToJsonElement(tree.body.readUtf8()).jsonObject
        assertEquals("T0", tb["base_tree"]!!.jsonPrimitive.content)
        val entries = tb["tree"]!!.jsonArray.map { it.jsonObject }
        assertEquals("code", entries[0]["content"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, entries[1]["sha"])
        val commit = StrixJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("T1", commit["tree"]!!.jsonPrimitive.content)
        assertEquals("C0", commit["parents"]!!.jsonArray[0].jsonPrimitive.content)
        val ref = server.takeRequest()
        assertEquals("PATCH", ref.method); assertEquals("/repos/me/app/git/refs/heads/strix/feat", ref.path)
        assertEquals("C1", StrixJson.parseToJsonElement(ref.body.readUtf8()).jsonObject["sha"]!!.jsonPrimitive.content)
    }

    @Test fun branchAndPullRequest() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"object":{"sha":"S9"}}"""))
        assertEquals("S9", api.branchSha("me/app", "feature/x y"))
        assertEquals("/repos/me/app/git/ref/heads/feature/x%20y", server.takeRequest().path)
        server.enqueue(MockResponse().setBody("{}"))
        api.createBranch("me/app", "strix/n", "S9")
        val b = StrixJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("refs/heads/strix/n", b["ref"]!!.jsonPrimitive.content)
        server.enqueue(MockResponse().setBody("""{"html_url":"https://github.com/me/app/pull/3"}"""))
        assertEquals("https://github.com/me/app/pull/3", api.openPullRequest("me/app", "T", "B", "strix/n", "main"))
        server.enqueue(MockResponse().setBody("""[{"html_url":"https://github.com/me/app/pull/4"}]"""))
        assertEquals("https://github.com/me/app/pull/4", api.existingPullRequest("me/app", "strix/n", "main"))
    }
}
