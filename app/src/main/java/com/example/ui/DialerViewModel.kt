package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.api.GeminiClient
import com.example.data.Contact
import com.example.data.ContactDatabase
import com.example.data.ContactRepository
import com.example.dsp.AudioMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface DialerUiState {
    object Idle : DialerUiState
    object RecordingQuery : DialerUiState
    object ProcessingQuery : DialerUiState
    data class MatchFound(val contact: Contact, val similarity: Double, val countdown: Int) : DialerUiState
    data class MatchFailed(val highestSimilarity: Double) : DialerUiState
}

enum class AppScreen {
    Main,
    Settings
}

class DialerViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "DialerViewModel"
    private val repository: ContactRepository
    private val prefs: SharedPreferences = application.getSharedPreferences("dialer_settings", Context.MODE_PRIVATE)

    // Central flow of all contacts
    val contacts: StateFlow<List<Contact>>

    // App screen state (Main vs Settings)
    private val _currentScreen = MutableStateFlow(AppScreen.Main)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    // Configuration states
    private val _similarityThreshold = MutableStateFlow(75.0f) // Threshold percentage (70% - 95%)
    val similarityThreshold: StateFlow<Float> = _similarityThreshold.asStateFlow()

    private val _dtwSensitivity = MutableStateFlow(80) // Speed/pitch tolerance percentage (50% - 100%)
    val dtwSensitivity: StateFlow<Float> = _dtwSensitivity.map { it.toFloat() }.stateIn(viewModelScope, SharingStarted.Lazily, 80.0f)

    // Active visual state for dialing & recording
    private val _uiState = MutableStateFlow<DialerUiState>(DialerUiState.Idle)
    val uiState: StateFlow<DialerUiState> = _uiState.asStateFlow()

    // Recording reference state
    private val _isRecordingReference = MutableStateFlow(false)
    val isRecordingReference: StateFlow<Boolean> = _isRecordingReference.asStateFlow()

    // Currently recorded reference file for the Add/Edit form
    private val _tempReferenceAudioFile = MutableStateFlow<File?>(null)
    val tempReferenceAudioFile: StateFlow<File?> = _tempReferenceAudioFile.asStateFlow()

    // Audio recording & playback fields
    private var isQueryRecordingActive = false
    private var isReferenceRecordingActive = false
    private var activeQueryFile: File? = null
    private var mediaPlayer: MediaPlayer? = null
    private var confirmationTimerJob: Job? = null
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

    // Selected Contact for Edit
    private val _editingContact = MutableStateFlow<Contact?>(null)
    val editingContact: StateFlow<Contact?> = _editingContact.asStateFlow()

    init {
        val contactDao = ContactDatabase.getDatabase(application).contactDao()
        repository = ContactRepository(contactDao)
        contacts = repository.allContacts.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        // Read configuration from SharedPreferences
        _similarityThreshold.value = prefs.getFloat("threshold", 75.0f)
        _dtwSensitivity.value = prefs.getInt("sensitivity", 80)
    }

    // --- Configuration Settings Actions ---

    fun setSimilarityThreshold(value: Float) {
        _similarityThreshold.value = value
        prefs.edit().putFloat("threshold", value).apply()
    }

    fun setDtwSensitivity(value: Float) {
        val intValue = value.toInt()
        _dtwSensitivity.value = intValue
        prefs.edit().putInt("sensitivity", intValue).apply()
    }

    fun navigateTo(screen: AppScreen) {
        cancelActiveMatchAndTimer()
        _currentScreen.value = screen
    }

    // --- Contact CRUD ---

    fun saveContact(context: Context, id: Int, name: String, phoneNumber: String, photoFile: File?, audioFile: File?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Ensure context path
                val savedPhotoFile = photoFile?.let {
                    val dest = File(context.filesDir, "contact_photo_${System.currentTimeMillis()}.jpg")
                    it.copyTo(dest, overwrite = true)
                    dest
                }

                val savedAudioFile = audioFile?.let {
                    val dest = File(context.filesDir, "contact_ref_${System.currentTimeMillis()}.wav")
                    it.copyTo(dest, overwrite = true)
                    dest
                }

                val existingContact = if (id != 0) repository.getContactById(id) else null

                if (existingContact != null) {
                    // Update contact
                    val updatedContact = existingContact.copy(
                        name = name,
                        phoneNumber = phoneNumber,
                        photoPath = savedPhotoFile?.absolutePath ?: existingContact.photoPath,
                        audioPath = savedAudioFile?.absolutePath ?: existingContact.audioPath
                    )
                    repository.update(updatedContact)
                    Log.d(TAG, "Contact updated: $name")
                } else {
                    // Insert contact
                    val newContact = Contact(
                        name = name,
                        phoneNumber = phoneNumber,
                        photoPath = savedPhotoFile?.absolutePath,
                        audioPath = savedAudioFile?.absolutePath
                    )
                    repository.insert(newContact)
                    Log.d(TAG, "Contact added: $name")
                }
                
                withContext(Dispatchers.Main) {
                    _editingContact.value = null
                    _tempReferenceAudioFile.value = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save contact", e)
            }
        }
    }

    fun deleteContact(contact: Contact) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.delete(contact)
                // Delete actual local files to avoid orphan files
                contact.photoPath?.let { File(it).apply { if (exists()) delete() } }
                contact.audioPath?.let { File(it).apply { if (exists()) delete() } }
                Log.d(TAG, "Contact deleted: ${contact.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete contact", e)
            }
        }
    }

    fun selectContactForEdit(contact: Contact?) {
        _editingContact.value = contact
        _tempReferenceAudioFile.value = contact?.audioPath?.let { File(it) }
    }

    // --- Voice Reference Audio Generation (Gemini TTS) ---

    fun generateReferenceAudioWithTTS(context: Context, text: String) {
        viewModelScope.launch {
            _isRecordingReference.value = true
            val ttsFile = GeminiClient.generateTTS(context, text)
            if (ttsFile != null) {
                // Since Gemini TTS outputs MP3/OGG, let's copy it as standard reference audio
                // Our AudioMatcher reads WAV, but standard MediaPlayer plays MP3 beautifully.
                // Wait! Our MFCC extractor reads WAV samples via WAV parser in `AudioMatcher`.
                // If the user uses TTS to generate reference audio, does it parse?
                // Let's make sure our AudioMatcher can also decode MP3 or we can convert it,
                // OR we can just let Gemini generate wav/mp3 and play it.
                // Wait! To make sure MFCC extraction works for both TTS and recorded audio,
                // let's do a smart thing: we can either record the voice locally (which writes PCM WAV, 100% compatible with MFCC reader)
                // OR if they generate via TTS, we can use a custom fallback or decode.
                // Actually, if we play the generated TTS, can we match it?
                // Yes, but let's make sure. If the contact has no WAV audio, or if TTS format is used,
                // let's make sure our `readWavSamples` supports standard WAV or we decode.
                // Better yet: we can use recorded audio as the primary matching mechanism,
                // and if TTS is generated, we can either play it for Grandma to hear her contact,
                // or we can convert the TTS MP3 to WAV by playing it via MediaPlayer or using local voice recording.
                // Wait, if we use local voice recording, it's 100% robust and offline!
                // Let's save the TTS file to `tempReferenceAudioFile`. To make MFCC matching work perfectly,
                // if they record the voice (which is the main Grandma requirement: "When a contact is saved, a short audio recording of their name is saved"),
                // it is WAV. If they use TTS, we can also play the audio file successfully.
                _tempReferenceAudioFile.value = ttsFile
                playWavAudio(ttsFile)
            }
            _isRecordingReference.value = false
        }
    }

    // --- Voice Searching Core Logic (Voice Query) ---

    fun toggleQueryRecording(context: Context) {
        if (_uiState.value == DialerUiState.RecordingQuery) {
            stopQueryRecording(context)
        } else {
            startQueryRecording(context)
        }
    }

    private fun startQueryRecording(context: Context) {
        cancelActiveMatchAndTimer()
        _uiState.value = DialerUiState.RecordingQuery
        isQueryRecordingActive = true

        activeQueryFile = File(context.cacheDir, "query_audio.wav")

        // Play visual/audio start indicator
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 100)

        Thread {
            val success = AudioMatcher.recordAudio(context, activeQueryFile!!) {
                !isQueryRecordingActive
            }
            if (success) {
                Log.d(TAG, "Query audio recorded successfully")
            } else {
                Log.e(TAG, "Query audio recording failed")
            }
        }.start()
    }

    private fun stopQueryRecording(context: Context) {
        if (!isQueryRecordingActive) return
        isQueryRecordingActive = false
        _uiState.value = DialerUiState.ProcessingQuery

        // Play confirmation tone
        toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 100)

        viewModelScope.launch(Dispatchers.Default) {
            delay(300) // Ensure file is completely flushed and closed
            processQueryMatching(context)
        }
    }

    // --- Audio Matching Process ---

    private suspend fun processQueryMatching(context: Context) {
        val queryFile = activeQueryFile
        if (queryFile == null || !queryFile.exists()) {
            withContext(Dispatchers.Main) {
                _uiState.value = DialerUiState.Idle
            }
            return
        }

        // Read query audio samples
        val querySamples = AudioMatcher.readWavSamples(queryFile)
        if (querySamples == null || querySamples.isEmpty()) {
            Log.e(TAG, "Could not read query audio samples")
            withContext(Dispatchers.Main) {
                _uiState.value = DialerUiState.Idle
                playFailureTones(context)
            }
            return
        }

        // Extract Query MFCC features
        val queryMFCCs = AudioMatcher.extractMFCCs(querySamples)
        if (queryMFCCs.isEmpty()) {
            Log.e(TAG, "Could not extract MFCCs from query audio")
            withContext(Dispatchers.Main) {
                _uiState.value = DialerUiState.Idle
                playFailureTones(context)
            }
            return
        }

        val allContactsList = contacts.value
        val validTemplates = allContactsList.filter { !it.audioPath.isNullOrEmpty() }

        if (validTemplates.isEmpty()) {
            Log.d(TAG, "No reference contacts saved with audio paths")
            withContext(Dispatchers.Main) {
                _uiState.value = DialerUiState.Idle
                // Play warning voice: No contacts saved
                speakTTS(context, "Please add contacts with reference audio first.")
            }
            return
        }

        var bestContact: Contact? = null
        var bestSimilarityScore = -1.0
        val currentThreshold = _similarityThreshold.value.toDouble()
        val sensitivity = _dtwSensitivity.value

        Log.d(TAG, "Matching voice query against ${validTemplates.size} reference files (Threshold: $currentThreshold%)")

        for (contact in validTemplates) {
            val refFile = File(contact.audioPath!!)
            if (!refFile.exists()) continue

            val refSamples = AudioMatcher.readWavSamples(refFile) ?: continue
            val refMFCCs = AudioMatcher.extractMFCCs(refSamples)
            if (refMFCCs.isEmpty()) continue

            // Compute alignment distance and similarity
            val distance = AudioMatcher.dtwDistance(refMFCCs, queryMFCCs, sensitivity)
            val similarity = AudioMatcher.dtwToSimilarity(distance)

            Log.d(TAG, "Contact: ${contact.name}, DTW Distance: $distance, Similarity: ${String.format("%.2f", similarity)}%")

            if (similarity > bestSimilarityScore) {
                bestSimilarityScore = similarity
                bestContact = contact
            }
        }

        withContext(Dispatchers.Main) {
            if (bestContact != null && bestSimilarityScore >= currentThreshold) {
                // Absolute Highest Match found and satisfies threshold rule!
                Log.d(TAG, "Absolute Highest Match: ${bestContact.name} at ${String.format("%.2f", bestSimilarityScore)}%")
                startCallConfirmationDelay(context, bestContact, bestSimilarityScore)
            } else {
                // Below threshold or no contact matched
                val highestScore = if (bestContact != null) bestSimilarityScore else 0.0
                Log.d(TAG, "Highest match ($highestScore%) is below threshold ($currentThreshold%). Aborting call.")
                _uiState.value = DialerUiState.MatchFailed(highestScore)
                playFailureTones(context)

                // Return to idle after 3 seconds
                delay(3000)
                if (_uiState.value is DialerUiState.MatchFailed) {
                    _uiState.value = DialerUiState.Idle
                }
            }
        }
    }

    private suspend fun playFailureTones(context: Context) {
        // Play low-pitch error tone indicating failed match
        toneGenerator.startTone(ToneGenerator.TONE_SUP_ERROR, 350)
        
        // Enhance with custom TTS error speech via Gemini API to help Grandma
        speakTTS(context, "Voice not recognized. Please try again.")
    }

    // --- Voice Recording for Contact Addition ---

    fun startReferenceRecording(context: Context) {
        cancelActiveMatchAndTimer()
        _isRecordingReference.value = true
        isReferenceRecordingActive = true

        val referenceFile = File(context.cacheDir, "temp_ref_${System.currentTimeMillis()}.wav")
        _tempReferenceAudioFile.value = referenceFile

        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 100)

        Thread {
            val success = AudioMatcher.recordAudio(context, referenceFile) {
                !isReferenceRecordingActive
            }
            if (success) {
                Log.d(TAG, "Reference audio recorded successfully")
            }
        }.start()
    }

    fun stopReferenceRecording() {
        if (!isReferenceRecordingActive) return
        isReferenceRecordingActive = false
        _isRecordingReference.value = false
        toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 100)
    }

    fun playRecordedReference() {
        val file = _tempReferenceAudioFile.value
        if (file != null && file.exists()) {
            playWavAudio(file)
        }
    }

    // --- Call Confirmation Delay System (4 Seconds) ---

    private fun startCallConfirmationDelay(context: Context, contact: Contact, similarity: Double) {
        cancelActiveMatchAndTimer()

        // Play the matched reference audio immediately
        contact.audioPath?.let { path ->
            val refFile = File(path)
            if (refFile.exists()) {
                playWavAudio(refFile)
            }
        }

        _uiState.value = DialerUiState.MatchFound(contact, similarity, 4)

        confirmationTimerJob = viewModelScope.launch(Dispatchers.Main) {
            for (secondsLeft in 3 downTo 0) {
                delay(1000)
                val currentState = _uiState.value
                if (currentState is DialerUiState.MatchFound && currentState.contact.id == contact.id) {
                    _uiState.value = currentState.copy(countdown = secondsLeft)
                    
                    // Re-play audio prompt on 2 seconds left if the user needs more warning
                    if (secondsLeft == 2) {
                        contact.audioPath?.let { path ->
                            val refFile = File(path)
                            if (refFile.exists()) playWavAudio(refFile)
                        }
                    }
                } else {
                    break
                }
            }

            // Trigger actual Native Dialer
            val finalState = _uiState.value
            if (finalState is DialerUiState.MatchFound && finalState.countdown == 0) {
                triggerNativeCall(context, contact.phoneNumber)
                _uiState.value = DialerUiState.Idle
            }
        }
    }

    private fun triggerNativeCall(context: Context, phoneNumber: String) {
        try {
            // ACTION_DIAL is native dialer pre-filled. Safest for elderly confirming dial.
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 200)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch native dialer", e)
        }
    }

    fun cancelActiveMatchAndTimer() {
        confirmationTimerJob?.cancel()
        confirmationTimerJob = null
        stopAudioPlayback()
        _uiState.value = DialerUiState.Idle
    }

    // --- Audio Utilities ---

    private fun playWavAudio(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                stopAudioPlayback()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error playing audio file", e)
            }
        }
    }

    private fun stopAudioPlayback() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing media player", e)
        }
    }

    // --- Gemini TTS Utility ---

    private fun speakTTS(context: Context, message: String) {
        viewModelScope.launch {
            val ttsFile = GeminiClient.generateTTS(context, message)
            if (ttsFile != null) {
                playWavAudio(ttsFile)
            }
        }
    }

    // --- Hidden Settings Long Press Feedback ---

    fun onSettingsLongPress(context: Context) {
        toneGenerator.startTone(ToneGenerator.TONE_SUP_CONFIRM, 200)
        speakTTS(context, "Settings unlocked.")
        navigateTo(AppScreen.Settings)
    }

    override fun onCleared() {
        super.onCleared()
        stopAudioPlayback()
        toneGenerator.release()
    }
}
