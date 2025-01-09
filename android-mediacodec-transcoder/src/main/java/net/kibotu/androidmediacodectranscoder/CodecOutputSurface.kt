package net.kibotu.androidmediacodectranscoder

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.graphics.SurfaceTexture.OnFrameAvailableListener
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.lang.RuntimeException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Holds state associated with a Surface used for MediaCodec decoder output.
 *
 *
 * The constructor for this class will prepare GL, create a SurfaceTexture,
 * and then create a Surface for that SurfaceTexture.  The Surface can be passed to
 * MediaCodec.configure() to receive decoder output.  When a frame arrives, we latch the
 * texture with updateTexImage(), then render the texture with GL to a pbuffer.
 *
 *
 * By default, the Surface will be using a BufferQueue in asynchronous mode, so we
 * can potentially drop frames.
 */
class CodecOutputSurface
    (width: Int, height: Int) : OnFrameAvailableListener {
    private var textureRender: STextureRender? = null
    var surfaceTexture: SurfaceTexture? = null
    var surface: Surface? = null

    private var eGLDisplay: EGLDisplay? = EGL14.EGL_NO_DISPLAY
    private var eGLContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eGLSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    var width: Int
    var height: Int

    private val frameSyncObject = Any() // guards mFrameAvailable
    private var frameAvailable = false

    private var pixelBuf: ByteBuffer? = null // used by saveFrame()


    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    /**
     * Creates a CodecOutputSurface backed by a pbuffer with the specified dimensions.  The
     * new EGL context and surface will be made current.  Creates a Surface that can be passed
     * to MediaCodec.configure().
     */
    init {
        require(!(width <= 0 || height <= 0))
        this@CodecOutputSurface.width = width
        this@CodecOutputSurface.height = height

        eglSetup()
        makeCurrent()
        setup()
    }

    /**
     * Creates interconnected instances of TextureRender, SurfaceTexture, and Surface.
     */
    private fun setup() {
        textureRender = STextureRender()
        textureRender?.surfaceCreated()

        handlerThread = HandlerThread("callback-thread")
        handlerThread?.start()
        handler = Handler(handlerThread!!.getLooper())

        ContentValues.TAG.log("textureID=" + textureRender!!.textureID)
        surfaceTexture = SurfaceTexture(textureRender!!.textureID)

        // This doesn't work if this object is created on the thread that CTS started for
        // these test cases.
        //
        // The CTS-created thread has a Looper, and the SurfaceTexture constructor will
        // create a Handler that uses it.  The "frame available" message is delivered
        // there, but since we're not a Looper-based thread we'll never see it.  For
        // this to do anything useful, CodecOutputSurface must be created on a thread without
        // a Looper, so that SurfaceTexture uses the main application Looper instead.
        //
        // Java language note: passing "this" out of a constructor is generally unwise,
        // but we should be able to get away with it here.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            surfaceTexture!!.setOnFrameAvailableListener(this, handler)
        } else {
            surfaceTexture!!.setOnFrameAvailableListener(this)
        }

        surface = Surface(surfaceTexture)

        pixelBuf = ByteBuffer.allocateDirect(width * height * 4)
        pixelBuf!!.order(ByteOrder.LITTLE_ENDIAN)
    }

    /**
     * Prepares EGL.  We want a GLES 2.0 context and a surface that supports pbuffer.
     */
    private fun eglSetup() {
        eGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eGLDisplay === EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("unable to get EGL14 display")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eGLDisplay, version, 0, version, 1)) {
            eGLDisplay = null
            throw RuntimeException("unable to initialize EGL14")
        }

        // Configure EGL for pbuffer and OpenGL ES 2.0, 24-bit RGB.
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(
                eGLDisplay, attribList, 0, configs, 0, configs.size,
                numConfigs, 0
            )
        ) {
            throw RuntimeException("unable to find RGB888+recordable ES2 EGL config")
        }

        // Configure context for OpenGL ES 2.0.
        val attrib_list = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eGLContext = EGL14.eglCreateContext(
            eGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            attrib_list, 0
        )
        checkEglError("eglCreateContext")
        if (eGLContext == null) {
            throw RuntimeException("null context")
        }

        // Create a pbuffer surface.
        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE
        )
        eGLSurface = EGL14.eglCreatePbufferSurface(eGLDisplay, configs[0], surfaceAttribs, 0)
        checkEglError("eglCreatePbufferSurface")
        if (eGLSurface == null) {
            throw RuntimeException("surface was null")
        }
    }

    /**
     * Discard all resources held by this class, notably the EGL context.
     */
    fun release() {
        if (eGLDisplay !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroySurface(eGLDisplay, eGLSurface)
            EGL14.eglDestroyContext(eGLDisplay, eGLContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eGLDisplay)
        }
        eGLDisplay = EGL14.EGL_NO_DISPLAY
        eGLContext = EGL14.EGL_NO_CONTEXT
        eGLSurface = EGL14.EGL_NO_SURFACE

        surface!!.release()

        // this causes a bunch of warnings that appear harmless but might confuse someone:
        //  W BufferQueue: [unnamed-3997-2] cancelBuffer: BufferQueue has been abandoned!
        //mSurfaceTexture.release();
        textureRender = null
        surface = null
        surfaceTexture = null
    }

    /**
     * Makes our EGL context and surface current.
     */
    fun makeCurrent() {
        if (!EGL14.eglMakeCurrent(eGLDisplay, eGLSurface, eGLSurface, eGLContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    /**
     * Latches the next buffer into the texture.  Must be called from the thread that created
     * the CodecOutputSurface object.  (More specifically, it must be called on the thread
     * with the EGLContext that contains the GL texture object used by SurfaceTexture.)
     */
    fun awaitNewImage() {
        val TIMEOUT_MS = 3000

        synchronized(frameSyncObject) {
            while (!frameAvailable) {
                try {
                    // Wait for onFrameAvailable() to signal us.  Use a timeout to avoid
                    // stalling the test if it doesn't arrive.
                    (frameSyncObject as Object).wait(TIMEOUT_MS.toLong())
                    if (!frameAvailable) {
                        // TODO: if "spurious wakeup", continue while loop
                        throw RuntimeException("frame wait timed out")
                    }
                } catch (ie: InterruptedException) {
                    // shouldn't happen
                    throw RuntimeException(ie)
                }
            }
            frameAvailable = false
        }

        // Latch the data.
        textureRender!!.checkGlError("before updateTexImage")
        surfaceTexture!!.updateTexImage()
    }

    /**
     * Draws the data from SurfaceTexture onto the current EGL surface.
     *
     * @param invert if set, render the image with Y inverted (0,0 in top left)
     */
    fun drawImage(invert: Boolean) {
        textureRender!!.drawFrame(surfaceTexture!!, invert)
    }

    // SurfaceTexture callback
    override fun onFrameAvailable(st: SurfaceTexture?) {
        ContentValues.TAG.log("new frame available")
        synchronized(frameSyncObject) {
            if (frameAvailable) {
                throw RuntimeException("mFrameAvailable already set, frame could be dropped")
            }
            frameAvailable = true
            (frameSyncObject as Object).notifyAll()
        }
    }

    /**
     * Saves the current frame to disk as a JPEG image.
     */
    @Throws(IOException::class)
    fun saveFrame(filename: String?, photoQuality: Int) {
        // glReadPixels gives us a ByteBuffer filled with what is essentially big-endian RGBA
        // data (i.e. a byte of red, followed by a byte of green...).  To use the Bitmap
        // constructor that takes an int[] array with pixel data, we need an int[] filled
        // with little-endian ARGB data.
        //
        // If we implement this as a series of buf.get() calls, we can spend 2.5 seconds just
        // copying data around for a 720p frame.  It's better to do a bulk get() and then
        // rearrange the data in memory.  (For comparison, the PNG compress takes about 500ms
        // for a trivial frame.)
        //
        // So... we set the ByteBuffer to little-endian, which should turn the bulk IntBuffer
        // get() into a straight memcpy on most Android devices.  Our ints will hold ABGR data.
        // Swapping B and R gives us ARGB.  We need about 30ms for the bulk get(), and another
        // 270ms for the color swap.
        //
        // We can avoid the costly B/R swap here if we do it in the fragment shader (see
        // http://stackoverflow.com/questions/21634450/ ).
        //
        // Having said all that... it turns out that the Bitmap#copyPixelsFromBuffer()
        // method wants RGBA pixels, not ARGB, so if we create an empty bitmap and then
        // copy pixel data in we can avoid the swap issue entirely, and just copy straight
        // into the Bitmap from the ByteBuffer.
        //
        // Making this even more interesting is the upside-down nature of GL, which means
        // our output will look upside-down relative to what appears on screen if the
        // typical GL conventions are used.  (For ExtractMpegFrameTest, we avoid the issue
        // by inverting the frame when we render it.)
        //
        // Allocating large buffers is expensive, so we really want mPixelBuf to be
        // allocated ahead of time if possible.  We still get some allocations from the
        // Bitmap / PNG creation.

        pixelBuf!!.rewind()
        GLES20.glReadPixels(
            0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
            pixelBuf
        )

        var bos: BufferedOutputStream? = null
        try {
            bos = BufferedOutputStream(FileOutputStream(filename))
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            pixelBuf!!.rewind()
            bmp.copyPixelsFromBuffer(pixelBuf!!)
            bmp.compress(Bitmap.CompressFormat.JPEG, photoQuality, bos)
            bmp.recycle()
        } finally {
            bos?.close()
        }
        ContentValues.TAG.log("Saved " + width + "x" + height + " frame as '" + filename + "'")
    }

    /**
     * Checks for EGL errors.
     */
    private fun checkEglError(msg: String?) {
        var error: Int
        if ((EGL14.eglGetError().also { error = it }) != EGL14.EGL_SUCCESS) {
            throw RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(error))
        }
    }
}