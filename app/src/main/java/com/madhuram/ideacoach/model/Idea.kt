package com.madhuram.ideacoach.model

import com.madhuram.ideacoach.data.IdeaStatus
import com.madhuram.ideacoach.data.TranscriptionStatus

data class Idea(
    val id: String,
    val rawText: String,
    val summary: String,
    val tag: String,
    val nextStep: String,
    val risk: String,
    val audioPath: String?,
    val status: IdeaStatus,
    val transcriptionStatus: TranscriptionStatus,
    val lastTranscriptionError: String?,
    val createdAt: Long,
    val answerCount: Int = 0
)
