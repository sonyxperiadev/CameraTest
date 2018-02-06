/**
 * Copyright (c) 2018, Sony Mobile Communications Inc.
 * Licensed under the LICENSE.
 */
package com.sony.open.cameratest;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@SuppressWarnings({"MissingPermission", "FieldCanBeLocal"})
public class MultiCameraActivity extends Activity {
    /* number of cameras supported */
    private static final int numCams = 2;

    /* camera manager and camera ids */
    private static CameraManager camManager;
    private static String[] cameraIds;

    /* per-camera widgets */
    private Spinner[]     selCams     = new Spinner[numCams];
    private TextureView[] viewCams    = new TextureView[numCams];

    /* internal state */
    private String[]       activeCamIds   = new String[numCams];
    private CameraDevice[] activeCamDevs  = new CameraDevice[numCams];
    private Surface[]      activeSurfaces = new Surface[numCams];

    /* log to TextView */
    private void Log(String line, int cam) {
        // timestamp
        Calendar now = Calendar.getInstance();
        String timestamp = String.format(Locale.US, "%02d:%02d:%02d ",
                now.get(Calendar.HOUR_OF_DAY),
                now.get(Calendar.MINUTE),
                now.get(Calendar.SECOND));

        // prefix
        String prefix = "";
        if(cam >= 0) {
            prefix = "Cam " + cam + " (ID " + activeCamIds[cam] + "): ";
        }

        TextView log = findViewById(R.id.txtMultiCameraLog);
        log.setText(timestamp + prefix + line + "\n" + log.getText().toString());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_multi_camera);

        // fetch and store CameraIds
        ArrayAdapter<String> camListAdapter;
        try {
            camManager = (CameraManager)getSystemService(Context.CAMERA_SERVICE);
            cameraIds  = camManager.getCameraIdList();

            List<String> camIdList = new ArrayList<>();
            camIdList.add("<none>");
            for(String camId : cameraIds) {
                camIdList.add("ID " + camId);
            }
            camListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, camIdList);
        } catch (Exception e) {
            Log("onCreate Exception: " + e.getMessage(), -1);
            finish();
            return;
        }

        // create per-camera interface
        LinearLayout root = findViewById(R.id.lCams);
        for(int cam = 0; cam < numCams; cam++) {
            final int c = cam;

            // Parent Layout
            LinearLayout l = new LinearLayout(this);
            l.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            l.setOrientation(LinearLayout.VERTICAL);
            root.addView(l);

            // TextView
            TextView txv = new TextView(this);
            txv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            txv.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            txv.setText(getString(R.string.multi_camera_cam_name, cam));
            l.addView(txv);

            // Spinner
            Spinner sel = new Spinner(this);
            sel.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            sel.setAdapter(camListAdapter);
            sel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                    onCameraSelect(c, i);
                }

                @Override
                public void onNothingSelected(AdapterView<?> adapterView) {
                    onCameraSelect(c, -1);
                }
            });
            selCams[cam] = sel;
            l.addView(sel);

            // TextureView
            TextureView tv = new TextureView(this);
            tv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            viewCams[cam] = tv;
            l.addView(tv);

            // Button
            Button btn = new Button(this);
            btn.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            btn.setText(getString(R.string.multi_camera_info));
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    onCameraInfo(c);
                }
            });
            l.addView(btn);
        }
    }

    @Override
    protected void onPause() {
        for(int cam = 0; cam < numCams; cam++) {
            closeCamera(cam);
        }

        super.onPause();
    }

    /* close camera, remove references and reset spinner */
    private void closeCamera(int cam) {
        if(activeCamDevs[cam] != null) {
            Log("Closing.", cam);
            activeCamDevs[cam].close();
            activeCamDevs[cam] = null;
        }
        activeCamIds[cam]   = null;
        activeSurfaces[cam] = null;
    }

    /* called when a camera got selected */
    public void onCameraSelect(int cam, int index) {
        // close old camera
        if(activeCamIds[cam] != null) {
            closeCamera(cam);
        }

        if(index <= 0) {
            return;
        }

        // reject request and notify user if camera permission missing
        if(checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            selCams[cam].setSelection(0);
            Toast.makeText(this, "Camera permission not granted.", Toast.LENGTH_SHORT).show();
            return;
        }

        // open new camera
        try {
            String newId = cameraIds[index-1];
            final int newCam = cam;

            camManager.openCamera(newId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice cameraDevice) {
                    onCameraEvent(newCam, cameraDevice, 0, -1);
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice cameraDevice) {
                    onCameraEvent(newCam, cameraDevice, 1, -1);
                }

                @Override
                public void onError(@NonNull CameraDevice cameraDevice, int i) {
                    onCameraEvent(newCam, cameraDevice, 2, i);
                }
            }, null);
            activeCamIds[cam] = newId;

        } catch(Exception e) {
            Log("openDevice failed: " + e.getMessage(), cam);
        }
    }

    /* called when a camera event happened */
    public void onCameraEvent(int cam, CameraDevice dev, int event, int tag) {
        switch(event) {
            case 1:
                // onDisconnect: stop camera, reset spinner, done
                Log("Disconnected.", cam);
                closeCamera(cam);
                selCams[cam].setSelection(0);
                return;

            case 2:
                // onError: stop camera, reset spinner, done
                Log("Error " + tag + ".", cam);
                closeCamera(cam);
                selCams[cam].setSelection(0);
                return;
        }

        // onOpened: camera opened successfully
        Log("Opened successfully.", cam);

        activeCamDevs[cam] = dev;
        if(activeCamIds[cam] == null) {
            // we already closed the camera while openCamera() was pending
            Log("Opened while close in progress...", cam);
            closeCamera(cam);
        }

        // create capture session
        try {
            final int c = cam;
            Surface s = new Surface(viewCams[cam].getSurfaceTexture());
            activeSurfaces[cam] = s;
            dev.createCaptureSession(Collections.singletonList(s), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    onSessionEvent(c, 0, cameraCaptureSession);
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    onSessionEvent(c, 1, cameraCaptureSession);
                }
            }, null);

        } catch(Exception e) {
            Log("createCaptureSession failed: " + e.getMessage(), cam);
        }
    }

    public void onSessionEvent(int cam, int event, CameraCaptureSession session) {
        // session creation failed
        if(event == 1) {
            Log("failed to create CaptureSession.", cam);
            closeCamera(cam);
            selCams[cam].setSelection(0);
            return;
        }

        // onConfigured: capture session created successfully
        // set up repeating preview request
        Log("Session created successfully.", cam);

        try {
            CaptureRequest.Builder b = session.getDevice().createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(activeSurfaces[cam]);
            CaptureRequest req = b.build();
            session.setRepeatingRequest(req, new CameraCaptureSession.CaptureCallback() {}, null);

        } catch(Exception e) {
            Log("setRepeatingRequest failed: " + e.getMessage(), cam);
        }
    }

    /* called when "info" button is pressed */
    public void onCameraInfo(int cam) {
        Log("Info", cam);
    }
}
