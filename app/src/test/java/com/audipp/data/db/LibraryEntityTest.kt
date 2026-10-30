package com.audipp.data.db

import com.audipp.domain.model.AudioFormat
import com.audipp.domain.model.SourceId
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.SourceRef
import com.audipp.domain.model.TrackId
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.VariantId
import org.junit.Assert.*
import org.junit.Test

class LibraryEntityTest {
    private val source = SourceRef(SourceId("local:one"), SourceKind.LOCAL)
    private val variant = TrackVariant(VariantId("v"), TrackId("t"), source)

    @Test
    fun domainRoundTripPreservesUnknownAndPerVariantAttributes() {
        assertEquals(source, SourceEntity.from(source, 1).toDomain())
        for (value in listOf(variant, variant.copy(durationMs = 300_000, format = AudioFormat("FLAC", 44_100, 16, 2)))) {
            assertEquals(value, VariantEntity.from(value).toDomain(source))
        }
    }

    @Test
    fun invalidIdentifiersAndNumericAttributesAreRejected() {
        val invalid: List<() -> Unit> = listOf(
            { TrackEntity(" ") }, { ArtistEntity("") }, { AlbumEntity("") },
            { AlbumEntity("a", releaseNamespace = "musicbrainz") },
            { SourceEntity.from(source, -1) }, { SourceEntity.from(source, 1, " ") },
            { SourceEntity("provider:p", SourceKind.PROVIDER, updatedAt = 1) },
            { VariantEntity.from(variant, discNo = 0) }, { VariantEntity.from(variant, trackNo = -1) },
            { VariantEntity.from(variant).copy(durationMs = -1) },
            { VariantEntity.from(variant).copy(sampleRate = 0) },
            { VariantEntity.from(variant).copy(bitDepth = 0) },
            { VariantEntity.from(variant).copy(channels = 0) },
            { TrackArtistEntity("t", "a", "", 0) }, { TrackArtistEntity("t", "a", "performer", -1) },
        )
        for (create in invalid) assertThrows(IllegalArgumentException::class.java) { create() }
    }

    @Test
    fun variantCannotBeHydratedFromAnotherSource() {
        assertThrows(IllegalArgumentException::class.java) {
            VariantEntity.from(variant).toDomain(source.copy(sourceId = SourceId("local:other")))
        }
    }
}
