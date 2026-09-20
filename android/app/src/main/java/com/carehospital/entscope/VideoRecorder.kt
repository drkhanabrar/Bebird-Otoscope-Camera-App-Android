package com.carehospital.entscope

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File

/**
 * Records processed frames to an H.264 / MP4 file.
 *
 * Frames arrive as bitmaps, so the encoder is fed through ByteBuffer input in
 * YUV 4:2:0 rather than through an OpenGL input surface - that keeps the whole
 * recorder to one file with no EGL setup, which is plenty for the scope's
 * 640x480 stream.
 *
 * Every entry point swallows its own failures: a recording problem must never
 * take the live view down with it.
 */
class VideoRecorder(requestedWidth: Int, requestedHeight: Int, private val fps: Int) {

    companion object {
        private const val TAG = "VideoRecorder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val IFRAME_INTERVAL = 1

        /** Most hardware encoders want dimensions that are a multiple of 16. */
        private fun align16(value: Int): Int = (value / 16) * 16
    }

    val width: Int = align16(requestedWidth).coerceAtLeast(160)
    val height: Int = align16(requestedHeight).coerceAtLeast(160)

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    private var frameIndex = 0L
    private var scaled: Bitmap? = null
    private var pixels: IntArray? = null
    private var yuv: ByteArray? = null

    private val bufferInfo = MediaCodec.BufferInfo()

    var frameCount: Int = 0
        private set
    var isRecording: Boolean = false
        private set

    lateinit var outputFile: File
        private set

    // ----------------------------------------------------------------- start
    fun start(cacheDir: File): Boolean {
        return try {
            outputFile = File.createTempFile("scope_rec_", ".mp4", cacheDir)

            val encoder = MediaCodec.createEncoderByType(MIME)
            colorFormat = pickColorFormat(encoder)

            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, width * height * 6)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            codec = encoder

            muxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            pixels = IntArray(width * height)
            yuv = ByteArray(width * height * 3 / 2)
            frameIndex = 0
            frameCount = 0
            isRecording = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            releaseQuietly()
            false
        }
    }

    private fun pickColorFormat(encoder: MediaCodec): Int {
        return try {
            val caps = encoder.codecInfo.getCapabilitiesForType(MIME)
            val supported = caps.colorFormats.toSet()
            when {
                supported.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                supported.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                else ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            }
        } catch (e: Exception) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        }
    }

    // ----------------------------------------------------------------- frame
    fun encode(source: Bitmap) {
        if (!isRecording) return
        val encoder = codec ?: return
        try {
            val input = scaleToTarget(source)
            val argb = pixels ?: return
            val planes = yuv ?: return
            input.getPixels(argb, 0, width, 0, 0, width, height)
            argbToYuv420(argb, planes)

            val index = encoder.dequeueInputBuffer(12000)
            if (index >= 0) {
                val buffer = encoder.getInputBuffer(index)
                if (buffer != null) {
                    buffer.clear()
                    buffer.put(planes)
                    val ptsUs = frameIndex * 1_000_000L / fps
                    encoder.queueInputBuffer(index, 0, planes.size, ptsUs, 0)
                    frameIndex++
                    frameCount++
                }
            }
            drain(false)
        } catch (e: Exception) {
            Log.w(TAG, "encode failed", e)
        }
    }

    private fun scaleToTarget(source: Bitmap): Bitmap {
        if (source.width == width && source.height == height) return source
        val existing = scaled
        val target: Bitmap =
            if (existing != null && existing.width == width && existing.height == height) {
                existing
            } else {
                existing?.recycle()
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { scaled = it }
            }
        val canvas = android.graphics.Canvas(target)
        canvas.drawBitmap(
            source,
            null,
            android.graphics.Rect(0, 0, width, height),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        )
        return target
    }

    // ------------------------------------------------------------------ stop
    /** Finishes the file and returns it, or null if nothing usable was written. */
    fun stop(): File? {
        if (!isRecording) return null
        isRecording = false
        return try {
            val encoder = codec
            if (encoder != null) {
                val index = encoder.dequeueInputBuffer(12000)
                if (index >= 0) {
                    encoder.queueInputBuffer(
                        index, 0, 0,
                        frameIndex * 1_000_000L / fps,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                }
                drain(true)
            }
            val produced = muxerStarted && frameCount > 0
            releaseQuietly()
            if (produced && outputFile.length() > 0) outputFile else {
                outputFile.delete(); null
            }
        } catch (e: Exception) {
            Log.w(TAG, "stop failed", e)
            releaseQuietly()
            null
        }
    }

    private fun drain(endOfStream: Boolean) {
        val encoder = codec ?: return
        val mux = muxer ?: return
        // When finishing, keep pulling until the encoder actually signals
        // end-of-stream; give it a bounded number of empty polls so a
        // misbehaving encoder cannot hang the app.
        var emptyPolls = 0
        while (true) {
            val status = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 12000 else 0)
            when {
                status == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (++emptyPolls > 50) return
                }

                status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    emptyPolls = 0
                    if (!muxerStarted) {
                        trackIndex = mux.addTrack(encoder.outputFormat)
                        mux.start()
                        muxerStarted = true
                    }
                }

                status >= 0 -> {
                    emptyPolls = 0
                    val data = encoder.getOutputBuffer(status)
                    if (data != null && muxerStarted && bufferInfo.size > 0 &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        data.position(bufferInfo.offset)
                        data.limit(bufferInfo.offset + bufferInfo.size)
                        mux.writeSampleData(trackIndex, data, bufferInfo)
                    }
                    val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(status, false)
                    if (eos) return
                }
            }
        }
    }

    private fun releaseQuietly() {
        try { codec?.stop() } catch (_: Exception) { }
        try { codec?.release() } catch (_: Exception) { }
        codec = null
        try { if (muxerStarted) muxer?.stop() } catch (_: Exception) { }
        try { muxer?.release() } catch (_: Exception) { }
        muxer = null
        muxerStarted = false
        scaled?.recycle()
        scaled = null
        pixels = null
        yuv = null
    }

    // ------------------------------------------------------------ conversion
    /** Packs ARGB pixels into NV12 or I420 depending on the encoder's format. */
    private fun argbToYuv420(argb: IntArray, out: ByteArray) {
        val frameSize = width * height
        val semiPlanar =
            colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        var yIndex = 0
        var uvIndex = frameSize

        for (row in 0 until height) {
            for (col in 0 until width) {
                val pixel = argb[row * width + col]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                out[yIndex++] = y.coerceIn(0, 255).toByte()

                if (row % 2 == 0 && col % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    if (semiPlanar) {
                        out[uvIndex++] = u.coerceIn(0, 255).toByte()
                        out[uvIndex++] = v.coerceIn(0, 255).toByte()
                    } else {
                        val quarter = frameSize / 4
                        val plane = (row / 2) * (width / 2) + (col / 2)
                        out[frameSize + plane] = u.coerceIn(0, 255).toByte()
                        out[frameSize + quarter + plane] = v.coerceIn(0, 255).toByte()
                    }
                }
            }
        }
    }
}
