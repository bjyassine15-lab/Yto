package com.example.api

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

// --- Gemini API Models for Moshi ---

data class PrebuiltVoiceConfig(
    @Json(name = "voiceName") val voiceName: String
)

data class VoiceConfig(
    @Json(name = "prebuiltVoiceConfig") val prebuiltVoiceConfig: PrebuiltVoiceConfig
)

data class SpeechConfig(
    @Json(name = "voiceConfig") val voiceConfig: VoiceConfig
)

data class GenerationConfig(
    @Json(name = "responseModalities") val responseModalities: List<String>?,
    @Json(name = "speechConfig") val speechConfig: SpeechConfig?,
    @Json(name = "temperature") val temperature: Float? = null
)

data class InlineData(
    @Json(name = "mimeType") val mimeType: String,
    @Json(name = "data") val data: String
)

data class Part(
    @Json(name = "text") val text: String? = null,
    @Json(name = "inlineData") val inlineData: InlineData? = null
)

data class Content(
    @Json(name = "parts") val parts: List<Part>
)

data class GenerateContentRequest(
    @Json(name = "contents") val contents: List<Content>,
    @Json(name = "generationConfig") val generationConfig: GenerationConfig? = null
)

data class Candidate(
    @Json(name = "content") val content: Content
)

data class GenerateContentResponse(
    @Json(name = "candidates") val candidates: List<Candidate>?
)

// --- Retrofit Interface ---

interface GeminiApiService {
    @POST("v1beta/models/gemini-3.1-flash-tts-preview:generateContent")
    suspend fun generateSpeech(
        @Query("key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse
}

// --- Client Singleton ---

object GeminiClient {
    private const val TAG = "GeminiClient"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val apiService: GeminiApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GeminiApiService::class.java)
    }

    /**
     * Synthesizes text to speech using Gemini's audio capability.
     * Returns a local File containing the synthesized audio, or null if failed.
     */
    suspend fun generateTTS(context: Context, text: String, voiceName: String = "Kore"): File? {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.e(TAG, "Gemini API Key is empty or placeholder! Please configure it in the AI Studio Secrets panel.")
            return null
        }

        try {
            // Construct request payload
            val textPrompt = "Say this clearly: $text"
            val request = GenerateContentRequest(
                contents = listOf(
                    Content(parts = listOf(Part(text = textPrompt)))
                ),
                generationConfig = GenerationConfig(
                    responseModalities = listOf("AUDIO"),
                    speechConfig = SpeechConfig(
                        voiceConfig = VoiceConfig(
                            prebuiltVoiceConfig = PrebuiltVoiceConfig(voiceName = voiceName)
                        )
                    )
                )
            )

            Log.d(TAG, "Requesting TTS for: '$text' using voice: $voiceName")
            val response = apiService.generateSpeech(apiKey, request)

            // Extract the base64 audio data
            val candidates = response.candidates
            if (candidates.isNullOrEmpty()) {
                Log.e(TAG, "No candidates in response")
                return null
            }

            val part = candidates[0].content.parts.firstOrNull { it.inlineData != null }
            if (part == null || part.inlineData == null) {
                Log.e(TAG, "No inlineData found in parts")
                return null
            }

            val base64Data = part.inlineData.data
            val mimeType = part.inlineData.mimeType
            Log.d(TAG, "Received audio payload of type: $mimeType, length: ${base64Data.length}")

            val audioBytes = Base64.decode(base64Data, Base64.DEFAULT)

            // Determine appropriate suffix
            val suffix = when {
                mimeType.contains("mp3") -> ".mp3"
                mimeType.contains("wav") -> ".wav"
                mimeType.contains("aac") -> ".aac"
                mimeType.contains("ogg") || mimeType.contains("opus") -> ".ogg"
                else -> ".mp3" // Default fallback
            }

            // Save to file
            val outputFile = File(context.cacheDir, "gemini_tts_${System.currentTimeMillis()}$suffix")
            FileOutputStream(outputFile).use { fos ->
                fos.write(audioBytes)
            }

            Log.d(TAG, "Successfully saved TTS audio to: ${outputFile.absolutePath}")
            return outputFile

        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate TTS", e)
            return null
        }
    }
}
