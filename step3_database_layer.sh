#!/bin/sh
set -e

BASE="app/src/main/java/com/example/audiorecorder"

echo "==> 1. Writing RecordingEntity.kt..."
cat << 'ENTITY' > "$BASE/data/db/RecordingEntity.kt"
package com.example.audiorecorder.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val filePath: String,
    val durationMs: Long,
    val sampleRate: Int,
    val bitDepth: Int,
    val channelCount: Int,
    val format: String, // "WAV", "FLAC", "AAC", "MP3", "RAW_FLOAT"
    val fileSize: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val isTrashed: Boolean = false,
    val trashedDate: Long? = null
)
ENTITY

echo "==> 2. Writing RecordingDao.kt..."
cat << 'DAO' > "$BASE/data/db/RecordingDao.kt"
package com.example.audiorecorder.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecording(recording: RecordingEntity): Long

    @Update
    suspend fun updateRecording(recording: RecordingEntity)

    @Delete
    suspend fun deleteRecording(recording: RecordingEntity)

    @Query("SELECT * FROM recordings WHERE isTrashed = 0 ORDER BY createdAt DESC")
    fun getAllActiveRecordingsFlow(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE isTrashed = 0 ORDER BY createdAt DESC")
    suspend fun getAllActiveRecordings(): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE isTrashed = 1 ORDER BY trashedDate DESC")
    fun getTrashedRecordingsFlow(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE id = :id LIMIT 1")
    suspend fun getRecordingById(id: Long): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE isTrashed = 0 AND title LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    fun searchRecordingsFlow(query: String): Flow<List<RecordingEntity>>

    @Query("UPDATE recordings SET isTrashed = 1, trashedDate = :trashedTimestamp WHERE id = :id")
    suspend fun softDelete(id: Long, trashedTimestamp: Long = System.currentTimeMillis())

    @Query("UPDATE recordings SET isTrashed = 1, trashedDate = :trashedTimestamp WHERE id IN (:ids)")
    suspend fun softDeleteMultiple(ids: List<Long>, trashedTimestamp: Long = System.currentTimeMillis())

    @Query("UPDATE recordings SET isTrashed = 0, trashedDate = NULL WHERE id = :id")
    suspend fun restoreFromTrash(id: Long)

    @Query("UPDATE recordings SET isTrashed = 0, trashedDate = NULL WHERE id IN (:ids)")
    suspend fun restoreMultipleFromTrash(ids: List<Long>)

    @Query("DELETE FROM recordings WHERE id IN (:ids)")
    suspend fun deleteMultiple(ids: List<Long>)

    @Query("SELECT * FROM recordings WHERE isTrashed = 1 AND trashedDate <= :thresholdTimestamp")
    suspend fun getExpiredTrashedRecordings(thresholdTimestamp: Long): List<RecordingEntity>
}
DAO

echo "==> 3. Writing AppDatabase.kt..."
cat << 'DATABASE' > "$BASE/data/db/AppDatabase.kt"
package com.example.audiorecorder.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [RecordingEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun recordingDao(): RecordingDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "audio_recorder_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
DATABASE

echo "==> 4. Writing RecordingRepository.kt..."
cat << 'REPO' > "$BASE/data/repository/RecordingRepository.kt"
package com.example.audiorecorder.data.repository

import android.content.Context
import com.example.audiorecorder.data.db.AppDatabase
import com.example.audiorecorder.data.db.RecordingDao
import com.example.audiorecorder.data.db.RecordingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class RecordingRepository(
    private val recordingDao: RecordingDao,
    private val context: Context
) {

    val allActiveRecordings: Flow<List<RecordingEntity>> = recordingDao.getAllActiveRecordingsFlow()
    val trashedRecordings: Flow<List<RecordingEntity>> = recordingDao.getTrashedRecordingsFlow()

    fun searchRecordings(query: String): Flow<List<RecordingEntity>> {
        return recordingDao.searchRecordingsFlow(query)
    }

    suspend fun insertRecording(recording: RecordingEntity): Long = withContext(Dispatchers.IO) {
        recordingDao.insertRecording(recording)
    }

    suspend fun getRecordingById(id: Long): RecordingEntity? = withContext(Dispatchers.IO) {
        recordingDao.getRecordingById(id)
    }

    /**
     * Renames the file on disk (preserving extension) and updates the database row.
     */
    suspend fun renameRecording(id: Long, newTitle: String): Boolean = withContext(Dispatchers.IO) {
        val record = recordingDao.getRecordingById(id) ?: return@withContext false
        val oldFile = File(record.filePath)

        if (!oldFile.exists()) {
            recordingDao.updateRecording(record.copy(title = newTitle))
            return@withContext true
        }

        val extension = oldFile.extension
        val sanitizedTitle = newTitle.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val newFileName = if (extension.isNotEmpty()) "$sanitizedTitle.$extension" else sanitizedTitle
        val parentDir = oldFile.parentFile ?: context.getExternalFilesDir("recordings")
        val newFile = File(parentDir, newFileName)

        val renamed = if (!newFile.exists()) {
            oldFile.renameTo(newFile)
        } else {
            false
        }

        val finalPath = if (renamed) newFile.absolutePath else record.filePath
        recordingDao.updateRecording(record.copy(title = newTitle, filePath = finalPath))
        true
    }

    suspend fun softDelete(id: Long) = withContext(Dispatchers.IO) {
        recordingDao.softDelete(id)
    }

    suspend fun softDeleteMultiple(ids: List<Long>) = withContext(Dispatchers.IO) {
        recordingDao.softDeleteMultiple(ids)
    }

    suspend fun restoreFromTrash(id: Long) = withContext(Dispatchers.IO) {
        recordingDao.restoreFromTrash(id)
    }

    suspend fun restoreMultipleFromTrash(ids: List<Long>) = withContext(Dispatchers.IO) {
        recordingDao.restoreMultipleFromTrash(ids)
    }

    /**
     * Unlinks the physical file from disk and deletes the database entry.
     */
    suspend fun permanentlyDelete(id: Long) = withContext(Dispatchers.IO) {
        val record = recordingDao.getRecordingById(id) ?: return@withContext
        val file = File(record.filePath)
        if (file.exists()) {
            file.delete()
        }
        recordingDao.deleteRecording(record)
    }

    suspend fun permanentlyDeleteMultiple(ids: List<Long>) = withContext(Dispatchers.IO) {
        for (id in ids) {
            val record = recordingDao.getRecordingById(id) ?: continue
            val file = File(record.filePath)
            if (file.exists()) {
                file.delete()
            }
        }
        recordingDao.deleteMultiple(ids)
    }

    /**
     * Purges recordings trashed longer than retentionDays (default: 30 days) from disk and database.
     */
    suspend fun purgeExpiredTrash(retentionDays: Int = 30) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - (retentionDays.toLong() * 24L * 60L * 60L * 1000L)
        val expired = recordingDao.getExpiredTrashedRecordings(cutoff)
        for (record in expired) {
            val file = File(record.filePath)
            if (file.exists()) {
                file.delete()
            }
        }
        recordingDao.deleteMultiple(expired.map { it.id })
    }

    companion object {
        @Volatile
        private var INSTANCE: RecordingRepository? = null

        fun getInstance(context: Context): RecordingRepository {
            return INSTANCE ?: synchronized(this) {
                val db = AppDatabase.getDatabase(context)
                val instance = RecordingRepository(db.recordingDao(), context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
REPO

echo "==> Step 3 database and repository files generated successfully!"
