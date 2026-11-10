package com.audipp.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Update
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.VariantId

// Partial scans must not turn omitted rows into deletions.
data class LibraryBatch(
    val sources: List<SourceEntity> = emptyList(),
    val tracks: List<TrackEntity> = emptyList(),
    val albums: List<AlbumEntity> = emptyList(),
    val artists: List<ArtistEntity> = emptyList(),
    val variants: List<VariantEntity> = emptyList(),
    val trackArtists: List<TrackArtistEntity> = emptyList(),
)

@Dao
abstract class LibraryDao {
    @Transaction
    open suspend fun save(batch: LibraryBatch) {
        // @Upsert can swallow secondary-unique conflicts by updating zero rows.
        for (source in batch.sources) {
            if (updateSource(source) == 0) insertSource(source)
        }
        upsertTracks(batch.tracks)
        upsertAlbums(batch.albums)
        upsertArtists(batch.artists)
        upsertVariants(batch.variants)
        for (credit in batch.trackArtists) {
            if (updateTrackArtist(credit) == 0) insertTrackArtist(credit)
        }
    }

    @Insert protected abstract suspend fun insertSource(row: SourceEntity)
    @Update protected abstract suspend fun updateSource(row: SourceEntity): Int
    @Insert protected abstract suspend fun insertTrackArtist(row: TrackArtistEntity)
    @Update protected abstract suspend fun updateTrackArtist(row: TrackArtistEntity): Int
    @Upsert protected abstract suspend fun upsertTracks(rows: List<TrackEntity>)
    @Upsert protected abstract suspend fun upsertAlbums(rows: List<AlbumEntity>)
    @Upsert protected abstract suspend fun upsertArtists(rows: List<ArtistEntity>)
    @Upsert protected abstract suspend fun upsertVariants(rows: List<VariantEntity>)

    @Query("SELECT * FROM sources WHERE source_id = :id")
    abstract suspend fun source(id: String): SourceEntity?

    @Query("SELECT * FROM tracks WHERE track_id = :id")
    abstract suspend fun track(id: String): TrackEntity?

    @Query("SELECT * FROM albums WHERE album_id = :id")
    abstract suspend fun album(id: String): AlbumEntity?

    @Query("SELECT * FROM artists WHERE artist_id = :id")
    abstract suspend fun artist(id: String): ArtistEntity?

    @Query("SELECT * FROM track_variants WHERE variant_id = :id")
    abstract suspend fun variant(id: String): VariantEntity?

    @Query("SELECT * FROM track_variants WHERE track_id = :trackId ORDER BY variant_id")
    abstract suspend fun variantsForTrack(trackId: String): List<VariantEntity>

    @Query("SELECT * FROM track_artists WHERE track_id = :trackId ORDER BY role, ordinal")
    abstract suspend fun artistsForTrack(trackId: String): List<TrackArtistEntity>

    @Transaction
    open suspend fun trackVariant(id: VariantId): TrackVariant? {
        val variant = variant(id.value) ?: return null
        return variant.toDomain(checkNotNull(source(variant.sourceId)).toDomain())
    }

    @Query("UPDATE sources SET availability = 'MISSING', updated_at = :updatedAt WHERE source_id = :id")
    protected abstract suspend fun updateMissing(id: String, updatedAt: Long): Int

    suspend fun markSourceMissing(id: String, updatedAt: Long): Boolean {
        require(id.isNotBlank())
        require(updatedAt >= 0)
        return updateMissing(id, updatedAt) == 1
    }
}
