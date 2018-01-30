/**
 * Copyright (c) 2018, Sony Mobile Communications Inc.
 * Licensed under the LICENSE.
 */
package com.sony.open.cameratest;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        /* request camera permission if needed */
        if(checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Missing CAMERA permission.", Toast.LENGTH_SHORT).show();
            requestPermissions(new String[] { Manifest.permission.CAMERA }, 0);
        }

        /* show list of cameras */
        try {
            CameraManager camManager = (CameraManager)getSystemService(Context.CAMERA_SERVICE);
            String[] cameraIds = camManager.getCameraIdList();

            String camList = "List of cameras:";
            for(String id : cameraIds) {
                camList += "\n\tCameraId '" + id + "'";
            }

            TextView txtCameraList = findViewById(R.id.txtCameraList);
            txtCameraList.setText(camList);

        } catch(Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    /* button handler */
    public void ButtonClick(View view) {
        int btnId = view.getId();
        Intent i;

        switch(btnId) {
            case R.id.btnDualCamera:
                i = new Intent(this, MultiCameraActivity.class);
                startActivity(i);
                break;

            case R.id.btnParallelCapture:
                i = new Intent(this, ParallelCaptureActivity.class);
                startActivity(i);
                break;

            case R.id.btnZslReprocess:
                i = new Intent(this, ZslReprocessActivity.class);
                startActivity(i);
                break;

            case R.id.btnTestMode:
                i = new Intent(this, TestModeActivity.class);
                startActivity(i);
                break;

            default:
                Toast.makeText(this, "Error: Unhandled button pressed.", Toast.LENGTH_SHORT).show();
                break;
        }
    }
}
