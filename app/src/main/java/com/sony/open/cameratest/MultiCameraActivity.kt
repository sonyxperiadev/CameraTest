/*
 * Copyright (c) 2019, Sony Mobile Communications Inc.
 * Licensed under the LICENSE.
 */
package com.sony.open.cameratest

import android.app.Activity
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.os.Bundle
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_multi_camera.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MultiCameraActivity : Activity() {
    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private val cameraHelper by lazy { CameraHelper(cameraManager) }

    class CameraDeviceData(val cameraId : String, val tvPreview : TextureView) {
        var device : CameraDevice? = null
        var session : CameraCaptureSession? = null
    }
    private val cameraDevices = ArrayList<CameraDeviceData>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multi_camera)

        // create interface
        val llRoot = llMultiCameraRoot
        for(id in cameraManager.cameraIdList) {
            // Parent Layout
            val llCamera = LinearLayout(this@MultiCameraActivity)
            llCamera.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            llCamera.orientation = LinearLayout.HORIZONTAL
            llRoot.addView(llCamera)

            // Button
            val btnCamera = Button(this@MultiCameraActivity)
            btnCamera.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            btnCamera.text = "ID $id"
            btnCamera.setOnClickListener { v ->
                val data = v.tag as CameraDeviceData
                if(data.session == null) {
                    GlobalScope.launch(Dispatchers.Main) { startPreview(data) }
                } else {
                    stopPreview(data)
                }
            }
            llCamera.addView(btnCamera)

            // TextureView
            val tvPreview = TextureView(this@MultiCameraActivity)
            tvPreview.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            llCamera.addView(tvPreview)

            // add to internal structure
            val data = CameraDeviceData(id, tvPreview)
            cameraDevices.add(data)
            btnCamera.tag = data
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        for(data in cameraDevices) {
            if(data.session != null) {
                stopPreview(data)
            }
        }
    }

    private suspend fun startPreview(data : CameraDeviceData) {
        // get supported resolutions
        val map = cameraManager.getCameraCharacteristics(data.cameraId)[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        val sizes = ArrayList<Size>()
        for(s in map!!.getOutputSizes(ImageFormat.PRIVATE)) {
            sizes.add(s)
        }

        // select resolution
        var size = sizes[0]
        if(sizes.size > 1) {
            val ret = cameraHelper.askSelection(this@MultiCameraActivity, "Select resolution:", sizes.map { "${it.width}x${it.height}" })
            if(ret != null) {
                size = sizes[ret]
            } else {
                return
            }
        }

        // prepare preview - very ugly
        val lParams = data.tvPreview.layoutParams
        lParams.width = (data.tvPreview.measuredHeight * size.height)/size.width
        data.tvPreview.layoutParams = lParams
        delay(250)
        data.tvPreview.surfaceTexture.setDefaultBufferSize(size.width, size.height)
        val surfaces = listOf(Surface(data.tvPreview.surfaceTexture))

        // open camera device
        data.device = cameraHelper.openCamera(data.cameraId)
        if(data.device == null) {
            Toast.makeText(this@MultiCameraActivity, "Failed to open camera ${data.cameraId}", Toast.LENGTH_LONG).show()
            return
        }

        // create camera session
        data.session = cameraHelper.createCaptureSession(data.device!!, surfaces)
        if(data.session == null) {
            Toast.makeText(this@MultiCameraActivity, "Failed to create capture session.", Toast.LENGTH_LONG).show()
            cameraHelper.closeCamera(data.device)
        }

        // start preview
        val builder = data.device!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(surfaces[0])
        val req = builder.build()
        data.session?.setRepeatingRequest(req, object : CameraCaptureSession.CaptureCallback() {
            // nothing to override
        }, null)

        return
    }

    private fun stopPreview(data : CameraDeviceData) {
        data.session?.close()
        data.session = null
        cameraHelper.closeCamera(data.device)
        Toast.makeText(this@MultiCameraActivity, "Closed camera ${data.cameraId}.", Toast.LENGTH_LONG).show()
    }
}
