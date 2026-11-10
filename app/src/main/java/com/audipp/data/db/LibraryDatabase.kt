package com.audipp.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SourceEntity::class, TrackEntity::class, VariantEntity::class,
        AlbumEntity::class, ArtistEntity::class, TrackArtistEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        fun open(context: Context, name: String = "library.db"): LibraryDatabase = Room.databaseBuilder(
            context.applicationContext, LibraryDatabase::class.java, name,
        ).build()
    }
}
