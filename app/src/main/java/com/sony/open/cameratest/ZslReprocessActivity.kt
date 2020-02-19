/*
 * Copyright (c) 2019, Sony Mobile Communications Inc.
 * Licensed under the LICENSE.
 */
package com.sony.open.cameratest

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.InputConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.media.ImageWriter
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*
import kotlin.collections.ArrayList

class ZslReprocessActivity : Activity() {
    private val TAG = "ZslReprocessActivity"

    private val numUnprocessedImages = 50
    private val numProcessedImages = 2

    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private val cameraHelper by lazy { CameraHelper(cameraManager) }

    private var cameraId: String? = null
    private var captureSize: Size? = null
    private var camDevice: CameraDevice? = null
    private var camSession: CameraCaptureSession? = null
    private var iwReprocess: ImageWriter? = null

    private class ImagePair(
            val meta: TotalCaptureResult,
            val img: Image
    )

    private val unprocessedMeta = LinkedList<TotalCaptureResult>()
    private val unprocessedData = LinkedList<Image>()
    private val unprocessedImages = LinkedList<ImagePair>()
    private val lSurfaces = ArrayList<Surface>(3)

    private val finalMeta = LinkedList<TotalCaptureResult>()
    private val finalData = LinkedList<Image>()

    private val closeables = LinkedList<AutoCloseable>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_zsl_reprocess)

        // find all camera devices with PRIVATE_REPROCESSING support
        val ids = cameraManager.cameraIdList.filter {
            val cc = cameraManager.getCameraCharacteristics(it)
            val caps = cc[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]

            caps != null && caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING)
        }
        if (ids.isEmpty()) {
            Toast.makeText(this@ZslReprocessActivity, "No cameraId with PRIVATE_REPROCESSING support found.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        GlobalScope.launch(Dispatchers.Main) {
            // select cameraId to use
            cameraId = ids[0]
            if (ids.size > 1) {
                val ret = cameraHelper.askSelection(this@ZslReprocessActivity, "Choose cameraId:", ids)
                if (ret != null) {
                    cameraId = ids[ret]
                } else {
                    Toast.makeText(this@ZslReprocessActivity, "No camera selected.", Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }
            }

            val cc = cameraManager.getCameraCharacteristics(cameraId!!)
            val map = cc[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP] as StreamConfigurationMap

            val iSizes = map.getInputSizes(ImageFormat.PRIVATE)
            val oSizes = map.getOutputSizes(ImageFormat.JPEG)

            val possibleSizes = iSizes.intersect(oSizes.asIterable()).sortedWith(compareBy(Size::getHeight, Size::getWidth).reversed())

            for (s in possibleSizes)
                Log.i(TAG, "Supported size: $s")

            if (possibleSizes.isEmpty()) {
                Toast.makeText(this@ZslReprocessActivity, "No matching input and output size found!", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }

            captureSize = possibleSizes.first()

            if (possibleSizes.size > 1) {
                val ret = cameraHelper.askSelection(this@ZslReprocessActivity, "Choose capture resolution:", possibleSizes.map { it.toString() })
                if (ret != null) {
                    captureSize = possibleSizes[ret]
                } else {
                    Toast.makeText(this@ZslReprocessActivity, "No capture resolution selected.", Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }
            }

            // TODO: Instead move all selection logic to onResume?
            startPreview()
        }
    }

    override fun onResume() {
        super.onResume()
        startPreview()
    }

    override fun onPause() {
        super.onPause()
        closeAll()
    }

    @Suppress("UNUSED_PARAMETER")
    fun btnCapture(v: View) {
        val iwReprocess = iwReprocess ?: return
        val camDevice = camDevice ?: return
        val camSession = camSession ?: return

        val todo = unprocessedImages.poll()
        if (todo == null) {
            Toast.makeText(this@ZslReprocessActivity, "Picture not taken: No unprocessed image available.", Toast.LENGTH_LONG).show()
            return
        }

        // Queue image for reprocessing on captureSession input:
        todo.img.use(iwReprocess::queueInputImage)
        val b = camDevice.createReprocessCaptureRequest(todo.meta)
        // Send result to irReprocess surface:
        b.addTarget(lSurfaces[2]) /* reprocess surface */
        camSession.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                super.onCaptureCompleted(session, request, result)
                Log.d(TAG, "ReprocessCapture.onCaptureCompleted")
                finalMeta.add(result)
                processFinalImage()
            }

            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                super.onCaptureFailed(session, request, failure)
                Log.w(TAG, "reprocess failed")
            }

            override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
                super.onCaptureBufferLost(session, request, target, frameNumber)
                Log.w(TAG, "reprocess lost buffer")
            }
        }, null)
    }

    /* Pop unprocessedMeta/unprocessedData and combine into unprocessedImages */
    private fun mergeUnprocessed() {
        while (unprocessedData.isNotEmpty() && unprocessedMeta.isNotEmpty()) {
            val meta = unprocessedMeta.poll()
            val img = unprocessedData.poll()
            if (meta == null || img == null)
                continue
            val p = ImagePair(meta, img)
            unprocessedImages.add(p)

            // TODO: single global variable?
            while (unprocessedImages.size > 1) {
                val old = unprocessedImages.poll()
                old?.img?.close()
            }

            val btnCapture = findViewById<Button>(R.id.btnZslReprocessCapture)
            btnCapture.isEnabled = true
        }
    }

    private fun processFinalImage() {
        while (finalMeta.isNotEmpty() && finalData.isNotEmpty()) {
            val meta = finalMeta.poll()
            val img = finalData.poll()
            if (img == null || meta == null)
                continue

            img.use {
                if (img.format == ImageFormat.JPEG) {
                    val start = System.currentTimeMillis()
                    // retrieve bytes and release buffer
                    val buf = img.planes[0].buffer
                    val jpegBytes = ByteArray(buf.remaining())
                    buf.get(jpegBytes)

                    // decode and display
                    val bmpImage = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                    val ivThumbnail = findViewById<ImageView>(R.id.ivZslReprocessThumbnail)
                    ivThumbnail.rotation = 90f
                    ivThumbnail.setImageBitmap(bmpImage)
                    Log.i(TAG, "Decoding JPEG took ${(System.currentTimeMillis() - start)}ms")
                } else {
                    Log.e(TAG, "format ${img.format} not supported")
                }
            }
        }
    }

    private fun startPreview() {
        val tvPreview = findViewById<TextureView>(R.id.tvZslReprocessPreview)

        // Create immutable local copy
        val captureSize = captureSize
        if (captureSize == null) {
            Log.w(TAG, "No capture size defined, can't start!")
            return
        }
        val cameraId = cameraId
        if (cameraId == null) {
            Log.w(TAG, "No cameraId set, can't start!")
            return
        }

        Log.i(TAG, "Capturing $captureSize on camera $cameraId")

        val stPreview = tvPreview.surfaceTexture
        stPreview.setDefaultBufferSize(captureSize.width, captureSize.height);
        // stPreview.setDefaultBufferSize(1280, 720)
        val tvSurface = Surface(stPreview)
        lSurfaces.add(tvSurface)
        val irPreview = ImageReader.newInstance(captureSize.width, captureSize.height, ImageFormat.PRIVATE, numUnprocessedImages)
        closeables.add(irPreview)
        irPreview.setOnImageAvailableListener({ ir ->
            unprocessedData.add(ir.acquireNextImage())
            mergeUnprocessed()
        }, null)
        lSurfaces.add(irPreview.surface)
        val irReprocess = ImageReader.newInstance(captureSize.width, captureSize.height, ImageFormat.JPEG, numProcessedImages)
        closeables.add(irReprocess)
        irReprocess.setOnImageAvailableListener({ ir ->
            finalData.add(ir.acquireNextImage())
            Log.d(TAG, "irReprocess.onImageAvailable")
            processFinalImage()
        }, null)
        lSurfaces.add(irReprocess.surface)

        GlobalScope.launch(Dispatchers.Main) {
            // open camera device
            val device = cameraHelper.openCamera(cameraId)
            if (device == null) {
                Toast.makeText(this@ZslReprocessActivity, "Failed to open camera $cameraId.", Toast.LENGTH_LONG).show()
                return@launch
            }
            camDevice = device

            val ic = InputConfiguration(captureSize.width, captureSize.height, ImageFormat.PRIVATE)
            device.createReprocessableCaptureSession(ic, lSurfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                    camSession = cameraCaptureSession
                    val camInput = cameraCaptureSession.inputSurface
                    if (camInput == null) {
                        Toast.makeText(this@ZslReprocessActivity, "Camera capture session has no input surface.", Toast.LENGTH_LONG).show()
                        return
                    }
                    iwReprocess = ImageWriter.newInstance(camInput, 2)

                    val b = device.createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG)
                    // Show preview in UI:
                    b.addTarget(tvSurface) // TextureView
                    // Send unprocessed image to reader to cache for future processing
                    b.addTarget(irPreview.surface)
                    cameraCaptureSession.setRepeatingRequest(b.build(), object : CameraCaptureSession.CaptureCallback() {
                        var then = 0L
                        var n = 0.0
                        var s = 0.0
                        var sq = 0.0

                        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                            super.onCaptureCompleted(session, request, result)

                            if (then == 0L) {
                                // first frame
                                then = System.nanoTime()
                                return
                            }

                            // gather statistics
                            val now = System.nanoTime()
                            val diff = (now - then) / 1000000.0
                            n += 1
                            s += diff
                            sq += diff * diff
                            then = now

                            // print and reset regularly
                            if (n >= 50) {
                                // https://www-user.tu-chemnitz.de/~heha/hs/mr610.htm
                                val avg = s / n
                                val sfq = sq - (s * s) / n
                                val std = Math.sqrt(sfq / (n - 1))
                                Log.d(TAG, String.format(Locale.US, "preview at %.2f fps (%.2f ± %.2f ms)", 1000 / avg, avg, std))

                                n = 0.0
                                s = 0.0
                                sq = 0.0
                            }

                            unprocessedMeta.add(result)
                            mergeUnprocessed()
                        }

                        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                            super.onCaptureFailed(session, request, failure)
                            Log.w(TAG, "preview failed")
                        }

                        override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
                            super.onCaptureBufferLost(session, request, target, frameNumber)
                            Log.w(TAG, "preview lost buffer")
                        }
                    }, null)
                }

                override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                    Toast.makeText(this@ZslReprocessActivity, "ERROR: Failed to configure session.", Toast.LENGTH_LONG).show()
                    closeAll()
                    finish()
                }
            }, null)
        }
    }

    private fun closeAll() {
        val b = findViewById<Button>(R.id.btnZslReprocessCapture)
        b.isEnabled = false

//        for (s in lSurfaces)
//            s.release()

        for (c in closeables)
            c.close()
        closeables.clear()

        iwReprocess?.close()
        iwReprocess = null

        camSession?.close()
        camSession = null
        camDevice?.close()
        camDevice = null
    }
}
