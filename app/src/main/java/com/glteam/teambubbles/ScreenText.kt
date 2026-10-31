package com.glteam.teambubbles

/** A node of the on-screen UI tree, behind an interface so the reading rules are unit-tested. */
interface TextNode {
    val text: CharSequence?
    val description: CharSequence?
    val childCount: Int
    fun child(index: Int): TextNode?
}

/**
 * Reads the text of the other apps' windows (spec §9): depth ≤ 40, ≤ 300 nodes, ≤ 4000 characters,
 * consecutive duplicates removed. Pure logic; ScreenService adapts AccessibilityNodeInfo to [TextNode].
 */
object ScreenText {
    fun collect(roots: List<TextNode>, maxDepth: Int = 40, maxNodes: Int = 300, maxChars: Int = 4000): String {
        val lines = mutableListOf<String>()
        var visited = 0
        var chars = 0

        fun add(s: CharSequence?) {
            val line = s?.toString()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            if (line.isEmpty() || lines.lastOrNull() == line || chars >= maxChars) return
            lines += line
            chars += line.length + 1
        }

        fun walk(node: TextNode, depth: Int) {
            if (depth > maxDepth || visited >= maxNodes || chars >= maxChars) return
            visited++
            add(node.text)
            if (node.text.isNullOrBlank()) add(node.description)
            for (i in 0 until node.childCount) {
                if (visited >= maxNodes || chars >= maxChars) return
                node.child(i)?.let { walk(it, depth + 1) }
            }
        }

        for (r in roots) walk(r, 0)
        return lines.joinToString("\n").take(maxChars)
    }
}

/** Screenshot size for sending (spec §9): longest side ≤ 1024 px, aspect kept, never upscaled. */
object ScreenImage {
    const val MAX_SIDE = 1024
    const val JPEG_QUALITY = 70

    fun fit(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= maxSide || longest <= 0) return width to height
        val scale = maxSide.toDouble() / longest
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }
}

/** What a question carries about the screen. */
data class ScreenCapture(val jpegBase64: String?, val text: String)
