/*
 * Copyright (c) 2019, Sony Mobile Communications Inc.
 * Licensed under the LICENSE.
 */
package com.sony.open.cameratest

import android.app.Activity
import android.content.Context
import android.graphics.Point
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.View
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_highspeed.*

import kotlinx.coroutines.*

class HighSpeedActivity : Activity() {
    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager}
    private val cameraHelper by lazy { CameraHelper(cameraManager) }
    private val mediaRecorder by lazy { MediaRecorder() }

    private var cameraDevice : CameraDevice? = null
    private var cameraSession : CameraConstrainedHighSpeedCaptureSession? = null
    private var cameraId : String? = null

    private val fileName = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath + "/CameraTest_HighSpeed.mp4"

    private var lastFrameTs : Long = -1
    private var avgFps : Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_highspeed)

        GlobalScope.launch(Dispatchers.Main) {
            // find all camera devices with CONSTRAINED_HIGH_SPEED support
            val ids = ArrayList<String>()
            for (id in cameraManager.cameraIdList) {
                val cc = cameraManager.getCameraCharacteristics(id)
                val caps = cc[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
                if (caps != null && caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) {
                    ids.add(id)
                }
            }
            if (ids.size <= 0) {
                Toast.makeText(this@HighSpeedActivity, "No cameraId with CONSTRAINED_HIGH_SPEED_VIDEO support found.", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }

            // select cameraId to use
            cameraId = ids[0]
            if (ids.size > 1) {
                val ret = cameraHelper.askSelection(this@HighSpeedActivity, "Choose cameraId:", ids)
                if(ret != null) {
                    cameraId = ids[ret]
                } else {
                    Toast.makeText(this@HighSpeedActivity, "No camera selected.", Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()

        // stop camera session on pause
        if(cameraSession != null) {
            btnHighSpeedSessionClick(btnHighSpeedSession)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraSession?.close()
        cameraHelper.closeCamera(cameraDevice)
    }

    fun updateFps(newTs : Long) : Long {
        val weight = 128

        // initialize first timestamp
        if (lastFrameTs < 0) {
            lastFrameTs = newTs
            return -1
        }

        // initialize first result
        val curFps = 1000000000 / (newTs - lastFrameTs)
        if (avgFps < 0) {
            avgFps = curFps*weight
            return curFps
        }

        // smooth and return value
        avgFps -= avgFps/weight
        avgFps += curFps
        lastFrameTs = newTs
        return avgFps/weight
    }

    @Suppress("UNUSED_PARAMETER")
    fun btnHighSpeedSessionClick(v : View) {
        // if there is an active session, close it and return
        if(cameraSession != null) {
            // stop recording
            if(chkHighSpeedRecording.isChecked) {
                mediaRecorder.stop()
            }

            // close session and camera
            cameraSession?.close()
            cameraSession = null
            cameraHelper.closeCamera(cameraDevice)

            var text = "Camera closed."
            if(chkHighSpeedRecording.isChecked) {
                text += " Recording saved as $fileName."
            }
            Toast.makeText(this@HighSpeedActivity, text, Toast.LENGTH_SHORT).show()

            // update UI and FPS state
            chkHighSpeedPreview.isEnabled = true
            chkHighSpeedRecording.isEnabled = true
            lastFrameTs = -1
            avgFps = -1
            return
        }

        // otherwise, start
        GlobalScope.launch(Dispatchers.Main) {
            val id = cameraId!!
            val surfaces = ArrayList<Surface>()

            // find all possible resolutions for all fixed-fps ranges
            val map = cameraManager.getCameraCharacteristics(id)[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
            val sizes = ArrayList<Pair<Size, Range<Int>>>()
            for (r in map!!.highSpeedVideoFpsRanges) {
                if (r.lower == r.upper) {
                    for (s in map.getHighSpeedVideoSizesFor(r)) {
                        sizes.add(Pair(s, r))
                    }
                }
            }
            if (sizes.size <= 0) {
                Toast.makeText(this@HighSpeedActivity, "No valid sizes for cameraId $id found.", Toast.LENGTH_LONG).show()
                finish()
            }

            // select resolution to use
            var size = sizes[0]
            if (sizes.size > 1) {
                val ret = cameraHelper.askSelection(this@HighSpeedActivity, "Select resolution:", sizes.map { "${it.first.width}x${it.first.height}, ${it.second.lower} fps" })
                if (ret != null) {
                    size = sizes[ret]
                } else {
                    Toast.makeText(this@HighSpeedActivity, "No resolution selected.", Toast.LENGTH_LONG).show()
                    return@launch
                }
            }

            // open camera device
            val device = cameraHelper.openCamera(id)
            if (device == null) {
                Toast.makeText(this@HighSpeedActivity, "Failed to open camera $id.", Toast.LENGTH_LONG).show()
                return@launch
            }
            cameraDevice = device

            // prepare preview surface
            if(chkHighSpeedPreview.isChecked) {
                // scale preview to aspect - very ugly
                val screenSize = Point()
                windowManager.defaultDisplay.getSize(screenSize)
                val lParams = tvHighSpeedPreview.layoutParams
                if(screenSize.x > screenSize.y) {
                    lParams.width = (screenSize.y * size.first.height) / size.first.width
                    lParams.height = screenSize.y
                } else {
                    lParams.width = screenSize.x
                    lParams.height = (screenSize.x * size.first.width) / size.first.height
                }
                tvHighSpeedPreview.layoutParams = lParams
                delay(250)

                // add preview surface
                tvHighSpeedPreview.surfaceTexture.setDefaultBufferSize(size.first.width, size.first.height)
                surfaces.add(Surface(tvHighSpeedPreview.surfaceTexture))
            }

            // prepare recording surface
            if(chkHighSpeedRecording.isChecked) {
                mediaRecorder.reset()
                mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                mediaRecorder.setOutputFile(fileName)
                mediaRecorder.setVideoSize(size.first.width, size.first.height)
                mediaRecorder.setVideoEncodingBitRate((size.first.width*size.first.height*size.second.lower) / 15)
                mediaRecorder.prepare()
                surfaces.add(mediaRecorder.surface)
            }

            // fail if no surfaces enabled
            if(surfaces.size == 0) {
                Toast.makeText(this@HighSpeedActivity, "No surfaces enabled.", Toast.LENGTH_LONG).show()
                cameraHelper.closeCamera(cameraDevice)
                return@launch
            }

            // create camera session
            val session = cameraHelper.createHighSpeedSession(device, surfaces)
            if (session == null) {
                Toast.makeText(this@HighSpeedActivity, "Failed to start camera session on camera $id.", Toast.LENGTH_LONG).show()
                return@launch
            }
            cameraSession = session
            chkHighSpeedPreview.isEnabled = false
            chkHighSpeedRecording.isEnabled = false

            // create repeating high speed request list
            val reqBuilder = device.createCaptureRequest(if (chkHighSpeedRecording.isChecked) CameraDevice.TEMPLATE_PREVIEW else CameraDevice.TEMPLATE_RECORD)
            reqBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, size.second)
            for(s in surfaces) {
                reqBuilder.addTarget(s)
            }
            val baseReq = reqBuilder.build()
            val reqList = session.createHighSpeedRequestList(baseReq)
            session.setRepeatingBurst(reqList, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, totalResult: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, totalResult)

                    // use totalResult timestamp for fps
                    val ts = totalResult[CaptureResult.SENSOR_TIMESTAMP] ?: -1
                    val fps = updateFps(ts)
                    txtHighSpeedFps.text = "FPS: $fps"
                }
            }, null)

            // start recording
            if(chkHighSpeedRecording.isChecked) {
                mediaRecorder.start()
            }

            Toast.makeText(this@HighSpeedActivity, "Ready: Camera ${device.id}, ${size.first.width}x${size.first.height}, ${size.second.lower} fps.", Toast.LENGTH_SHORT).show()
        }
    }
}