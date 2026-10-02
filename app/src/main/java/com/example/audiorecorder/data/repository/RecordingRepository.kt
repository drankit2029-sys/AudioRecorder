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
