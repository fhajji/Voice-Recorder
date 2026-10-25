package org.fossify.voicerecorder.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.ParcelFileDescriptor
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import org.fossify.commons.extensions.showErrorToast
import org.fossify.voicerecorder.extensions.config
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class Mp3Recorder(val context: Context, audioSourceOverride: Int? = null) : Recorder {
    private var mp3buffer: ByteArray = ByteArray(0)
    private var isPaused = AtomicBoolean(false)
    private var isStopped = AtomicBoolean(false)
    private val gainProcessor = GainProcessor(context.config.samplingRate)
    private var androidLame: AndroidLame? = null
    private var outputFileDescriptor: ParcelFileDescriptor? = null
    private val minBufferSize = AudioRecord.getMinBufferSize(
        context.config.samplingRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    @SuppressLint("MissingPermission")
    private val audioRecord = AudioRecord(
        audioSourceOverride ?: context.config.microphoneMode,
        context.config.samplingRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        minBufferSize * 2
    )

    private var thread: Thread? = null

    override fun setPreferredDevice(device: AudioDeviceInfo?) {
        audioRecord.setPreferredDevice(device)
    }

    override fun setGainDb(gainDb: Float) {
        gainProcessor.setGainDb(gainDb)
    }

    override fun prepare() {}

    override fun start() {
        val rawData = ShortArray(minBufferSize)
        mp3buffer = ByteArray((7200 + rawData.size * 2 * 1.25).toInt())

        val outputFileDescriptor = requireNotNull(this.outputFileDescriptor)
        val outputStream = FileOutputStream(outputFileDescriptor.fileDescriptor)

        androidLame = LameBuilder()
            .setInSampleRate(context.config.samplingRate)
            .setOutBitrate(context.config.bitrate / 1000)
            .setOutSampleRate(context.config.samplingRate)
            .setOutChannels(1)
            .build()

        thread = Thread {
            try {
                audioRecord.startRecording()
            } catch (e: Exception) {
                context.showErrorToast(e)
                return@Thread
            }

            outputStream.use { outputStream ->
                while (!isStopped.get()) {
                    if (!isPaused.get()) {
                        val count = audioRecord.read(rawData, 0, minBufferSize)
                        if (count > 0) {
                            gainProcessor.process(rawData, count)
                            val encoded = androidLame!!.encode(rawData, rawData, count, mp3buffer)
                            if (encoded > 0) {
                                try {
                                    outputStream.write(mp3buffer, 0, encoded)
                                } catch (e: IOException) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    }
                }
            }
        }.apply { start() }
    }

    override fun stop() {
        isPaused.set(true)
        isStopped.set(true)
        audioRecord.stop()

        thread?.join() // ensures the buffer is fully written to the output file before continuing
        thread = null
    }

    override fun pause() {
        isPaused.set(true)
    }

    override fun resume() {
        isPaused.set(false)
    }

    override fun release() {
        androidLame?.flush(mp3buffer)
        audioRecord.release()
    }

    override fun getMaxAmplitude(): Int {
        return gainProcessor.getAndResetPeak()
    }

    override fun setOutputFile(parcelFileDescriptor: ParcelFileDescriptor) {
        // the output stream closes this duplicate, the caller keeps ownership of the original
        this.outputFileDescriptor = ParcelFileDescriptor.dup(parcelFileDescriptor.fileDescriptor)
    }
}
