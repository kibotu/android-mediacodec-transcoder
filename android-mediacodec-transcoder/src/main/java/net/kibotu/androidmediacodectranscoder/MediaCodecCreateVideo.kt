package net.kibotu.androidmediacodectranscoder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import io.reactivex.Observable
import io.reactivex.ObservableEmitter
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.functions.Consumer
import io.reactivex.functions.Function
import io.reactivex.schedulers.Schedulers
import java.io.File
import java.io.IOException

class MediaCodecCreateVideo(mediaConfig: MediaConfig) {
    private val subscription = CompositeDisposable()
    private var outputFile: File? = null
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null

    private val mimeType: String // H.264 Advanced Video Coding
    private var bitRate: Int = 0
    private var frameRate: Int = 0 // Frames per second

    private var iFrameInterval: Int = 0

    private var generateIndex: Int = 0
    private var trackIndex: Int = 0
    private val noMoreFrames = false
    private val abort = false

    private var colorFormat = 0
    private var frameNumber = 0
    private var startTime: Long = 0

    private var frames: List<File>? = null
    private var cancelable: MediaCodecExtractImages.Cancelable? = null

    init {
        this.mimeType = mediaConfig.mimeType

        if (mediaConfig.bitRate != null) {
            this.bitRate = mediaConfig.bitRate
        }

        if (mediaConfig.frameRate != null) {
            this.frameRate = mediaConfig.frameRate
        }

        if (mediaConfig.iFrameInterval != null) {
            this.iFrameInterval = mediaConfig.iFrameInterval
        }

        val formats = this.getMediaCodecList()

        if (colorFormat <= 0) {
            colorFormat = CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        }

        //need to select correct color format yuv420. In here we are deciding which one should we use
        //https://www.jianshu.com/p/54a702be01e1 check this link for more (Chinese)
        lab@ for (format in formats) {
            when (format) {
                CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> {
                    colorFormat = format
                    break@lab
                }

                CodecCapabilities.COLOR_FormatYUV420Planar -> {
                    colorFormat = format
                    break@lab
                }

                CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar -> {
                    colorFormat = format
                    break@lab
                }

                CodecCapabilities.COLOR_FormatYUV420PackedPlanar -> {
                    colorFormat = format
                    break@lab
                }
            }
        }
    }

    fun startEncoding(
        frames: List<File>,
        width: Int,
        height: Int,
        outputUri: Uri,
        cancelable: MediaCodecExtractImages.Cancelable?,
        emitter: ObservableEmitter<Progress>
    ) {
        mWidth = width
        mHeight = height
        this.frames = frames
        this.cancelable = cancelable

        startTime = System.currentTimeMillis()

        outputFile = File(outputUri.path ?: return)

        val codecInfo = selectCodec(mimeType)
        if (codecInfo == null) {
            TAG.log("Unable to find an appropriate codec for " + mimeType)
            return
        }
        TAG.log("found codec: " + codecInfo.name)

        try {
            mediaCodec = MediaCodec.createByCodecName(codecInfo.name)
        } catch (e: IOException) {
            TAG.log("Unable to create MediaCodec " + e.message)
            emitter.onError(e)
            return
        }

        val mediaFormat = MediaFormat.createVideoFormat(mimeType, mWidth, mHeight)
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
        mediaCodec!!.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mediaCodec!!.start()
        try {
            mediaMuxer = MediaMuxer(
                outputFile!!.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
        } catch (e: IOException) {
            TAG.log("MediaMuxer creation failed. " + e.message)
            emitter.onError(e)
            return
        }

        TAG.log("Initialization complete. Starting encoder...")

        if (this.cancelable!!.cancel.get()) {
            release()
            outputFile!!.delete()
            return
        }

        subscription.add(
            Observable.fromIterable<File?>(frames)
                .map<Bitmap?>(Function { input: File? -> BitmapFactory.decodeFile(input!!.absolutePath) })
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.computation())
                .subscribe(Consumer { bitmap: Bitmap? ->
                    frameNumber++
                    encode(bitmap!!, emitter)
                })
        )
    }

