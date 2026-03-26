package com.clawdroid.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume

/**
 * VoiceEngine
 *
 * Text-to-Speech (TTS) + Speech-to-Text (STT)
 *
 * TTS options:
 *  1. ElevenLabs API (high quality, needs API key)
 *  2. Android system TTS (offline fallback, always available)
 *
 * STT options:
 *  1. Android SpeechRecognizer (uses Google, requires internet)
 *  2. Future: Whisper local (Vosk)
 *
 * Like OpenClaw: ElevenLabs when configured, system TTS fallback.
 */
class VoiceEngine(private val context: Context) {

    companion object {
        private const val TAG = "VoiceEngine"
        private const val ELEVENLABS_BASE_URL = "https://api.elevenlabs.io/v1"
    }

    // Config
    var elevenLabsApiKey: String = ""
    var elevenLabsVoiceId: String = "21m00Tcm4TlvDq8ikWAM" // default: Rachel

    // State
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    // System TTS
    private var systemTts: TextToSpeech? = null
    private var systemTtsReady = false

    // Speech Recognizer
    private var speechRecognizer: SpeechRecognizer? = null

    // ──────────────────────────────────────────────
    // Init
    // ──────────────────────────────────────────────

    fun initialize() {
        initSystemTts()
        initSpeechRecognizer()
    }

    private fun initSystemTts() {
        systemTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                systemTts?.language = Locale.US
                systemTtsReady = true
                Log.d(TAG, "System TTS ready")
            } else {
                Log.e(TAG, "System TTS init failed: $status")
            }
        }
    }

    private fun initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            Log.d(TAG, "SpeechRecognizer initialized")
        } else {
            Log.w(TAG, "Speech recognition not available on this device")
        }
    }

    // ──────────────────────────────────────────────
    // SPEAK (TTS)
    // ──────────────────────────────────────────────

    suspend fun speak(text: String) {
        if (elevenLabsApiKey.isNotBlank()) {
            speakElevenLabs(text)
        } else {
            speakSystem(text)
        }
    }

    private suspend fun speakElevenLabs(text: String) = withContext(Dispatchers.IO) {
        _isSpeaking.value = true
        try {
            val url = URL("$ELEVENLABS_BASE_URL/text-to-speech/$elevenLabsVoiceId")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("xi-api-key", elevenLabsApiKey)
                setRequestProperty("Accept", "audio/mpeg")
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
            }

            val body = JSONObject().apply {
                put("text", text.take(2500)) // ElevenLabs limit
                put("model_id", "eleven_flash_v2_5") // fast model
                put("voice_settings", JSONObject().apply {
                    put("stability", 0.5)
                    put("similarity_boost", 0.75)
                })
            }
            OutputStreamWriter(connection.outputStream).use { it.write(body.toString()) }

            if (connection.responseCode == 200) {
                val audioBytes = connection.inputStream.readBytes()
                playAudioBytes(audioBytes)
            } else {
                Log.w(TAG, "ElevenLabs failed (${connection.responseCode}), falling back to system TTS")
                speakSystem(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "ElevenLabs error, falling back to system TTS", e)
            speakSystem(text)
        } finally {
            _isSpeaking.value = false
        }
    }

    private suspend fun speakSystem(text: String) = suspendCancellableCoroutine<Unit> { cont ->
        _isSpeaking.value = true
        val tts = systemTts
        if (tts == null || !systemTtsReady) {
            _isSpeaking.value = false
            cont.resume(Unit)
            return@suspendCancellableCoroutine
        }

        val utteranceId = "CLAW_${System.currentTimeMillis()}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                _isSpeaking.value = false
                if (cont.isActive) cont.resume(Unit)
            }
            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
                if (cont.isActive) cont.resume(Unit)
            }
        })

        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun playAudioBytes(audioBytes: ByteArray) {
        // Play using MediaPlayer
        try {
            val tempFile = java.io.File.createTempFile("elevenlabs_", ".mp3", context.cacheDir)
            tempFile.writeBytes(audioBytes)

            val player = android.media.MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    tempFile.delete()
                    release()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio", e)
        }
    }

    fun stopSpeaking() {
        systemTts?.stop()
        _isSpeaking.value = false
    }

    // ──────────────────────────────────────────────
    // LISTEN (STT)
    // ──────────────────────────────────────────────

    suspend fun listen(
        language: String = "en-US",
        maxResults: Int = 1
    ): String = suspendCancellableCoroutine { cont ->
        val recognizer = speechRecognizer
        if (recognizer == null) {
            cont.resume("ERROR: Speech recognition not available")
            return@suspendCancellableCoroutine
        }

        _isListening.value = true

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxResults)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                _isListening.value = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                if (cont.isActive) cont.resume(text)
            }

            override fun onError(error: Int) {
                _isListening.value = false
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                    else -> "Unknown error $error"
                }
                if (cont.isActive) cont.resume("ERROR: $errorMsg")
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer.startListening(intent)

        cont.invokeOnCancellation {
            recognizer.cancel()
            _isListening.value = false
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        _isListening.value = false
    }

    // ──────────────────────────────────────────────
    // Cleanup
    // ──────────────────────────────────────────────

    fun release() {
        systemTts?.stop()
        systemTts?.shutdown()
        speechRecognizer?.destroy()
        _isSpeaking.value = false
        _isListening.value = false
    }
}
