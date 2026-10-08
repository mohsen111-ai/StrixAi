package dev.strix.app.core

enum class DiffKind { ADD, DEL, CTX, HUNK }

data class DiffLine(val kind: DiffKind, val text: String, val oldNo: Int? = null, val newNo: Int? = null)

data class DiffResult(val lines: List<DiffLine>, val added: Int, val removed: Int)

object Diff {
    private const val MAX_CELLS = 4_000_000L

    /** Line diff with 3 lines of context. Trims the common head and tail, then LCS on the middle. */
    fun unified(old: String, new: String, context: Int = 3): DiffResult {
        val a = if (old.isEmpty()) emptyList() else old.split("\n")
        val b = if (new.isEmpty()) emptyList() else new.split("\n")
        var head = 0
        while (head < a.size && head < b.size && a[head] == b[head]) head++
        var tail = 0
        while (tail < a.size - head && tail < b.size - head && a[a.size - 1 - tail] == b[b.size - 1 - tail]) tail++
        val am = a.subList(head, a.size - tail)
        val bm = b.subList(head, b.size - tail)

        // ops over the whole file: (kind, text, oldNo, newNo)
        val ops = ArrayList<DiffLine>(a.size + b.size)
        for (i in 0 until head) ops.add(DiffLine(DiffKind.CTX, a[i], i + 1, i + 1))
        middle(am, bm, head, ops)
        for (k in 0 until tail) {
            val oi = a.size - tail + k
            val ni = b.size - tail + k
            ops.add(DiffLine(DiffKind.CTX, a[oi], oi + 1, ni + 1))
        }
        val added = ops.count { it.kind == DiffKind.ADD }
        val removed = ops.count { it.kind == DiffKind.DEL }
        if (added == 0 && removed == 0) return DiffResult(emptyList(), 0, 0)

        val keep = BooleanArray(ops.size)
        ops.forEachIndexed { i, l ->
            if (l.kind != DiffKind.CTX) for (j in maxOf(0, i - context)..minOf(ops.size - 1, i + context)) keep[j] = true
        }
        val out = ArrayList<DiffLine>()
        var i = 0
        while (i < ops.size) {
            if (!keep[i]) { i++; continue }
            val start = i
            while (i < ops.size && keep[i]) i++
            val first = ops[start]
            out.add(DiffLine(DiffKind.HUNK, "@@ -${first.oldNo ?: (ops.slice(start until i).firstNotNullOfOrNull { it.oldNo } ?: 0)} +${first.newNo ?: (ops.slice(start until i).firstNotNullOfOrNull { it.newNo } ?: 0)} @@"))
            for (k in start until i) out.add(ops[k])
        }
        return DiffResult(out, added, removed)
    }

    private fun middle(a: List<String>, b: List<String>, offset: Int, out: MutableList<DiffLine>) {
        if (a.isEmpty() && b.isEmpty()) return
        // old/new line numbers continue from the trimmed head
        var oi = offset
        var ni = offset
        if (a.isEmpty() || b.isEmpty() || a.size.toLong() * b.size > MAX_CELLS) {
            a.forEach { out.add(DiffLine(DiffKind.DEL, it, ++oi, null)) }
            b.forEach { out.add(DiffLine(DiffKind.ADD, it, null, ++ni)) }
            return
        }
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out.add(DiffLine(DiffKind.CTX, a[i], ++oi, ++ni)); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { out.add(DiffLine(DiffKind.DEL, a[i], ++oi, null)); i++ }
                else -> { out.add(DiffLine(DiffKind.ADD, b[j], null, ++ni)); j++ }
            }
        }
        while (i < n) { out.add(DiffLine(DiffKind.DEL, a[i], ++oi, null)); i++ }
        while (j < m) { out.add(DiffLine(DiffKind.ADD, b[j], null, ++ni)); j++ }
    }
}
