package com.sony.open.cameratest

import android.app.Activity
import android.content.Context
import android.hardware.camera2.*
import android.os.Bundle
import android.util.Size
import android.view.Surface
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_actuator_test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ActuatorTestActivity : Activity() {
    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private val cameraHelper by lazy { CameraHelper(cameraManager) }

    private var cameraDevice : CameraDevice? = null
    private var cameraSession : CameraCaptureSession? = null
    private var cameraId : String? = null
    private var previewSurface : Surface? = null

    private val size = Size(1280, 720)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_actuator_test)

        // find all camera devices with manual auto-focus control
        val ids = ArrayList<String>()
        for (id in cameraManager.cameraIdList) {
            val cc = cameraManager.getCameraCharacteristics(id)
            val caps = cc[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
            if(caps != null && caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
                val mfd = cc[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE]
                if(mfd != null && mfd > 0) {
                    val modes = cc[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES]
                    if(modes != null && modes.contains(CameraMetadata.CONTROL_AF_MODE_OFF)) {
                        ids.add(id)
                    }
                }
            }
        }
        if(ids.size <= 0) {
            Toast.makeText(this, "No cameraId with manual auto-focus support found.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // select camera
        GlobalScope.launch(Dispatchers.Main) {
            cameraId = ids[0]
            if(ids.size > 1) {
                val ret = cameraHelper.askSelection(this@ActuatorTestActivity, "Choose cameraId:", ids)
                if(ret != null) {
                    cameraId = ids[ret]
                } else {
                    Toast.makeText(this@ActuatorTestActivity, "No camera selected.", Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }
            }
            txtActuatorTestCamera.text = "Camera: $cameraId (${size.width}x${size.height})"
        }

        // configure seek bar
        seekActuatorTestPosition.max = 100
        seekActuatorTestPosition.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, newValue: Int, fromUser: Boolean) {
                seekActuatorTestPositionChange(newValue, fromUser)
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {
                // nothing to override
            }

            override fun onStopTrackingTouch(p0: SeekBar?) {
                // nothing to override
            }
        })
    }

    // close camera on exit
    override fun onDestroy() {
        super.onDestroy()
        cameraSession?.close()
        cameraSession = null
        cameraHelper.closeCamera(cameraDevice)
    }

    // close camera if application paused
    override fun onPause() {
        super.onPause()
        cameraSession?.close()
        cameraSession = null
        cameraHelper.closeCamera(cameraDevice)
    }

    // restore preview if it was active before pausing
    override fun onResume() {
        super.onResume()
        if(chkActuatorTestPreview.isChecked) {
            chkActuatorTestPreviewClick(chkActuatorTestPreview)
        }
    }

    // open session and move lens to infinity
    private suspend fun openSession() {
        // return if a session is already active
        if(cameraSession != null) {
            return
        }

        // open camera device
        val device = cameraHelper.openCamera(cameraId!!)
        if(device == null) {
            Toast.makeText(this@ActuatorTestActivity, "Failed to open camera $cameraId.", Toast.LENGTH_LONG).show()
            return
        }
        cameraDevice = device

        // prepare preview surface - very ugly
        val lParams = tvActuatorTestPreview.layoutParams
        lParams.height = (tvActuatorTestPreview.measuredWidth * size.width)/size.height
        tvActuatorTestPreview.layoutParams = lParams
        delay(250)
        tvActuatorTestPreview.surfaceTexture.setDefaultBufferSize(size.width, size.height)
        previewSurface = Surface(tvActuatorTestPreview.surfaceTexture)
        if(previewSurface == null) {
            Toast.makeText(this@ActuatorTestActivity, "Failed to create preview surface.", Toast.LENGTH_LONG).show()
            return
        }

        // create capture session
        cameraSession = cameraHelper.createCaptureSession(device, listOf(previewSurface!!))
    }

    // move lens position
    private fun moveLens(newValue : Int) {
        // compute focus distance
        val mfd = cameraManager.getCameraCharacteristics(cameraId!!)[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0.0f

        // return if no session active
        if(cameraSession == null) {
            return
        }

        // create capture request with fixed lens position
        val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(previewSurface!!)
        builder[CaptureRequest.CONTROL_AF_MODE] = CaptureRequest.CONTROL_AF_MODE_OFF
        builder[CaptureRequest.LENS_FOCUS_DISTANCE] = (newValue * mfd) / 100
        val req = builder.build()

        // send request
        if(chkActuatorTestPreview.isChecked) {
            // update repeating request if preview is active
            cameraSession?.setRepeatingRequest(req, object : CameraCaptureSession.CaptureCallback() {
                // nothing to override
            }, null)
        } else {
            // single-shot if no preview
            cameraSession?.capture(req, object : CameraCaptureSession.CaptureCallback() {
                // nothing to override
            }, null)
        }
    }

    // when checkbox is clicked
    @Suppress("UNUSED_PARAMETER")
    fun chkActuatorTestPreviewClick(v : View) {
        GlobalScope.launch(Dispatchers.Main) {
            // open session if needed
            if(cameraSession == null) {
                openSession()
            }

            // enable or disable preview
            if(chkActuatorTestPreview.isChecked) {
                moveLens(seekActuatorTestPosition.progress)
            } else {
                cameraSession?.stopRepeating()

            }
        }
    }

    // when seek bar value is changed, move lens
    @Suppress("UNUSED_PARAMETER")
    fun seekActuatorTestPositionChange(newValue : Int, fromUser : Boolean) {
        GlobalScope.launch(Dispatchers.Main) {
            moveLens(newValue)
        }
    }

    // when sweep button is clicked, start sweeping
    @Suppress("UNUSED_PARAMETER")
    fun btnActuatorTestSweepClick(v : View) {
        GlobalScope.launch(Dispatchers.Main) {
            // open session if needed
            if(cameraSession == null) {
                openSession()
            }

            // disable UI
            btnActuatorTestSweep.isEnabled = false
            chkActuatorTestPreview.isEnabled = false

            // sweep forward (inf->macro) and backward (macro->inf)
            val bar = seekActuatorTestPosition
            val top = 50
            bar.isEnabled = false
            for(i in 0..top) {
                bar.progress = (100* i)/top
                delay(50)
            }
            for(i in top downTo 0) {
                bar.progress = (100 * i)/top
                delay(50)
            }

            // restore UI
            bar.isEnabled = true
            chkActuatorTestPreview.isEnabled = true
            btnActuatorTestSweep.isEnabled = true
        }
    }

}
