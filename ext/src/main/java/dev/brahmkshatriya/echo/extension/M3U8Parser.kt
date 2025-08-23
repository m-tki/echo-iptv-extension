package dev.brahmkshatriya.echo.extension

data class M3U8Entry(
    val group: String? = null,
    val tvgName: String,
    val tvgId: String,
    val tvgLogo: String,
    val groupTitle: String,
    val radio: Boolean,
    val title: String,
    val url: String
)

class M3U8Parser {
    fun parse(content: String): List<M3U8Entry> {
        val entries = mutableListOf<M3U8Entry>()
        val lines = content.lines()

        var currentGroup: String? = null
        var i = 0

        while (i < lines.size) {
            val line = lines[i].trim()

            when {
                line.startsWith("#EXTGRP:") -> {
                    currentGroup = line.removePrefix("#EXTGRP:").trim()
                    i++
                }

                line.startsWith("#EXTINF:") -> {
                    val extinfLine = line
                    while (lines.getOrNull(i + 1)?.trim()?.startsWith("#") == true) {
                        i++
                    }
                    val urlLine = lines.getOrNull(i + 1)?.trim()

                    if (urlLine != null) {
                        val entry = parseExtInfLine(extinfLine, urlLine, currentGroup)
                        entries.add(entry)
                        i += 2
                    } else {
                        i++
                    }
                }

                else -> i++
            }
        }

        return entries
    }

    private fun parseExtInfLine(extinfLine: String, url: String, group: String?): M3U8Entry {
        // Find the last comma that separates attributes from title
        // We need to find the comma that's NOT inside quoted values
        var commaIndex = -1
        var inQuotes = false

        for ((index, char) in extinfLine.withIndex()) {
            when (char) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) {
                    commaIndex = index
                    break
                }
            }
        }

        val title = if (commaIndex != -1) {
            extinfLine.substring(commaIndex + 1).trim()
        } else {
            ""
        }

        // Extract attributes part (everything between #EXTINF: and the separating comma)
        val attributesPart = if (commaIndex != -1) {
            extinfLine.substring(8, commaIndex).trim() // Remove "#EXTINF:"
        } else {
            extinfLine.removePrefix("#EXTINF:").trim()
        }

        // Parse attributes handling quoted values with commas
        val attributes = parseAttributes(attributesPart)

        return M3U8Entry(
            group = group,
            tvgName = attributes["tvg-name"] ?: "",
            tvgId = attributes["tvg-id"] ?: "",
            tvgLogo = attributes["tvg-logo"] ?: "",
            groupTitle = attributes["group-title"] ?: "",
            radio = attributes["radio"]?.toBooleanStrictOrNull() ?: false,
            title = title,
            url = url
        )
    }

    private fun parseAttributes(attributesPart: String): Map<String, String> {
        val attributes = mutableMapOf<String, String>()
        var currentIndex = 0
        val length = attributesPart.length

        while (currentIndex < length) {
            // Skip whitespace
            while (currentIndex < length && attributesPart[currentIndex].isWhitespace()) {
                currentIndex++
            }

            if (currentIndex >= length) break

            // Find key
            val keyStart = currentIndex
            while (currentIndex < length && attributesPart[currentIndex] != '=' &&
                !attributesPart[currentIndex].isWhitespace()
            ) {
                currentIndex++
            }
            val key = attributesPart.substring(keyStart, currentIndex)

            // Skip to equals sign
            while (currentIndex < length && attributesPart[currentIndex] != '=') {
                currentIndex++
            }

            if (currentIndex >= length || attributesPart[currentIndex] != '=') {
                // Invalid format, skip this attribute
                break
            }

            currentIndex++ // Skip '='

            // Skip whitespace after equals
            while (currentIndex < length && attributesPart[currentIndex].isWhitespace()) {
                currentIndex++
            }

            if (currentIndex >= length) break

            // Find value (quoted or unquoted)
            val value: String
            if (attributesPart[currentIndex] == '"') {
                currentIndex++ // Skip opening quote
                val valueStart = currentIndex

                // Find closing quote, handling escaped quotes if necessary
                while (currentIndex < length && attributesPart[currentIndex] != '"') {
                    currentIndex++
                }

                value = attributesPart.substring(valueStart, currentIndex)
                currentIndex++ // Skip closing quote
            } else {
                // Unquoted value (ends at whitespace or end of string)
                val valueStart = currentIndex
                while (currentIndex < length && !attributesPart[currentIndex].isWhitespace()) {
                    currentIndex++
                }
                value = attributesPart.substring(valueStart, currentIndex)
            }

            attributes[key] = value

            // Skip to next attribute
            while (currentIndex < length && attributesPart[currentIndex].isWhitespace()) {
                currentIndex++
            }
        }

        return attributes
    }
}