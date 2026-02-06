package com.madhuram.ideacoach.transcription

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream

class VoskTranscriber(private val context: Context) {
    companion object {
        const val MODEL_DIR_NAME = "vosk-model-small-en-us-0.15"
        private const val WAV_HEADER_SIZE = 44
        private const val DEFAULT_SAMPLE_RATE = 16000f
    }

    @Volatile
    private var model: Model? = null

    suspend fun transcribe(file: File): Result<String> = withContext(Dispatchers.IO) {
        return@withContext runCatching {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val activeModel = model ?: synchronized(this) {
                model ?: loadModel().also { model = it }
            }

            FileInputStream(file).use { input ->
                val header = ByteArray(WAV_HEADER_SIZE)
                val headerRead = input.read(header)
                val sampleRate = if (headerRead >= WAV_HEADER_SIZE) {
                    extractSampleRate(header) ?: DEFAULT_SAMPLE_RATE
                } else {
                    DEFAULT_SAMPLE_RATE
                }

                val recognizer = Recognizer(activeModel, sampleRate)
                try {
                    val buffer = ByteArray(4096)
                    var bytes = input.read(buffer)
                    while (bytes > 0) {
                        recognizer.acceptWaveForm(buffer, bytes)
                        bytes = input.read(buffer)
                    }
                    val resultJson = recognizer.finalResult
                    JSONObject(resultJson).optString("text").trim()
                } finally {
                    recognizer.close()
                }
            }
        }
    }

    private fun loadModel(): Model {
        val modelDir = File(context.filesDir, MODEL_DIR_NAME)
        if (!modelDir.exists() || modelDir.listFiles().isNullOrEmpty()) {
            copyAssets("models/$MODEL_DIR_NAME", modelDir)
        }
        return Model(modelDir.absolutePath)
    }

    private fun copyAssets(assetPath: String, dest: File) {
        val assets = context.assets.list(assetPath) ?: return
        if (assets.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            if (!dest.exists()) {
                dest.mkdirs()
            }
            for (child in assets) {
                copyAssets("$assetPath/$child", File(dest, child))
            }
        }
    }

    private fun extractSampleRate(header: ByteArray): Float? {
        if (header.size < 28) return null
        val sampleRate = (header[24].toInt() and 0xff) or
            ((header[25].toInt() and 0xff) shl 8) or
            ((header[26].toInt() and 0xff) shl 16) or
            ((header[27].toInt() and 0xff) shl 24)
        return if (sampleRate > 0) sampleRate.toFloat() else null
    }
}
