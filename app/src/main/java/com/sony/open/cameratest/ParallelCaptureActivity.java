/*
 * Copyright (c) 2018, Sony Mobile Communications Inc.
 * Licensed under the LICENSE.
 */
package com.sony.open.cameratest;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.InputConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Vector;

@SuppressWarnings({"MissingPermission", "FieldCanBeLocal"})
public class ParallelCaptureActivity extends Activity {
    private CameraManager camManager;
    private CameraDevice  camDevice;
    private String[]      camIds;

    private CameraCaptureSession captureSession;
    private boolean         isReprocessing = false;
    private Vector<Surface> surfaceList = new Vector<>();

    private ImageReader reprocessReader, finalResultReader;
    private ImageWriter reprocessWriter;

    long preview_capture, preview_start, preview_complete;
    long capture_capture, capture_start, capture_complete;
    long reproc_capture, reproc_start, reproc_complete;

    /* state machines */
    private enum eCamState { OPEN, CLOSED, OPENING, CLOSING, READY, PREVIEWING }
    private eCamState camState = eCamState.CLOSED;

    /* log to TextView */
    private List<String> logList = new LinkedList<>();
    private void Log(String line) {
        // timestamp
        Calendar now = Calendar.getInstance();
        String timestamp = String.format(Locale.US, "%02d:%02d:%02d ",
                now.get(Calendar.HOUR_OF_DAY),
                now.get(Calendar.MINUTE),
                now.get(Calendar.SECOND));

        // add new line
        logList.add(0, timestamp + line + "\n");

        // replace textview content
        TextView txtLog = findViewById(R.id.txtParallelCaptureLog);
        String text = "";
        for(String s : logList) {
            text += s;
        }
        txtLog.setText(text);
    }

