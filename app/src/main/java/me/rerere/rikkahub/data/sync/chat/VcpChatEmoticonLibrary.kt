package me.rerere.rikkahub.data.sync.chat

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable

@Serializable
data class VcpChatEmoticonItem(
    val url: String,
    val category: String = "",
    val filename: String = "",
    val searchKey: String = "",
)

@Serializable
data class VcpChatEmoticonLibrarySnapshot(
    val sourceApp: String = CHAT_SYNC_APP_VCPCHAT,
    val appDataDir: String? = null,
    val generatedAt: Long? = null,
    val syncedAt: Long = 0L,
    val count: Int = 0,
    val degradedReason: String? = null,
    val items: List<VcpChatEmoticonItem> = emptyList(),
)

object VcpChatEmoticonLibraryRegistry {
    @Volatile
    private var snapshot: VcpChatEmoticonLibrarySnapshot = VcpChatEmoticonLibrarySnapshot()

    fun update(next: VcpChatEmoticonLibrarySnapshot) {
        snapshot = next.copy(count = next.items.size)
    }

    fun current(): VcpChatEmoticonLibrarySnapshot = snapshot

    fun fixUrl(source: String?): String? {
        return VcpChatEmoticonUrlFixer.fix(source, snapshot.items)
    }
}

internal object VcpChatEmoticonUrlFixer {
    fun fix(source: String?, library: List<VcpChatEmoticonItem>): String? {
        val original = source?.trim() ?: return source
        if (original.isBlank() || library.isEmpty()) return source

        val decodedOriginal = decode(original)
        if (library.any { decode(it.url) == decodedOriginal }) {
            return original
        }
        if (!decodedOriginal.contains("表情包")) {
            return original
        }

        val searchInfo = extractInfo(original)
        val searchFilename = searchInfo.filename ?: return original
        var bestMatch: VcpChatEmoticonItem? = null
        var highestScore = -1.0

        library.forEach { item ->
            val packageScore = when {
                searchInfo.packageName != null -> similarity(searchInfo.packageName, item.category)
                item.category.isEmpty() -> 1.0
                else -> 0.5
            }
            val filenameScore = similarity(searchFilename, item.filename)
            val score = (0.7 * packageScore) + (0.3 * filenameScore)
            if (score > highestScore) {
                highestScore = score
                bestMatch = item
            }
        }

        return bestMatch?.url?.takeIf { highestScore > 0.6 } ?: original
    }

    internal fun extractInfo(source: String): EmoticonInfo {
        val decoded = decode(source)
            .substringBefore('?')
            .substringBefore('#')
        val path = when {
            "/images/" in decoded -> decoded.substringAfter("/images/")
            else -> decoded
        }
        val parts = path.split('/').filter { it.isNotBlank() }
        return EmoticonInfo(
            filename = parts.lastOrNull(),
            packageName = parts.getOrNull(parts.size - 2),
        )
    }

    internal fun similarity(first: String, second: String): Double {
        val left = first.lowercase()
        val right = second.lowercase()
        val maxLength = maxOf(left.length, right.length)
        if (maxLength == 0) return 1.0
        return (maxLength - editDistance(left, right)).toDouble() / maxLength.toDouble()
    }

    private fun editDistance(first: String, second: String): Int {
        if (first.isEmpty()) return second.length
        if (second.isEmpty()) return first.length

        val previous = IntArray(second.length + 1) { it }
        val current = IntArray(second.length + 1)
        for (i in first.indices) {
            current[0] = i + 1
            for (j in second.indices) {
                val cost = if (first[i] == second[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost,
                )
            }
            for (j in previous.indices) {
                previous[j] = current[j]
            }
        }
        return previous[second.length]
    }

    private fun decode(value: String): String {
        return runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.getOrDefault(value)
    }

    internal data class EmoticonInfo(
        val filename: String?,
        val packageName: String?,
    )
}
