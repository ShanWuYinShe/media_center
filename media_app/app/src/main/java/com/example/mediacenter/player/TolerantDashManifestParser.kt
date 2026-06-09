package com.example.mediacenter.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.ByteArrayInputStream
import java.io.InputStream

@OptIn(UnstableApi::class)
class TolerantDashManifestParser(
    private val delegate: DashManifestParser = DashManifestParser(),
) : ParsingLoadable.Parser<DashManifest> {
    override fun parse(uri: Uri, inputStream: InputStream): DashManifest {
        val originalBytes = inputStream.readBytes()
        val originalManifest = String(originalBytes, Charsets.UTF_8)
        val normalizedManifest = normalizeAdaptationSetIds(originalManifest)
        val normalizedInput = if (normalizedManifest == originalManifest) {
            ByteArrayInputStream(originalBytes)
        } else {
            normalizedManifest.byteInputStream(Charsets.UTF_8)
        }
        return delegate.parse(uri, normalizedInput)
    }

    private fun normalizeAdaptationSetIds(manifest: String): String {
        val usedNumericIds = AdaptationSetStartTag.findAll(manifest)
            .mapNotNull { tagMatch ->
                IdAttribute.find(tagMatch.value)
                    ?.groupValues
                    ?.getOrNull(IdValueGroup)
                    ?.toLongOrNull()
            }
            .toMutableSet()
        var nextId = 0L

        fun nextUnusedId(): Long {
            while (nextId in usedNumericIds) {
                nextId++
            }
            usedNumericIds += nextId
            return nextId++
        }

        return AdaptationSetStartTag.replace(manifest) { tagMatch ->
            val idMatch = IdAttribute.find(tagMatch.value) ?: return@replace tagMatch.value
            val idValue = idMatch.groupValues[IdValueGroup]
            if (idValue.toLongOrNull() != null) {
                tagMatch.value
            } else {
                val leadingWhitespace = idMatch.groupValues[LeadingWhitespaceGroup]
                val quote = idMatch.groupValues[QuoteGroup]
                val replacement = "$leadingWhitespace$idAttributeName=$quote${nextUnusedId()}$quote"
                tagMatch.value.replaceRange(idMatch.range, replacement)
            }
        }
    }

    private companion object {
        private const val idAttributeName = "id"
        private const val LeadingWhitespaceGroup = 1
        private const val QuoteGroup = 2
        private const val IdValueGroup = 3
        private val AdaptationSetStartTag = Regex("<(?:[\\w.-]+:)?AdaptationSet\\b[^>]*>")
        private val IdAttribute = Regex("""(\s)id\s*=\s*(["'])([^"']*)\2""")
    }
}
