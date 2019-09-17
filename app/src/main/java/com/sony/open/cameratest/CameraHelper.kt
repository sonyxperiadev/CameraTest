/*
 * Copyright (c) 2019, Sony Mobile Communications Inc.
 * Licensed under the LICENSE.
 */
package com.sony.open.cameratest

import android.app.AlertDialog
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.util.Log
import android.view.Surface
import java.lang.Exception
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class CameraHelper(private val cameraManager : CameraManager) {
    // open camera - returns CameraDevice or null on failure
    suspend fun openCamera(cameraId : String): CameraDevice? = suspendCoroutine { cont ->
        val cb = object : CameraDevice.StateCallback() {
            var isDone = false

            override fun onOpened(camera: CameraDevice) {
                if(!isDone) {
                    isDone = true
                    cont.resume(camera)
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                if(!isDone) {
                    isDone = true
                    cont.resume(null)
                }
            }

            override fun onError(camera: CameraDevice, error: Int) {
                if(!isDone) {
                    isDone = true
                    cont.resumeWithException(Exception("openCamera failed: error $error"))
                }
            }
        }

        try {
            cameraManager.openCamera(cameraId, cb, null)
        } catch(e: SecurityException) {
            cont.resume(null)
        }
    }

    // close camera - returns nothing
    fun closeCamera(device : CameraDevice?) {
        device?.close()
    }

    // show AlertDialog - return selection index or null on failure
    suspend fun askSelection(context : Context, title : String, selections : List<String>) : Int? = suspendCoroutine { cont ->
        val dialogBuilder = AlertDialog.Builder(context)
        dialogBuilder.setTitle(title)
        dialogBuilder.setItems(selections.toTypedArray()) { _, which ->
            cont.resume(which)
        }

        val dialog = dialogBuilder.create()
        dialog.show()
    }
}
