package com.offlinecyberdeck.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.offlinecyberdeck.core.database.dao.ArchiveDao
import com.offlinecyberdeck.core.database.dao.BookmarkDao
import com.offlinecyberdeck.core.database.dao.DownloadDao
import com.offlinecyberdeck.core.database.dao.HistoryDao
import com.offlinecyberdeck.core.database.dao.LibraryDao
import com.offlinecyberdeck.core.database.dao.MediaDao
import com.offlinecyberdeck.core.database.dao.RecentSearchDao
import com.offlinecyberdeck.core.database.dao.SettingsDao
import com.offlinecyberdeck.core.database.dao.SiteSettingDao
import com.offlinecyberdeck.core.database.dao.TabDao
import com.offlinecyberdeck.core.database.entity.AppSettingsEntity
import com.offlinecyberdeck.core.database.entity.ArchivedPageEntity
import com.offlinecyberdeck.core.database.entity.BookmarkEntity
import com.offlinecyberdeck.core.database.entity.DownloadEntity
import com.offlinecyberdeck.core.database.entity.HistoryEntity
import com.offlinecyberdeck.core.database.entity.LibraryItemEntity
import com.offlinecyberdeck.core.database.entity.MediaItemEntity
import com.offlinecyberdeck.core.database.entity.PlaylistEntity
import com.offlinecyberdeck.core.database.entity.PlaylistMediaCrossRef
import com.offlinecyberdeck.core.database.entity.RecentSearchEntity
import com.offlinecyberdeck.core.database.entity.SiteSettingEntity
import com.offlinecyberdeck.core.database.entity.TabEntity
import com.offlinecyberdeck.core.model.ArchiveStatus
import com.offlinecyberdeck.core.model.DownloadStatus
import com.offlinecyberdeck.core.model.DuplicatePolicy
import com.offlinecyberdeck.core.model.LibraryCategory
import com.offlinecyberdeck.core.model.LibraryFileType
import com.offlinecyberdeck.core.model.SearchEngineOption
import com.offlinecyberdeck.core.model.StorageTarget
import com.offlinecyberdeck.core.model.ThemeMode

class CyberDeckConverters {
    @TypeConverter fun fromThemeMode(v: ThemeMode): String = v.name
    @TypeConverter fun toThemeMode(v: String): ThemeMode = enumValueOrDefault(v, ThemeMode.DARK)

    @TypeConverter fun fromSearchEngine(v: SearchEngineOption): String = v.name
    @TypeConverter fun toSearchEngine(v: String): SearchEngineOption = enumValueOrDefault(v, SearchEngineOption.DUCKDUCKGO)

    @TypeConverter fun fromArchiveStatus(v: ArchiveStatus): String = v.name
    @TypeConverter fun toArchiveStatus(v: String): ArchiveStatus = enumValueOrDefault(v, ArchiveStatus.READER_SNAPSHOT)

    @TypeConverter fun fromStorageTarget(v: StorageTarget): String = v.name
    @TypeConverter fun toStorageTarget(v: String): StorageTarget = enumValueOrDefault(v, StorageTarget.INTERNAL)

    @TypeConverter fun fromDownloadStatus(v: DownloadStatus): String = v.name
    @TypeConverter fun toDownloadStatus(v: String): DownloadStatus = enumValueOrDefault(v, DownloadStatus.FAILED)

    @TypeConverter fun fromDuplicatePolicy(v: DuplicatePolicy): String = v.name
    @TypeConverter fun toDuplicatePolicy(v: String): DuplicatePolicy = enumValueOrDefault(v, DuplicatePolicy.RENAME)

    @TypeConverter fun fromLibraryCategory(v: LibraryCategory): String = v.name
    @TypeConverter fun toLibraryCategory(v: String): LibraryCategory = enumValueOrDefault(v, LibraryCategory.BOOKS)

    @TypeConverter fun fromLibraryFileType(v: LibraryFileType): String = v.name
    @TypeConverter fun toLibraryFileType(v: String): LibraryFileType = enumValueOrDefault(v, LibraryFileType.OTHER)

    private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String, default: T): T =
        runCatching { enumValueOf<T>(name) }.getOrDefault(default)
}

@Database(
    entities = [
        TabEntity::class,
        BookmarkEntity::class,
        HistoryEntity::class,
        DownloadEntity::class,
        ArchivedPageEntity::class,
        LibraryItemEntity::class,
        MediaItemEntity::class,
        PlaylistEntity::class,
        PlaylistMediaCrossRef::class,
        SiteSettingEntity::class,
        RecentSearchEntity::class,
        AppSettingsEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(CyberDeckConverters::class)
abstract class CyberDeckDatabase : RoomDatabase() {
    abstract fun tabDao(): TabDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun archiveDao(): ArchiveDao
    abstract fun libraryDao(): LibraryDao
    abstract fun mediaDao(): MediaDao
    abstract fun siteSettingDao(): SiteSettingDao
    abstract fun recentSearchDao(): RecentSearchDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        @Volatile
        private var INSTANCE: CyberDeckDatabase? = null

        fun getInstance(context: Context): CyberDeckDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CyberDeckDatabase::class.java,
                    "cyber_deck.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
