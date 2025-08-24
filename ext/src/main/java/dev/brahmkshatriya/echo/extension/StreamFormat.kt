package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

class StreamFormat(private val client: OkHttpClient) {
    suspend fun detectStreamFormat(url: String): String = withContext(Dispatchers.IO) {
        try {
            // Check content type via HEAD request
            val (contentType, statusCode) = callHead(url)
            if (statusCode == 200 && contentType != null) {
                when {
                    isHlsContentType(contentType) -> return@withContext "HLS"
                    isDashContentType(contentType) -> return@withContext "DASH"
                }
            }

            // Check content via partial download
            val partialContent = callPartial(url)
            when {
                isHlsByContent(partialContent) -> "HLS"
                isDashByContent(partialContent) -> "DASH"
                else -> "UNKNOWN"
            }
        } catch (_: Exception) {
            "ERROR"
        }
    }

    // Network operations
    private suspend fun callHead(url: String): Pair<String?, Int> = withContext(Dispatchers.IO) {
        val response = client.newCall(
            Request.Builder()
                .url(url)
                .head()
                .build()
        ).await()

        Pair(response.header("Content-Type"), response.code)
    }

    private suspend fun callPartial(url: String, maxBytes: Int = 256): String = withContext(Dispatchers.IO) {
        val response = client.newCall(
            Request.Builder().url(url).build()
        ).await()

        val source = response.body.source()
        val buffer = Buffer()
        source.request(maxBytes.toLong())
        source.read(buffer, maxBytes.toLong())

        buffer.readString(Charsets.UTF_8)
    }

    // Content type detection
    private fun isHlsContentType(contentType: String): Boolean {
        val lowerType = contentType.lowercase()
        return lowerType.contains("mpegurl") ||
                lowerType.contains("m3u")
    }

    private fun isDashContentType(contentType: String): Boolean {
        val lowerType = contentType.lowercase()
        return lowerType.contains("dash") ||
                lowerType.contains("mpd")
    }

    // Content detection
    private fun isHlsByContent(content: String): Boolean {
        if (content.isBlank()) return false
        val firstFewLines = content.lines().take(5).joinToString("\n")
        return firstFewLines.contains("#EXTM3U") ||
                firstFewLines.contains("#EXT-X-VERSION") ||
                firstFewLines.contains("#EXT-X-STREAM-INF") ||
                firstFewLines.contains("#EXTINF")
    }

    private fun isDashByContent(content: String): Boolean {
        if (content.isBlank()) return false
        val firstFewLines = content.lines().take(5).joinToString("\n")
        return firstFewLines.contains("MPD") ||
                firstFewLines.contains("xmlns=\"urn:mpeg:dash:schema:mpd:")
    }
}

class StreamFormatByExtension() {
    companion object {
        fun isTransportStreamByExtension(url: String): Boolean {
            return url.contains(".ts", ignoreCase = true)
        }

        fun isHlsByExtension(url: String): Boolean {
            val hlsExtensions = listOf(".m3u8", ".m3u")
            return hlsExtensions.any { url.contains(it, ignoreCase = true) }
        }

        fun isDashByExtension(url: String): Boolean {
            val dashExtensions = listOf(".mpd", ".dash")
            return dashExtensions.any { url.contains(it, ignoreCase = true) }
        }
    }
}