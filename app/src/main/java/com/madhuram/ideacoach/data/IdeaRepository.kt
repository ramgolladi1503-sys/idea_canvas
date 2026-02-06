package com.madhuram.ideacoach.data

import kotlinx.coroutines.flow.Flow

class IdeaRepository(private val dao: IdeaDao) {
    fun observeIdeas(): Flow<List<IdeaEntity>> = dao.observeIdeas()

    suspend fun getIdeas(): List<IdeaEntity> = dao.getIdeas()

    fun observeIdeasWithAnswerCount(): Flow<List<IdeaWithAnswerCount>> = dao.observeIdeasWithAnswerCount()

    fun observeIdea(id: String): Flow<IdeaEntity?> = dao.observeIdea(id)

    fun observeAnswers(ideaId: String): Flow<List<IdeaAnswerEntity>> = dao.observeAnswers(ideaId)

    suspend fun insertIdea(idea: IdeaEntity) {
        dao.insertIdea(idea)
    }

    suspend fun updateStatus(id: String, status: IdeaStatus) {
        dao.updateStatus(id, status)
    }

    suspend fun updateTranscriptionStatus(id: String, status: TranscriptionStatus, error: String?) {
        dao.updateTranscriptionStatus(id, status, error)
    }

    suspend fun updateIdeaTextAndStatus(
        id: String,
        rawText: String,
        summary: String,
        tag: String,
        status: TranscriptionStatus,
        error: String?
    ) {
        dao.updateIdeaTextAndStatus(id, rawText, summary, tag, status, error)
    }

    suspend fun upsertAnswer(answer: IdeaAnswerEntity) {
        dao.upsertAnswer(answer)
    }

    suspend fun getAllAnswers(): List<IdeaAnswerEntity> = dao.getAllAnswers()
}
