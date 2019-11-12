/*
 * Copyright (c) 2019, Sony Mobile Communications Inc.
 * Licensed under the LICENSE.
 */
package com.sony.open.cameratest

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.Toast

class MainActivity : Activity() {
    private val buttons by lazy {
        mapOf(
            Pair(getString(R.string.multi_camera), MultiCameraActivity::class.java),
            Pair(getString(R.string.parallel_capture), ParallelCaptureActivity::class.java),
            Pair(getString(R.string.zsl_reprocess), ZslReprocessActivity::class.java),
            Pair(getString(R.string.high_speed), HighSpeedActivity::class.java),
            Pair(getString(R.string.actuator_test), ActuatorTestActivity::class.java),
            Pair(getString(R.string.test_mode), TestModeActivity::class.java)
        )
    }

    private val permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    override fun onCreate(savedInstanceState : Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // fetch missing permissions
        val neededPermissions = ArrayList<String>()
        for(p in permissions) {
            if(checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(p)
            }
        }

        // ask for required permissions
        if(neededPermissions.size > 0) {
            val askPermissions = neededPermissions.toTypedArray()
            requestPermissions(askPermissions, 0)
        }

        // create buttons
        val mainLayout = findViewById<GridLayout>(R.id.mainActivityLayout)
        for(b in buttons) {
            val newLayout = GridLayout.LayoutParams()
            newLayout.setGravity(Gravity.FILL)
            newLayout.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1.0f)
            newLayout.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1.0f)

            val newButton = Button(this)
            newButton.text = b.key
            newButton.setOnClickListener{startModule(it)}
            newButton.layoutParams = newLayout
            mainLayout.addView(newButton)
        }
    }

    private fun startModule(button : View) {
        val clickedButton = button as Button

        for(b in buttons) {
            if(clickedButton.text == b.key) {
                val intent = Intent(this, b.value)
                startActivity(intent)
                return
            }
        }

        Toast.makeText(this, "Failed to start module.", Toast.LENGTH_LONG).show()
        return
    }
}
