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
