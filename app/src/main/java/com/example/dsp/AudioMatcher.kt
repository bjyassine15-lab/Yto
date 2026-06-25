package com.example.dsp

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.*

object AudioMatcher {
    private const val TAG = "AudioMatcher"
    const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    // MFCC Framing parameters
    private const val FRAME_SIZE = 512  // 32ms at 16kHz (Power of 2 for FFT)
    private const val FRAME_SHIFT = 160 // 10ms at 16kHz
    private const val NUM_MEL_FILTERS = 20
    private const val NUM_MFCCS = 13

    /**
     * Records audio from the microphone and saves it as a WAV file.
     * Returns the file path, or null if failed.
     */
    fun recordAudio(context: Context, outputFile: File, stopCondition: () -> Boolean): Boolean {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size for AudioRecord")
            return false
        }

        val tempRawFile = File(context.cacheDir, "temp_raw_audio.pcm")
        var audioRecord: AudioRecord? = null
        var outputStream: FileOutputStream? = null

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                max(bufferSize, 2048)
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord could not be initialized")
                return false
            }

            outputStream = FileOutputStream(tempRawFile)
            audioRecord.startRecording()
            Log.d(TAG, "Audio recording started")

            val audioBuffer = ShortArray(1024)
            val byteBuffer = ByteArray(2048)

            while (!stopCondition()) {
                val readResult = audioRecord.read(audioBuffer, 0, audioBuffer.size)
                if (readResult > 0) {
                    for (i in 0 until readResult) {
                        val value = audioBuffer[i]
                        byteBuffer[i * 2] = (value.toInt() and 0xff).toByte()
                        byteBuffer[i * 2 + 1] = ((value.toInt() shr 8) and 0xff).toByte()
                    }
                    outputStream.write(byteBuffer, 0, readResult * 2)
                } else if (readResult < 0) {
                    Log.e(TAG, "Error reading audio data: $readResult")
                    break
                }
            }

            audioRecord.stop()
            outputStream.flush()
            outputStream.close()
            outputStream = null

            // Convert PCM to WAV
            return convertPcmToWav(tempRawFile, outputFile)

        } catch (e: Exception) {
            Log.e(TAG, "Error during audio recording", e)
            return false
        } finally {
            try {
                audioRecord?.release()
            } catch (e: Exception) { /* ignore */ }
            try {
                outputStream?.close()
            } catch (e: Exception) { /* ignore */ }
            if (tempRawFile.exists()) {
                tempRawFile.delete()
            }
        }
    }

    private fun convertPcmToWav(pcmFile: File, wavFile: File): Boolean {
        val rawDataLength = pcmFile.length()
        val totalDataLength = rawDataLength + 36
        val byteRate = SAMPLE_RATE * 2 // 16-bit mono = 2 bytes per sample

        var fileInputStream: FileInputStream? = null
        var fileOutputStream: FileOutputStream? = null

        try {
            fileInputStream = FileInputStream(pcmFile)
            fileOutputStream = FileOutputStream(wavFile)

            // Write WAV Header
            val header = ByteArray(44)
            header[0] = 'R'.code.toByte() // RIFF
            header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte()
            header[3] = 'F'.code.toByte()
            header[4] = (totalDataLength and 0xff).toByte()
            header[5] = ((totalDataLength shr 8) and 0xff).toByte()
            header[6] = ((totalDataLength shr 16) and 0xff).toByte()
            header[7] = ((totalDataLength shr 24) and 0xff).toByte()
            header[8] = 'W'.code.toByte() // WAVE
            header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte()
            header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte() // 'fmt ' chunk
            header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte()
            header[15] = ' '.code.toByte()
            header[16] = 16 // Size of format chunk (16)
            header[17] = 0
            header[18] = 0
            header[19] = 0
            header[20] = 1 // Format: PCM (1)
            header[21] = 0
            header[22] = 1 // Mono
            header[23] = 0
            header[24] = (SAMPLE_RATE and 0xff).toByte() // Sample rate
            header[25] = ((SAMPLE_RATE shr 8) and 0xff).toByte()
            header[26] = ((SAMPLE_RATE shr 16) and 0xff).toByte()
            header[27] = ((SAMPLE_RATE shr 24) and 0xff).toByte()
            header[28] = (byteRate and 0xff).toByte() // Byte rate
            header[29] = ((byteRate shr 8) and 0xff).toByte()
            header[30] = ((byteRate shr 16) and 0xff).toByte()
            header[31] = ((byteRate shr 24) and 0xff).toByte()
            header[32] = 2 // Block align (channels * bitsPerSample / 8)
            header[33] = 0
            header[34] = 16 // Bits per sample
            header[35] = 0
            header[36] = 'd'.code.toByte() // 'data' chunk
            header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte()
            header[39] = 'a'.code.toByte()
            header[40] = (rawDataLength and 0xff).toByte() // Audio raw data size
            header[41] = ((rawDataLength shr 8) and 0xff).toByte()
            header[42] = ((rawDataLength shr 16) and 0xff).toByte()
            header[43] = ((rawDataLength shr 24) and 0xff).toByte()

            fileOutputStream.write(header, 0, 44)

            val buffer = ByteArray(4096)
            var bytesRead: Int
            while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                fileOutputStream.write(buffer, 0, bytesRead)
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write WAV file", e)
            return false
        } finally {
            try { fileInputStream?.close() } catch (e: Exception) {}
            try { fileOutputStream?.close() } catch (e: Exception) {}
        }
    }

    /**
     * Read audio samples from a WAV file.
     */
    fun readWavSamples(file: File): FloatArray? {
        if (!file.exists()) return null
        var fis: FileInputStream? = null
        try {
            fis = FileInputStream(file)
            val header = ByteArray(44)
            val readHeader = fis.read(header)
            if (readHeader < 44) return null

            // Find data chunk size
            val dataLength = file.length() - 44
            if (dataLength <= 0) return null

            val rawBytes = ByteArray(dataLength.toInt())
            var bytesRead = 0
            while (bytesRead < rawBytes.size) {
                val chunk = fis.read(rawBytes, bytesRead, rawBytes.size - bytesRead)
                if (chunk == -1) break
                bytesRead += chunk
            }

            // Convert to Float normalized (-1.0 to 1.0)
            val numSamples = bytesRead / 2
            val samples = FloatArray(numSamples)
            for (i in 0 until numSamples) {
                val low = rawBytes[i * 2].toInt() and 0xff
                val high = rawBytes[i * 2 + 1].toInt()
                val sample = ((high shl 8) or low).toShort()
                samples[i] = sample.toFloat() / 32768.0f
            }
            return samples
        } catch (e: Exception) {
            Log.e(TAG, "Error reading WAV file: ${e.message}")
            return null
        } finally {
            try { fis?.close() } catch (e: Exception) {}
        }
    }

    /**
     * Trims leading and trailing silence using an energy-based Voice Activity Detection (VAD).
     */
    fun trimSilence(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples

        var peak = 0.0f
        for (s in samples) {
            val absVal = abs(s)
            if (absVal > peak) {
                peak = absVal
            }
        }

        if (peak < 0.01f) return samples

        val frameSize = 160 // 10ms at 16kHz
        val numFrames = samples.size / frameSize
        if (numFrames == 0) return samples

        // Use a dynamic threshold based on the peak signal value
        val threshold = max(0.005f, peak * 0.08f)

        var firstVoiceFrame = -1
        var lastVoiceFrame = -1

        // Scan forward to find start of voice activity
        for (f in 0 until numFrames) {
            var framePeak = 0.0f
            for (i in 0 until frameSize) {
                val absVal = abs(samples[f * frameSize + i])
                if (absVal > framePeak) {
                    framePeak = absVal
                }
            }
            if (framePeak >= threshold) {
                firstVoiceFrame = f
                break
            }
        }

        // Scan backward to find end of voice activity
        for (f in numFrames - 1 downTo 0) {
            var framePeak = 0.0f
            for (i in 0 until frameSize) {
                val absVal = abs(samples[f * frameSize + i])
                if (absVal > framePeak) {
                    framePeak = absVal
                }
            }
            if (framePeak >= threshold) {
                lastVoiceFrame = f
                break
            }
        }

        // If no voice activity detected, return original samples
        if (firstVoiceFrame == -1 || lastVoiceFrame == -1 || firstVoiceFrame >= lastVoiceFrame) {
            return samples
        }

        // Add safety padding of a few frames to prevent consonant clipping
        val paddedFirstFrame = max(0, firstVoiceFrame - 4)
        val paddedLastFrame = min(numFrames - 1, lastVoiceFrame + 5)

        val startIndex = paddedFirstFrame * frameSize
        val endIndex = min(samples.size, (paddedLastFrame + 1) * frameSize)
        val trimmedSize = endIndex - startIndex
        if (trimmedSize <= 0) return samples

        val trimmed = FloatArray(trimmedSize)
        System.arraycopy(samples, startIndex, trimmed, 0, trimmedSize)
        return trimmed
    }

    /**
     * Normalizes the volume level of the audio samples to a target peak level.
     */
    fun normalizeVolume(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        var maxVal = 0.0f
        for (s in samples) {
            val absVal = abs(s)
            if (absVal > maxVal) {
                maxVal = absVal
            }
        }
        if (maxVal < 1e-5f) return samples

        val targetPeak = 0.9f
        val factor = targetPeak / maxVal
        val normalized = FloatArray(samples.size)
        for (i in samples.indices) {
            normalized[i] = min(1.0f, max(-1.0f, samples[i] * factor))
        }
        return normalized
    }

    /**
     * Preprocesses the audio samples by trimming silence and normalizing volume.
     */
    fun preprocessAudio(samples: FloatArray): FloatArray {
        val trimmed = trimSilence(samples)
        return normalizeVolume(trimmed)
    }

    /**
     * Extracts MFCC vectors from normalized audio samples.
     * Includes Pre-emphasis, Hamming windowing, FFT, Mel Filterbank, Log, and DCT.
     */
    fun extractMFCCs(samples: FloatArray): List<DoubleArray> {
        val preprocessed = preprocessAudio(samples)
        if (preprocessed.size < FRAME_SIZE) return emptyList()

        // 1. Pre-emphasis: y[n] = x[n] - 0.97 * x[n-1]
        val preEmphasized = FloatArray(preprocessed.size)
        preEmphasized[0] = preprocessed[0]
        for (i in 1 until preprocessed.size) {
            preEmphasized[i] = preprocessed[i] - 0.97f * preprocessed[i - 1]
        }

        // 2. Hamming Window coefficients
        val hamming = DoubleArray(FRAME_SIZE) { i ->
            0.54 - 0.46 * cos(2.0 * Math.PI * i / (FRAME_SIZE - 1))
        }

        // 3. Mel Filterbank Weights Setup
        val melFilters = createMelFilterbank()

        val rawMFCCs = mutableListOf<DoubleArray>()

        // Process frames
        var offset = 0
        while (offset + FRAME_SIZE <= preEmphasized.size) {
            val re = DoubleArray(FRAME_SIZE)
            val im = DoubleArray(FRAME_SIZE)

            // Window frame and copy to Real array
            for (i in 0 until FRAME_SIZE) {
                re[i] = preEmphasized[offset + i].toDouble() * hamming[i]
                im[i] = 0.0
            }

            // Compute FFT
            fft(re, im)

            // Compute Power Spectrum
            val halfSize = FRAME_SIZE / 2 + 1
            val powerSpectrum = DoubleArray(halfSize)
            for (i in 0 until halfSize) {
                powerSpectrum[i] = re[i] * re[i] + im[i] * im[i]
            }

            // Apply Mel Filterbank
            val melEnergies = DoubleArray(NUM_MEL_FILTERS)
            for (m in 0 until NUM_MEL_FILTERS) {
                var energy = 0.0
                val filterWeights = melFilters[m]
                for (k in 0 until halfSize) {
                    energy += powerSpectrum[k] * filterWeights[k]
                }
                melEnergies[m] = ln(max(energy, 1e-10))
            }

            // Compute DCT (Type II) to get MFCCs
            val mfcc = DoubleArray(NUM_MFCCS)
            for (i in 0 until NUM_MFCCS) {
                var sum = 0.0
                for (j in 0 until NUM_MEL_FILTERS) {
                    sum += melEnergies[j] * cos(Math.PI * i * (2.0 * j + 1.0) / (2.0 * NUM_MEL_FILTERS))
                }
                mfcc[i] = sum
            }

            rawMFCCs.add(mfcc)
            offset += FRAME_SHIFT
        }

        if (rawMFCCs.isEmpty()) return emptyList()

        // 4. Cepstral Mean & Variance Normalization (CMVN)
        val numFrames = rawMFCCs.size
        val means = DoubleArray(NUM_MFCCS)
        val vars = DoubleArray(NUM_MFCCS)

        for (i in 0 until NUM_MFCCS) {
            var sum = 0.0
            for (f in 0 until numFrames) {
                sum += rawMFCCs[f][i]
            }
            means[i] = sum / numFrames
        }

        for (i in 0 until NUM_MFCCS) {
            var sumSquareDiff = 0.0
            for (f in 0 until numFrames) {
                val diff = rawMFCCs[f][i] - means[i]
                sumSquareDiff += diff * diff
            }
            vars[i] = max(sumSquareDiff / numFrames, 1e-10)
        }

        val normalizedMFCCs = List(numFrames) { f ->
            DoubleArray(NUM_MFCCS) { i ->
                (rawMFCCs[f][i] - means[i]) / sqrt(vars[i])
            }
        }

        return normalizedMFCCs
    }

    /**
     * Standard Radix-2 Cooley-Tukey FFT algorithm.
     */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        if (n <= 1) return

        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                val tempRe = re[i]; re[i] = re[j]; re[j] = tempRe
                val tempIm = im[i]; im[i] = im[j]; im[j] = tempIm
            }
            var m = n shr 1
            while (m >= 1 && j >= m) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        var len = 2
        while (len <= n) {
            val angle = -2.0 * Math.PI / len
            val wlenRe = cos(angle)
            val wlenIm = sin(angle)
            val len2 = len shr 1
            for (i in 0 until n step len) {
                var wRe = 1.0
                var wIm = 0.0
                for (k in 0 until len2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val tRe = re[i + k + len2] * wRe - im[i + k + len2] * wIm
                    val tIm = re[i + k + len2] * wIm + im[i + k + len2] * wRe
                    re[i + k] = uRe + tRe
                    im[i + k] = uIm + tIm
                    re[i + k + len2] = uRe - tRe
                    im[i + k + len2] = uIm - tIm
                    val nextWRe = wRe * wlenRe - wIm * wlenIm
                    val nextWIm = wRe * wlenIm + wIm * wlenRe
                    wRe = nextWRe
                    wIm = nextWIm
                }
            }
            len = len shl 1
        }
    }

    /**
     * Converts frequency in Hz to Mel scale.
     */
    private fun hzToMel(hz: Double): Double {
        return 2595.0 * log10(1.0 + hz / 700.0)
    }

    /**
     * Converts Mel scale to frequency in Hz.
     */
    private fun melToHz(mel: Double): Double {
        return 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
    }

    /**
     * Creates a set of triangular filterbank weights for mel filters.
     */
    private fun createMelFilterbank(): List<DoubleArray> {
        val numBins = FRAME_SIZE / 2 + 1
        val minFreq = 300.0
        val maxFreq = SAMPLE_RATE / 2.0 // 8000 Hz

        val minMel = hzToMel(minFreq)
        val maxMel = hzToMel(maxFreq)

        // Mel scale spacing points
        val melPoints = DoubleArray(NUM_MEL_FILTERS + 2) { i ->
            minMel + i * (maxMel - minMel) / (NUM_MEL_FILTERS + 1)
        }

        // Convert back to Hz points
        val hzPoints = DoubleArray(NUM_MEL_FILTERS + 2) { i ->
            melToHz(melPoints[i])
        }

        // Map to FFT bins
        val binPoints = IntArray(NUM_MEL_FILTERS + 2) { i ->
            round(hzPoints[i] * (FRAME_SIZE) / SAMPLE_RATE).toInt()
        }

        // Build filter weights
        return List(NUM_MEL_FILTERS) { m ->
            val weights = DoubleArray(numBins)
            val left = binPoints[m]
            val center = binPoints[m + 1]
            val right = binPoints[m + 2]

            for (k in left until center) {
                if (k < numBins && (center - left) > 0) {
                    weights[k] = (k - left).toDouble() / (center - left)
                }
            }
            for (k in center..right) {
                if (k < numBins && (right - center) > 0) {
                    weights[k] = (right - k).toDouble() / (right - center)
                }
            }
            weights
        }
    }

    /**
     * Calculates the distance between two 13-dimensional vectors.
     */
    private fun euclideanDistance(v1: DoubleArray, v2: DoubleArray): Double {
        var sum = 0.0
        for (i in 0 until NUM_MFCCS) {
            val diff = v1[i] - v2[i]
            sum += diff * diff
        }
        return sqrt(sum)
    }

    /**
     * Computes Dynamic Time Warping (DTW) distance with a Sakoe-Chiba constraint.
     *
     * @param ref Reference template MFCCs.
     * @param query Query MFCCs.
     * @param tolerancePercent 70 to 95 slider value mapping to band size (higher is stricter).
     */
    fun dtwDistance(ref: List<DoubleArray>, query: List<DoubleArray>, tolerancePercent: Int): Double {
        val n = ref.size
        val m = query.size
        if (n == 0 || m == 0) return Double.MAX_VALUE

        // Scale tolerance percent:
        // Lower sensitivity slider = narrower window = strict speed/length alignment.
        // Higher sensitivity slider = wider window = relaxed speed/length alignment.
        // Sensitivity represents window size / margin of error. Let's map it.
        // At 100% sensitivity, window is full. At 10% sensitivity, window is 10% of max length.
        val windowSize = max(3, (tolerancePercent.toDouble() / 100.0 * max(n, m)).toInt())

        val dp = Array(n) { DoubleArray(m) { Double.MAX_VALUE } }

        dp[0][0] = euclideanDistance(ref[0], query[0])

        // Initial boundaries
        for (i in 1 until n) {
            if (abs(i) <= windowSize) {
                dp[i][0] = dp[i - 1][0] + euclideanDistance(ref[i], query[0])
            }
        }
        for (j in 1 until m) {
            if (abs(j) <= windowSize) {
                dp[0][j] = dp[0][j - 1] + euclideanDistance(ref[0], query[j])
            }
        }

        // DP State transitions
        for (i in 1 until n) {
            for (j in 1 until m) {
                if (abs(i - j) <= windowSize) {
                    val cost = euclideanDistance(ref[i], query[j])
                    val minPrev = min(
                        dp[i - 1][j], // Insertion
                        min(
                            dp[i][j - 1], // Deletion
                            dp[i - 1][j - 1] // Match
                        )
                    )
                    if (minPrev != Double.MAX_VALUE) {
                        dp[i][j] = cost + minPrev
                    }
                }
            }
        }

        val finalCost = dp[n - 1][m - 1]
        if (finalCost == Double.MAX_VALUE) return Double.MAX_VALUE

        // Normalized DTW Distance
        return finalCost / (n + m)
    }

    /**
     * Converts a raw DTW distance to a similarity percentage (0% to 100%).
     * Uses a piecewise-linear calibration curve:
     * - Distances below 0.6 indicate a virtually identical match: 95% - 100%
     * - Distances 0.6 to 1.2 cover slight speed/pitch variations: 85% - 95%
     * - Distances 1.2 to 1.8 represent borderline speech similarities: 50% - 85%
     * - Distances 1.8 to 3.0 are completely different phonetic utterances: 10% - 50%
     * - Distances above 3.0 are background noise or completely distinct: 0% - 10%
     */
    fun dtwToSimilarity(distance: Double): Double {
        if (distance == Double.MAX_VALUE) return 0.0
        
        return when {
            distance <= 0.6 -> {
                100.0 - (distance / 0.6) * 5.0
            }
            distance <= 1.2 -> {
                95.0 - ((distance - 0.6) / 0.6) * 10.0
            }
            distance <= 1.8 -> {
                85.0 - ((distance - 1.2) / 0.6) * 35.0
            }
            distance <= 3.0 -> {
                50.0 - ((distance - 1.8) / 1.2) * 40.0
            }
            else -> {
                val score = 10.0 - ((distance - 3.0) / 1.5) * 10.0
                max(0.0, score)
            }
        }
    }
}
