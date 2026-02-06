package com.madhuram.ideacoach.model

import com.madhuram.ideacoach.data.IdeaEntity
import com.madhuram.ideacoach.data.IdeaWithAnswerCount

fun IdeaEntity.toModel(): Idea = Idea(
    id = id,
    rawText = rawText,
    summary = summary,
    tag = tag,
    nextStep = nextStep,
    risk = risk,
    audioPath = audioPath,
    status = status,
    transcriptionStatus = transcriptionStatus,
    lastTranscriptionError = lastTranscriptionError,
    createdAt = createdAt,
    answerCount = 0
)

fun Idea.toEntity(): IdeaEntity = IdeaEntity(
    id = id,
    rawText = rawText,
    summary = summary,
    tag = tag,
    nextStep = nextStep,
    risk = risk,
    audioPath = audioPath,
    status = status,
    transcriptionStatus = transcriptionStatus,
    lastTranscriptionError = lastTranscriptionError,
    createdAt = createdAt
)

fun IdeaWithAnswerCount.toModel(): Idea = idea.toModel().copy(
    answerCount = answerCount
)
