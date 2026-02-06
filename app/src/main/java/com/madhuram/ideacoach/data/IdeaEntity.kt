package com.madhuram.ideacoach.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ideas")
data class IdeaEntity(
    @PrimaryKey val id: String,
    val rawText: String,
    val summary: String,
    val tag: String,
    val nextStep: String,
    val risk: String,
    val audioPath: String?,
    val status: IdeaStatus,
    val transcriptionStatus: TranscriptionStatus,
    val lastTranscriptionError: String?,
    val createdAt: Long
)
