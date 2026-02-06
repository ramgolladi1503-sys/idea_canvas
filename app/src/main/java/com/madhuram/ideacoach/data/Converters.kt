package com.madhuram.ideacoach.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromStatus(status: IdeaStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): IdeaStatus = IdeaStatus.valueOf(value)

    @TypeConverter
    fun fromTranscriptionStatus(status: TranscriptionStatus): String = status.name

    @TypeConverter
    fun toTranscriptionStatus(value: String): TranscriptionStatus = TranscriptionStatus.valueOf(value)
}