    /* ============================================================== */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parallel_capture);

        Button btnParallelCapture = findViewById(R.id.btnParallelCaptureCapture);
        btnParallelCapture.setEnabled(false);

        // fetch list of CameraIds and initialize Spinner
        try {
            camManager = (CameraManager)getSystemService(Context.CAMERA_SERVICE);
            camIds  = camManager.getCameraIdList();

            List<String> camIdList = new ArrayList<>();
            camIdList.add("<none>");
            for(String camId : camIds) {
                camIdList.add("ID " + camId);
            }
            ArrayAdapter<String> camListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, camIdList);

            Spinner sel = findViewById(R.id.selParallelCaptureCamera);
            sel.setAdapter(camListAdapter);
            sel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                    onCameraSelect(i-1);
                }

                @Override
                public void onNothingSelected(AdapterView<?> adapterView) {
                    onCameraSelect(-1);
                }
            });

        } catch (Exception e) {
            Log("onCreate Exception: " + e.getMessage());
            finish();
        }
    }

    @Override
    protected void onPause() {
        Spinner sel = findViewById(R.id.selParallelCaptureCamera);
        sel.setSelection(0);

        if(camState != eCamState.CLOSED && camState != eCamState.CLOSING) {
            camState = eCamState.CLOSING;
            camDevice.close();
        }
        super.onPause();
    }

    /* ============================================================== */

    /* user selected a camera */
    public void onCameraSelect(int index) {
        // close camera if open
        if(camDevice != null) {
            if(index < 0) {
                camState = eCamState.CLOSING;
            } else {
                camState = eCamState.OPENING;
            }
            camDevice.close();
            return;
        }

        // done if <none> selected
        if(index < 0) {
            return;
        }

        // reset stuff
        logList.clear();
        preview_capture = 0;

        // reject request and notify user if camera permission missing
        if(checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Spinner sel = findViewById(R.id.selParallelCaptureCamera);
            sel.setSelection(0);
            Toast.makeText(this, "Camera permission not granted.", Toast.LENGTH_SHORT).show();
            return;
        }

        // open new camera
        try {
            camManager.openCamera(camIds[index], cameraCallbacks, null);
        } catch(Exception e) {
            Log("ERROR: Failed to open camera:\n\t" + e.getMessage());
            camState = eCamState.CLOSING;
            camDevice.close();
        }
    }

    /* user wants to take a snapshot */
    public void btnCaptureClick(View view) {
        if(camState != eCamState.PREVIEWING) {
            Log("NOTE: Camera not previewing, can't capture.");
            return;
        }

        try {
            CaptureRequest.Builder b = camDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(surfaceList.get(0));
            b.addTarget(surfaceList.get(1));
            capture_capture = System.nanoTime();
            captureSession.capture(b.build(), captureCallbacks, null);

        } catch(Exception e) {
            Log("ERROR: Failed to create capture request:\n\t" + e.getMessage());
            camState = eCamState.CLOSING;
            camDevice.close();
        }
    }

    /* camera has been opened successfully */
    public void onCameraOpen() {
        Size previewSize = new Size(1280, 720);
        //Size previewSize = new Size(4208, 3120);

        // disable reprocessing if not supported for raw/private
        CheckBox chkReprocessing = findViewById(R.id.chkReprocessing);
        isReprocessing = canReprocess(chkReprocessing.isChecked());
        chkReprocessing.setChecked(isReprocessing);
        chkReprocessing.setEnabled(false);

        // get sensor/output sizes
        Size outputSizes[];
        Rect sensorSize;
        try {
            surfaceList.clear();
            CameraCharacteristics cc = camManager.getCameraCharacteristics(camDevice.getId());
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                throw new Exception();
            }

            outputSizes = map.getOutputSizes(ImageFormat.JPEG);

            for(Size sz : outputSizes) {
                Log(String.format(Locale.US, ">> %dx%d = %.2f MPix", sz.getWidth(), sz.getHeight(), sz.getWidth()*sz.getHeight()/1000000.0));
            }

            sensorSize = cc.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (sensorSize == null) {
                throw new Exception();
            }
        } catch(Exception e) {
            Log("ERROR: Failed to get valid output sizes:\n\t" + e.getMessage());
            camState = eCamState.CLOSING;
            camDevice.close();
            return;
        }

        // set preview size and create surface
        if(!contains(outputSizes, previewSize.getWidth(), previewSize.getHeight())) {
            throw new RuntimeException("ERROR: no support for " +
                    previewSize.getWidth() + "x" + previewSize.getHeight());
        }
        TextureView    tvPreview = findViewById(R.id.tvParallelCapturePreview);
        SurfaceTexture stPreview = tvPreview.getSurfaceTexture();
        stPreview.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

        // set capture size and create surface
        Size captureSize;
        if(contains(outputSizes, sensorSize.width(), sensorSize.height())) {
            captureSize = new Size(sensorSize.width(), sensorSize.height());
        } else {
            int mw = 0, mh = 0;
            for(Size s : outputSizes) {
                if(s.getHeight() > mh) {
                    mh = s.getHeight();
                    mw = s.getWidth();
                } else if(s.getHeight() == mh && s.getWidth() > mw) {
                    mw = s.getWidth();
                }
            }
            captureSize = new Size(mw, mh);

            Log(String.format(Locale.US,
                    "INFO: no support for sensor size %dx%d",
                    sensorSize.width(), sensorSize.height()
            ));
        }
        finalResultReader = ImageReader.newInstance(captureSize.getWidth(), captureSize.getHeight(), ImageFormat.JPEG, 2);
        finalResultReader.setOnImageAvailableListener(finalResultCallback, null);

        // prepare surface list
        if(isReprocessing) {
            reprocessReader = ImageReader.newInstance(captureSize.getWidth(), captureSize.getHeight(), ImageFormat.PRIVATE, 2);
            reprocessReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader imageReader) {
                    reprocessWriter.queueInputImage(imageReader.acquireLatestImage());
                }
            }, null);


            surfaceList.add(new Surface(stPreview));
            surfaceList.add(reprocessReader.getSurface());
            surfaceList.add(finalResultReader.getSurface());

        } else {
            surfaceList.add(new Surface(stPreview));
            surfaceList.add(finalResultReader.getSurface());
        }

        // create capture session
        try {
            if(isReprocessing) {
                InputConfiguration ic = new InputConfiguration(captureSize.getWidth(), captureSize.getHeight(), ImageFormat.PRIVATE);
                camDevice.createReprocessableCaptureSession(ic, surfaceList, sessionCallbacks, null);
            } else {
                camDevice.createCaptureSession(surfaceList, sessionCallbacks, null);
            }

            Log(String.format(Locale.US,
                    "INFO: Using sizes %dx%d and %dx%d.",
                        previewSize.getWidth(), previewSize.getHeight(),
                        captureSize.getWidth(), captureSize.getHeight()
                    ));

        } catch(Exception e) {
            Log("ERROR: Failed to create capture session:\n\t" + e.getMessage());
            camState = eCamState.CLOSING;
            camDevice.close();
        }
    }

    /* ============================================================== */

    CameraDevice.StateCallback cameraCallbacks = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            camState  = eCamState.OPEN;
            camDevice = cameraDevice;
            onCameraOpen();
        }

        @Override
        public void onClosed(@NonNull CameraDevice camera) {
            Spinner sel = findViewById(R.id.selParallelCaptureCamera);

            camDevice = null;
            switch(camState) {
                case CLOSING:
                    Log("INFO: Camera closed.");
                    camState = eCamState.CLOSED;
                    sel.setSelection(0);

                    // restore UI
                    CheckBox chkReprocessing = findViewById(R.id.chkReprocessing);
                    chkReprocessing.setEnabled(true);
                    Button btnParallelCapture = findViewById(R.id.btnParallelCaptureCapture);
                    btnParallelCapture.setEnabled(false);

                    super.onClosed(camera);
                    return;

                case OPENING:
                    Log("INFO: Camera closed, re-opening.");
                    super.onClosed(camera);
                    int index = sel.getSelectedItemPosition();
                    onCameraSelect(index-1);
                    return;

                default:
                    Log("ERROR: Unknown state in onClosed()!");
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            Log("INFO: Camera disconnected, closing.");
            camState = eCamState.CLOSING;
            cameraDevice.close();
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int err) {
            Log("ERROR: Camera failed, error " + err + "!");
            camState = eCamState.CLOSING;
            cameraDevice.close();
        }
    };

    CameraCaptureSession.StateCallback sessionCallbacks = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
            camState = eCamState.READY;
            captureSession = cameraCaptureSession;

            if(isReprocessing) {
                reprocessWriter = ImageWriter.newInstance(cameraCaptureSession.getInputSurface(), 2);
            }

            // start preview
            try {
                CaptureRequest.Builder b = camDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                b.addTarget(surfaceList.get(0));
                cameraCaptureSession.setRepeatingRequest(b.build(), previewCallbacks, null);
                onActive(cameraCaptureSession);
            } catch(Exception e) {
                Log("ERROR: Failed to create preview:\n\t" + e.getMessage());
                camState = eCamState.CLOSING;
                camDevice.close();
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
            Log("ERROR: Failed to configure capture session!");
            camState = eCamState.CLOSING;
            camDevice.close();
        }

        @Override
        public void onActive(@NonNull CameraCaptureSession session) {
            super.onActive(session);
            if(camState != eCamState.READY) {
                Log("Session became active while camera was not in READY state?");
                return;
            }

            camState = eCamState.PREVIEWING;
            if(isReprocessing) {
                Log("INFO: Reprocessable session became active.");
            } else {
                Log("INFO: Session became active.");
            }

            Button btnParallelCapture = findViewById(R.id.btnParallelCaptureCapture);
            btnParallelCapture.setEnabled(true);
        }
    };

    CameraCaptureSession.CaptureCallback previewCallbacks = new CameraCaptureSession.CaptureCallback() {
        private long preview_last = 0, preview_n = 0, preview_s = 0, preview_sq = 0;

        @Override
        public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
            super.onCaptureStarted(session, request, timestamp, frameNumber);
            preview_start = System.nanoTime();
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            preview_complete = System.nanoTime();

            // reset on first frame
            if(preview_last == 0) {
                preview_last = System.nanoTime();
                preview_n  = 0;
                preview_s  = 0;
                preview_sq = 0;
                return;
            }

            // gather time statistics
            long diff    = (preview_complete - preview_last) / 1000000;
            preview_n    = preview_n  + 1;
            preview_s    = preview_s  + diff;
            preview_sq   = preview_sq + diff*diff;
            preview_last = preview_complete;

            // print statistics
            if(preview_n > 100) {
                // https://www-user.tu-chemnitz.de/~heha/hs/mr610.htm
                double avg = (double)preview_s / preview_n;
                double sfq = preview_sq - (double)(preview_s*preview_s)/preview_n;
                double std = Math.sqrt(sfq / (preview_n - 1));

//                Log(String.format(Locale.US,
//                        "PREVIEW: %.2f fps (%.2f +- %.2f ms)",
//                        1000/avg, avg, std));

                preview_n  = 0;
                preview_s  = 0;
                preview_sq = 0;
            }
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
            super.onCaptureFailed(session, request, failure);
            Log("CAPTURE: onCaptureFailed for frame " + failure.getFrameNumber() + ": " + failure.getReason());
        }

        @Override
        public void onCaptureBufferLost(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull Surface target, long frameNumber) {
            super.onCaptureBufferLost(session, request, target, frameNumber);
            Log("PREVIEW: onCaptureBufferLost");
        }
    };

    CameraCaptureSession.CaptureCallback captureCallbacks = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
            super.onCaptureStarted(session, request, timestamp, frameNumber);
            capture_start = System.nanoTime();
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            capture_complete = System.nanoTime();

            double diff1 = (capture_start - capture_capture) / 1000000;
            double diff2 = (capture_complete - capture_start) / 1000000;
            Log(String.format(Locale.US,
                    "CAPTURE: frame %d, %.2f ms -- %.2f ms",
                    result.getFrameNumber(), diff1, diff2));

            if(isReprocessing) {
                try {
                    CaptureRequest.Builder b = camDevice.createReprocessCaptureRequest(result);
                    b.addTarget(surfaceList.get(2));
                    reproc_capture = System.nanoTime();
                    session.capture(b.build(), reprocessCallbacks, null);

                } catch(Exception e) {
                    Log("CAPTURE: Failed to start reprocessing:\n\t" + e.getMessage());
                }
            }
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
            super.onCaptureFailed(session, request, failure);
            Log("CAPTURE: onCaptureFailed for frame " + failure.getFrameNumber() + ": " + failure.getReason());
        }

        @Override
        public void onCaptureBufferLost(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull Surface target, long frameNumber) {
            super.onCaptureBufferLost(session, request, target, frameNumber);
            Log("CAPTURE: onBufferLost");
        }
    };

    CameraCaptureSession.CaptureCallback reprocessCallbacks = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
            super.onCaptureStarted(session, request, timestamp, frameNumber);
            reproc_start = System.nanoTime();
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            reproc_complete = System.nanoTime();

            double diff1 = (reproc_start - reproc_capture) / 1000000;
            double diff2 = (reproc_complete - reproc_start) / 1000000;
            Log(String.format(Locale.US,
                    "REPROC: frame %d, %.2f ms -- %.2f ms",
                    result.getFrameNumber(), diff1, diff2));
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
            super.onCaptureFailed(session, request, failure);
            Log("REPROC: onCaptureFailed: " + failure.getReason());
        }

        @Override
        public void onCaptureBufferLost(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull Surface target, long frameNumber) {
            super.onCaptureBufferLost(session, request, target, frameNumber);
            Log("REPROC: onCaptureBufferLost");
        }
    };

    ImageReader.OnImageAvailableListener finalResultCallback = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader imageReader) {
            long now, then;
            Image  result = imageReader.acquireNextImage();
            int    format = result.getFormat();
            Bitmap bmpResult = null;

            if(format == ImageFormat.JPEG ) {
                then = System.nanoTime();

                ByteBuffer buf = result.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buf.remaining()];
                buf.get(bytes);
                result.close();

                // save jpg file
                try {
                    File f = new File(getFilesDir(), "capture.jpg");
                    FileOutputStream output = new FileOutputStream(f);
                    output.write(bytes);
                    output.close();

                    now = System.nanoTime();
                } catch(Exception e) {
                    Log("Can't write JPG file: " + e.getMessage());
                    return;
                }

                // decode & display jpg file
                bmpResult = BitmapFactory.decodeFile(getFilesDir() + "/capture.jpg");
                now = System.nanoTime();
                Log("FINAL: save/decode/display took " + (now - then)/1000000 + " ms");

            } else if(format == ImageFormat.YUV_420_888) {
                Log("no yuv support yet");
                result.close();
                return;
            }

            ImageView ivCapture = findViewById(R.id.ivParallelCaptureImage);
            ivCapture.setImageBitmap(bmpResult);

        }
    };

    /* ============================================================== */

    private boolean canReprocess(boolean wantReprocessing) {
        if(!wantReprocessing)
            return false;

        int caps[];
        try {
            CameraCharacteristics cc = camManager.getCameraCharacteristics(camDevice.getId());
            caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        } catch(Exception e) {
            Log("ERROR: Can't get capabilities:\n\t" + e.getMessage());
            return false;
        }

        if(!contains(caps, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
            Log("DEBUG: Reprocessing disabled (no RAW support).");
            return false;
        }

        if(!contains(caps, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING)) {
            Log("DEBUG: Reprocessing disabled (no PRIV support).");
            return false;
        }

        return true;
    }

    private boolean contains(Size[] arr, int width, int height) {
        for(Size elem : arr) {
            if(elem.getWidth() == width && elem.getHeight() == height) {
                return true;
            }
        }
        return false;
    }

    private boolean contains(int[] arr, int val) {
        for(int elem : arr) {
            if(elem == val) {
                return true;
            }
        }
        return false;
    }
}
