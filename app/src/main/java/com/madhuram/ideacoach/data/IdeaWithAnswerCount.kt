package com.madhuram.ideacoach.data

import androidx.room.ColumnInfo
import androidx.room.Embedded

data class IdeaWithAnswerCount(
    @Embedded val idea: IdeaEntity,
    @ColumnInfo(name = "answerCount") val answerCount: Int
)