    private fun encode(bitmap: Bitmap, emitter: ObservableEmitter<Progress>) {
        if (cancelable!!.cancel.get()) {
            release()
            outputFile!!.delete()
            return
        }

        val progress = Progress(
            (((frameNumber.toFloat()) / (frames!!.size.toFloat() - 1f)) * 100).toInt(),
            null,
            Uri.parse(outputFile!!.absolutePath),
            System.currentTimeMillis() - startTime
        )
        emitter.onNext(progress)

        TAG.log("Encoder started")
        val byteConvertFrame = getNV12(bitmap.getWidth(), bitmap.getHeight(), bitmap)

        val TIMEOUT_USEC: Long = 500000
        val inputBufIndex = mediaCodec!!.dequeueInputBuffer(TIMEOUT_USEC)
        val ptsUsec = computePresentationTime(generateIndex.toLong(), frameRate)
        if (inputBufIndex >= 0) {
            val inputBuffer = mediaCodec!!.getInputBuffer(inputBufIndex)
            inputBuffer!!.clear()
            inputBuffer.put(byteConvertFrame)
            mediaCodec!!.queueInputBuffer(inputBufIndex, 0, byteConvertFrame.size, ptsUsec, 0)
            generateIndex++
        }
        val mBufferInfo = MediaCodec.BufferInfo()
        val encoderStatus = mediaCodec!!.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC)
        if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
            // no output available yet
            TAG.log("No output from encoder available")
        } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            // not expected for an encoder
            val newFormat = mediaCodec!!.outputFormat
            newFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 3000 * 3000)
            trackIndex = mediaMuxer!!.addTrack(newFormat)
            mediaMuxer!!.start()
        } else if (encoderStatus < 0) {
            TAG.log("unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus)
        } else if (mBufferInfo.size != 0) {
            val encodedData = mediaCodec!!.getOutputBuffer(encoderStatus)
            if (encodedData == null) {
                TAG.log("encoderOutputBuffer " + encoderStatus + " was null")
            } else {
                encodedData.position(mBufferInfo.offset)
                encodedData.limit(mBufferInfo.offset + mBufferInfo.size)
                mediaMuxer!!.writeSampleData(trackIndex, encodedData, mBufferInfo)
                mediaCodec!!.releaseOutputBuffer(encoderStatus, false)
            }
        }

        if (frameNumber >= frames!!.size - 1) {
            emitter.onComplete()
            release()
        }
    }

    private fun release() {
        subscription.dispose()

        frames = null
        cancelable = null

        if (mediaCodec != null) {
            mediaCodec!!.stop()
            mediaCodec!!.release()
            mediaCodec = null
            TAG.log("RELEASE CODEC")
        }
        if (mediaMuxer != null) {
            mediaMuxer!!.stop()
            mediaMuxer!!.release()
            mediaMuxer = null
            TAG.log("RELEASE MUXER")
        }
    }

    private fun getNV12(inputWidth: Int, inputHeight: Int, scaled: Bitmap): ByteArray {
        val argb = IntArray(inputWidth * inputHeight)

        //Log.i(TAG, "scaled : " + scaled);
        scaled.getPixels(argb, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        val yuv = ByteArray(inputWidth * inputHeight * 3 / 2)

        when (colorFormat) {
            CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> encodeYUV420SP(
                yuv,
                argb,
                inputWidth,
                inputHeight
            )

            CodecCapabilities.COLOR_FormatYUV420Planar -> encodeYUV420P(
                yuv,
                argb,
                inputWidth,
                inputHeight
            )

            CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar -> encodeYUV420PSP(
                yuv,
                argb,
                inputWidth,
                inputHeight
            )

            CodecCapabilities.COLOR_FormatYUV420PackedPlanar -> encodeYUV420PP(
                yuv,
                argb,
                inputWidth,
                inputHeight
            )
        }

        return yuv
    }

    private fun encodeYUV420P(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
        val frameSize = width * height

        var yIndex = 0
        var uIndex = frameSize
        var vIndex = frameSize + width * height / 4

        var a: Int
        var R: Int
        var G: Int
        var B: Int
        var Y: Int
        var U: Int
        var V: Int
        var index = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                a = (argb[index] and -0x1000000) shr 24 // a is not used obviously
                R = (argb[index] and 0xff0000) shr 16
                G = (argb[index] and 0xff00) shr 8
                B = (argb[index] and 0xff) shr 0

                // well known RGB to YUV algorithm
                Y = ((66 * R + 129 * G + 25 * B + 128) shr 8) + 16
                V = ((-38 * R - 74 * G + 112 * B + 128) shr 8) + 128 // Previously U
                U = ((112 * R - 94 * G - 18 * B + 128) shr 8) + 128 // Previously V

                yuv420sp[yIndex++] = (if ((Y < 0)) 0 else (if ((Y > 255)) 255 else Y)).toByte()
                if (j % 2 == 0 && index % 2 == 0) {
                    yuv420sp[vIndex++] = (if ((U < 0)) 0 else (if ((U > 255)) 255 else U)).toByte()
                    yuv420sp[uIndex++] = (if ((V < 0)) 0 else (if ((V > 255)) 255 else V)).toByte()
                }

                index++
            }
        }
    }

    private fun encodeYUV420SP(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
        val frameSize = width * height

        var yIndex = 0
        var uvIndex = frameSize

        var a: Int
        var R: Int
        var G: Int
        var B: Int
        var Y: Int
        var U: Int
        var V: Int
        var index = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                a = (argb[index] and -0x1000000) shr 24 // a is not used obviously
                R = (argb[index] and 0xff0000) shr 16
                G = (argb[index] and 0xff00) shr 8
                B = (argb[index] and 0xff) shr 0

                // well known RGB to YUV algorithm
                Y = ((66 * R + 129 * G + 25 * B + 128) shr 8) + 16
                V = ((-38 * R - 74 * G + 112 * B + 128) shr 8) + 128 // Previously U
                U = ((112 * R - 94 * G - 18 * B + 128) shr 8) + 128 // Previously V

                yuv420sp[yIndex++] = (if ((Y < 0)) 0 else (if ((Y > 255)) 255 else Y)).toByte()
                if (j % 2 == 0 && index % 2 == 0) {
                    yuv420sp[uvIndex++] = (if ((V < 0)) 0 else (if ((V > 255)) 255 else V)).toByte()
                    yuv420sp[uvIndex++] = (if ((U < 0)) 0 else (if ((U > 255)) 255 else U)).toByte()
                }

                index++
            }
        }
    }

    private fun encodeYUV420PSP(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
        width * height

        var yIndex = 0

        //        int uvIndex = frameSize;
        var a: Int
        var R: Int
        var G: Int
        var B: Int
        var Y: Int
        var U: Int
        var V: Int
        var index = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                a = (argb[index] and -0x1000000) shr 24 // a is not used obviously
                R = (argb[index] and 0xff0000) shr 16
                G = (argb[index] and 0xff00) shr 8
                B = (argb[index] and 0xff) shr 0

                //                R = (argb[index] & 0xff000000) >>> 24;
//                G = (argb[index] & 0xff0000) >> 16;
//                B = (argb[index] & 0xff00) >> 8;

                // well known RGB to YUV algorithm
                Y = ((66 * R + 129 * G + 25 * B + 128) shr 8) + 16
                V = ((-38 * R - 74 * G + 112 * B + 128) shr 8) + 128 // Previously U
                U = ((112 * R - 94 * G - 18 * B + 128) shr 8) + 128 // Previously V

                yuv420sp[yIndex++] = (if ((Y < 0)) 0 else (if ((Y > 255)) 255 else Y)).toByte()
                if (j % 2 == 0 && index % 2 == 0) {
                    yuv420sp[yIndex + 1] =
                        (if ((V < 0)) 0 else (if ((V > 255)) 255 else V)).toByte()
                    yuv420sp[yIndex + 3] =
                        (if ((U < 0)) 0 else (if ((U > 255)) 255 else U)).toByte()
                }
                if (index % 2 == 0) {
                    yIndex++
                }
                index++
            }
        }
    }

    private fun encodeYUV420PP(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
        var yIndex = 0
        var vIndex = yuv420sp.size / 2

        var a: Int
        var R: Int
        var G: Int
        var B: Int
        var Y: Int
        var U: Int
        var V: Int
        var index = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                a = (argb[index] and -0x1000000) shr 24 // a is not used obviously
                R = (argb[index] and 0xff0000) shr 16
                G = (argb[index] and 0xff00) shr 8
                B = (argb[index] and 0xff) shr 0

                //                R = (argb[index] & 0xff000000) >>> 24;
//                G = (argb[index] & 0xff0000) >> 16;
//                B = (argb[index] & 0xff00) >> 8;

                // well known RGB to YUV algorithm
                Y = ((66 * R + 129 * G + 25 * B + 128) shr 8) + 16
                V = ((-38 * R - 74 * G + 112 * B + 128) shr 8) + 128 // Previously U
                U = ((112 * R - 94 * G - 18 * B + 128) shr 8) + 128 // Previously V

                if (j % 2 == 0 && index % 2 == 0) { // 0
                    yuv420sp[yIndex++] = (if ((Y < 0)) 0 else (if ((Y > 255)) 255 else Y)).toByte()
                    yuv420sp[yIndex + 1] =
                        (if ((V < 0)) 0 else (if ((V > 255)) 255 else V)).toByte()
                    yuv420sp[vIndex + 1] =
                        (if ((U < 0)) 0 else (if ((U > 255)) 255 else U)).toByte()
                    yIndex++
                } else if (j % 2 == 0 && index % 2 == 1) { //1
                    yuv420sp[yIndex++] = (if ((Y < 0)) 0 else (if ((Y > 255)) 255 else Y)).toByte()
                } else if (j % 2 == 1 && index % 2 == 0) { //2
                    yuv420sp[vIndex++] = (if ((Y < 0)) 0 else (if ((Y > 255)) 255 else Y)).toByte()
                    vIndex++
                } else if (j % 2 == 1 && index % 2 == 1) { //3
                    yuv420sp[vIndex++] = (if ((Y < 0)) 0 else (if ((Y > 255)) 255 else Y)).toByte()
                }
                index++
            }
        }
    }

    fun getMediaCodecList(): IntArray {
        val numCodecs = MediaCodecList.getCodecCount()
        var codecInfo: MediaCodecInfo? = null
        var i = 0
        while (i < numCodecs && codecInfo == null) {
            val info = MediaCodecList.getCodecInfoAt(i)
            if (!info.isEncoder) {
                i++
                continue
            }
            val types = info.getSupportedTypes()
            var found = false
            var j = 0
            while (j < types.size && !found) {
                if (types[j] == mimeType) {
                    found = true
                }
                j++
            }
            if (!found) {
                i++
                continue
            }
            codecInfo = info
            i++
        }
        TAG.log("found" + codecInfo!!.name + "supporting " + mimeType)
        val capabilities = codecInfo.getCapabilitiesForType(mimeType)
        return capabilities.colorFormats
    }

    private fun computePresentationTime(frameIndex: Long, framerate: Int): Long {
        return 132 + frameIndex * 1000000 / framerate
    }

    companion object {
        private val TAG: String = MediaCodecCreateVideo::class.java.getSimpleName()

        private var mWidth = 0
        private var mHeight = 0
        private fun selectCodec(mimeType: String?): MediaCodecInfo? {
            val numCodecs = MediaCodecList.getCodecCount()
            for (i in 0 until numCodecs) {
                val codecInfo = MediaCodecList.getCodecInfoAt(i)
                if (!codecInfo.isEncoder) {
                    continue
                }
                val types = codecInfo.getSupportedTypes()
                for (j in types.indices) {
                    if (types[j].equals(mimeType, ignoreCase = true)) {
                        return codecInfo
                    }
                }
            }
            return null
        }
    }
}
