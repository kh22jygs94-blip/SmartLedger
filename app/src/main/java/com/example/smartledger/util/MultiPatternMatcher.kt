package com.example.smartledger.util

/**
 * Aho-Corasick 多模式串匹配。
 *
 * 用来替代「关键词列表逐个 contains」：模式串只建一次自动机，
 * 之后不论多少个关键词，匹配一整段文本都只扫一遍，复杂度 O(n + 命中数)。
 * 金额、页面判定这类每帧都要跑的判断，能省掉大量重复全文扫描。
 */
class MultiPatternMatcher(patterns: Collection<String>) {

    private class Node {
        val next = HashMap<Char, Int>(2)
        var fail = 0
        var output = false
    }

    private val nodes = ArrayList<Node>(patterns.size * 2).apply { add(Node()) }

    init {
        patterns.forEach { if (it.isNotEmpty()) insert(it) }
        buildFailureLinks()
    }

    private fun insert(pattern: String) {
        var current = 0
        for (char in pattern) {
            val next = nodes[current].next[char] ?: run {
                val created = nodes.size
                nodes.add(Node())
                nodes[current].next[char] = created
                created
            }
            current = next
        }
        nodes[current].output = true
    }

    /** BFS 建立失配指针：匹配失败时跳到最长的、仍是当前串后缀的前缀 */
    private fun buildFailureLinks() {
        val queue = ArrayDeque<Int>()
        nodes[0].next.values.forEach { child ->
            nodes[child].fail = 0
            queue.addLast(child)
        }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val node = nodes[current]
            node.next.forEach { (char, child) ->
                var fallback = node.fail
                while (fallback != 0 && nodes[fallback].next[char] == null) {
                    fallback = nodes[fallback].fail
                }
                nodes[child].fail = nodes[fallback].next[char] ?: 0
                if (nodes[nodes[child].fail].output) nodes[child].output = true
                queue.addLast(child)
            }
        }
    }

    fun containsAny(text: String): Boolean {
        var current = 0
        for (char in text) {
            while (current != 0 && nodes[current].next[char] == null) {
                current = nodes[current].fail
            }
            current = nodes[current].next[char] ?: 0
            if (nodes[current].output) return true
        }
        return false
    }
}
