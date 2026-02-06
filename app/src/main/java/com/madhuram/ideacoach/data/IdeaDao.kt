package com.madhuram.ideacoach.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface IdeaDao {
    @Query("SELECT * FROM ideas ORDER BY createdAt DESC")
    fun observeIdeas(): Flow<List<IdeaEntity>>

    @Query("SELECT * FROM ideas ORDER BY createdAt DESC")
    suspend fun getIdeas(): List<IdeaEntity>

    @Query("SELECT ideas.*, (SELECT COUNT(*) FROM idea_answers WHERE ideaId = ideas.id AND answer != '') AS answerCount FROM ideas ORDER BY createdAt DESC")
    fun observeIdeasWithAnswerCount(): Flow<List<IdeaWithAnswerCount>>

    @Query("SELECT * FROM ideas WHERE id = :id LIMIT 1")
    fun observeIdea(id: String): Flow<IdeaEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIdea(idea: IdeaEntity)

    @Query("UPDATE ideas SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: IdeaStatus)

    @Query("UPDATE ideas SET transcriptionStatus = :status, lastTranscriptionError = :error WHERE id = :id")
    suspend fun updateTranscriptionStatus(
        id: String,
        status: TranscriptionStatus,
        error: String?
    )

    @Query(
        "UPDATE ideas SET rawText = :rawText, summary = :summary, tag = :tag, " +
            "transcriptionStatus = :status, lastTranscriptionError = :error WHERE id = :id"
    )
    suspend fun updateIdeaTextAndStatus(
        id: String,
        rawText: String,
        summary: String,
        tag: String,
        status: TranscriptionStatus,
        error: String?
    )

    @Query("SELECT * FROM idea_answers WHERE ideaId = :ideaId")
    fun observeAnswers(ideaId: String): Flow<List<IdeaAnswerEntity>>

    @Query("SELECT * FROM idea_answers")
    suspend fun getAllAnswers(): List<IdeaAnswerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAnswer(answer: IdeaAnswerEntity)
}
