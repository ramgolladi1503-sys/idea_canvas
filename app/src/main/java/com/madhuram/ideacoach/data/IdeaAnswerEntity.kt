package com.madhuram.ideacoach.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "idea_answers",
    primaryKeys = ["ideaId", "question"],
    foreignKeys = [
        ForeignKey(
            entity = IdeaEntity::class,
            parentColumns = ["id"],
            childColumns = ["ideaId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["ideaId"])]
)
data class IdeaAnswerEntity(
    val ideaId: String,
    val question: String,
    val answer: String,
    val updatedAt: Long
)
