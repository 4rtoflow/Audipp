package com.audipp.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.audipp.domain.model.AudioFormat
import com.audipp.domain.model.Availability
import com.audipp.domain.model.SourceId
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.SourceRef
import com.audipp.domain.model.TrackId
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.VariantId

@Entity(
    tableName = "sources",
    indices = [Index(value = ["provider_key", "provider_item_id"], unique = true)],
)
data class SourceEntity(
    @PrimaryKey @ColumnInfo(name = "source_id") val sourceId: String,
    val kind: SourceKind,
    @ColumnInfo(name = "provider_key") val providerKey: String? = null,
    @ColumnInfo(name = "provider_item_id") val providerItemId: String? = null,
    @ColumnInfo(name = "local_uri") val localUri: String? = null,
    val availability: Availability = Availability.UNKNOWN,
    @ColumnInfo(name = "grant_mode") val grantMode: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    init {
        toDomain()
        require(grantMode == null || grantMode.isNotBlank())
        require(updatedAt >= 0)
    }

    fun toDomain() = SourceRef(
        SourceId(sourceId), kind, providerKey, providerItemId, localUri, availability,
    )

    companion object {
        fun from(source: SourceRef, updatedAt: Long, grantMode: String? = null) = SourceEntity(
            source.sourceId.value, source.kind, source.providerKey, source.providerItemId,
            source.localDocumentUri, source.availability, grantMode, updatedAt,
        )
    }
}

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey @ColumnInfo(name = "track_id") val trackId: String,
    val title: String? = null,
    @ColumnInfo(name = "artist_display") val artistDisplay: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    @ColumnInfo(name = "artwork_ref") val artworkRef: String? = null,
) {
    init { TrackId(trackId) }
}

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey @ColumnInfo(name = "album_id") val albumId: String,
    val title: String? = null,
    @ColumnInfo(name = "album_artist") val albumArtist: String? = null,
    @ColumnInfo(name = "release_namespace") val releaseNamespace: String? = null,
    @ColumnInfo(name = "release_id") val releaseId: String? = null,
    val year: Int? = null,
    @ColumnInfo(name = "artwork_ref") val artworkRef: String? = null,
) {
    init {
        require(albumId.isNotBlank())
        require((releaseNamespace == null) == (releaseId == null))
        require(releaseNamespace == null || releaseNamespace.isNotBlank())
        require(releaseId == null || releaseId.isNotBlank())
    }
}

@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey @ColumnInfo(name = "artist_id") val artistId: String,
    val name: String? = null,
    @ColumnInfo(name = "external_id") val externalId: String? = null,
) {
    init { require(artistId.isNotBlank()) }
}

@Entity(
    tableName = "track_variants",
    foreignKeys = [
        ForeignKey(entity = TrackEntity::class, parentColumns = ["track_id"], childColumns = ["track_id"]),
        ForeignKey(entity = SourceEntity::class, parentColumns = ["source_id"], childColumns = ["source_id"]),
        ForeignKey(entity = AlbumEntity::class, parentColumns = ["album_id"], childColumns = ["album_id"]),
    ],
    indices = [Index("track_id"), Index("source_id"), Index("album_id")],
)
data class VariantEntity(
    @PrimaryKey @ColumnInfo(name = "variant_id") val variantId: String,
    @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "album_id") val albumId: String? = null,
    @ColumnInfo(name = "disc_no") val discNo: Int? = null,
    @ColumnInfo(name = "track_no") val trackNo: Int? = null,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    val codec: String? = null,
    @ColumnInfo(name = "sample_rate") val sampleRate: Int? = null,
    @ColumnInfo(name = "bit_depth") val bitDepth: Int? = null,
    val channels: Int? = null,
    @ColumnInfo(name = "mastering_label") val masteringLabel: String? = null,
) {
    init {
        VariantId(variantId)
        TrackId(trackId)
        SourceId(sourceId)
        require(albumId == null || albumId.isNotBlank())
        require(discNo == null || discNo > 0)
        require(trackNo == null || trackNo > 0)
        require(durationMs == null || durationMs >= 0)
        AudioFormat(codec, sampleRate, bitDepth, channels)
    }

    fun toDomain(source: SourceRef): TrackVariant {
        require(source.sourceId.value == sourceId)
        return TrackVariant(
            VariantId(variantId), TrackId(trackId), source, durationMs,
            AudioFormat(codec, sampleRate, bitDepth, channels),
        )
    }

    companion object {
        fun from(
            variant: TrackVariant,
            albumId: String? = null,
            discNo: Int? = null,
            trackNo: Int? = null,
            masteringLabel: String? = null,
        ) = VariantEntity(
            variant.variantId.value, variant.trackId.value, variant.source.sourceId.value,
            albumId, discNo, trackNo, variant.durationMs, variant.format.codec,
            variant.format.sampleRateHz, variant.format.bitDepth, variant.format.channelCount,
            masteringLabel,
        )
    }
}

@Entity(
    tableName = "track_artists",
    primaryKeys = ["track_id", "artist_id", "role"],
    foreignKeys = [
        ForeignKey(entity = TrackEntity::class, parentColumns = ["track_id"], childColumns = ["track_id"]),
        ForeignKey(entity = ArtistEntity::class, parentColumns = ["artist_id"], childColumns = ["artist_id"]),
    ],
    indices = [Index("artist_id"), Index(value = ["track_id", "role", "ordinal"], unique = true)],
)
data class TrackArtistEntity(
    @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "artist_id") val artistId: String,
    val role: String,
    val ordinal: Int,
) {
    init {
        TrackId(trackId)
        require(artistId.isNotBlank())
        require(role.isNotBlank())
        require(ordinal >= 0)
    }
}
