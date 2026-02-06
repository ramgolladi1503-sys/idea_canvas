package com.madhuram.ideacoach.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.max

class AudioRecorder(private val context: Context) {
    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val WAV_HEADER_SIZE = 44
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var outputFile: File? = null
    @Volatile private var isRecording = false
    @Volatile private var dataSize: Long = 0

    fun start(): File {
        stopSafely()

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = max(minBuffer, SAMPLE_RATE)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord init failed")
        }

        val recordingsDir = File(context.filesDir, "recordings").apply { mkdirs() }
        val file = File(recordingsDir, "idea-${System.currentTimeMillis()}.wav")
        val outputStream = FileOutputStream(file)
        writeWavHeader(outputStream, 0)
        outputStream.flush()

        dataSize = 0
        isRecording = true
        audioRecord = record
        outputFile = file

        record.startRecording()

        recordingThread = Thread {
            val buffer = ByteArray(bufferSize)
            try {
                while (isRecording) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        outputStream.write(buffer, 0, read)
                        dataSize += read
                    }
                }
            } finally {
                try {
                    outputStream.flush()
                } catch (_: Exception) {
                }
                try {
                    outputStream.close()
                } catch (_: Exception) {
                }
            }
        }.apply { start() }

        return file
    }

    fun stop(): File? {
        val record = audioRecord ?: return outputFile
        isRecording = false
        try {
            record.stop()
        } catch (_: Exception) {
        }
        try {
            recordingThread?.join(1000)
        } catch (_: Exception) {
        }
        record.release()
        audioRecord = null
        recordingThread = null

        val file = outputFile
        if (file != null) {
            try {
                updateWavHeader(file, dataSize)
            } catch (_: Exception) {
            }
        }
        return file
    }

    fun release() {
        stopSafely()
    }

    private fun stopSafely() {
        if (audioRecord == null) return
        try {
            stop()
        } catch (_: Exception) {
        }
    }

    private fun writeWavHeader(out: FileOutputStream, dataLength: Long) {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val totalDataLen = dataLength + 36

        out.write("RIFF".toByteArray())
        out.write(intToLeBytes(totalDataLen.toInt()))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(intToLeBytes(16))
        out.write(shortToLeBytes(1))
        out.write(shortToLeBytes(CHANNELS.toShort()))
        out.write(intToLeBytes(SAMPLE_RATE))
        out.write(intToLeBytes(byteRate))
        out.write(shortToLeBytes((CHANNELS * BITS_PER_SAMPLE / 8).toShort()))
        out.write(shortToLeBytes(BITS_PER_SAMPLE.toShort()))
        out.write("data".toByteArray())
        out.write(intToLeBytes(dataLength.toInt()))
    }

    private fun updateWavHeader(file: File, dataLength: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.write(intToLeBytes((dataLength + 36).toInt()))
            raf.seek(40)
            raf.write(intToLeBytes(dataLength.toInt()))
        }
    }

    private fun intToLeBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 24) and 0xff).toByte()
        )
    }

    private fun shortToLeBytes(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() and 0xff).toByte(),
            ((value.toInt() shr 8) and 0xff).toByte()
        )
    }
}
