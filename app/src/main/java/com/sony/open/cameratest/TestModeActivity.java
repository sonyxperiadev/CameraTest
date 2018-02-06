/**
 * Copyright (c) 2018, Sony Mobile Communications Inc.
 * Licensed under the LICENSE.
 */
package com.sony.open.cameratest;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Toast;

import java.util.Locale;
import java.util.Vector;

@SuppressWarnings({"MissingPermission", "FieldCanBeLocal"})
public class TestModeActivity extends Activity {
    private CameraManager        camManager = null;
    private CameraDevice         camDevice  = null;
    private CameraCaptureSession camSession = null;

    private final static String  camId = "0";
    private final static boolean logAllFrames = true;
    private final static boolean captureToTextureView = true;

    private boolean resumed = false, surface = false, surface2 = false, ready = false;

    private Vector<Surface> surfaceList = new Vector<>();
    private TextureView tvPreview, tvCapture;
    private ImageReader imgReader;

    private int capWidth, capHeight;
    private long last, now, snapReq;
    private int showFrames = 0;

    private void MyLog(String msg) {
        Log.e("SRA", msg);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test_mode);

        // finish activity and notify user if camera permission missing
        if(checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Camera permission not granted.", Toast.LENGTH_SHORT).show();
            finish();
        }

        try {
            camManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics cc = camManager.getCameraCharacteristics(camId);
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] outputSizes = map.getOutputSizes(ImageFormat.JPEG);

            int mw = 0, mh = 0;
            for(Size s : outputSizes) {
                if(s.getWidth() > mw) {
                    mw = s.getWidth();
                    mh = s.getHeight();
                } else if(s.getWidth() == mw && s.getHeight() > mh) {
                    mh = s.getHeight();
                }
            }
            capWidth = mw;
            capHeight = mh;

        } catch(Exception e) {
            MyLog("failed to get size: " + e.getMessage());
        }

        tvPreview = findViewById(R.id.tvTestModePreview);
        tvPreview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
                surface = true;
                startCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i1) {

            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                surface = false;
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

            }
        });

        tvCapture = findViewById(R.id.tvTestModeCapture);
        tvCapture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
                surface2 = true;
                startCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i1) {

            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                surface2 = false;
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
                Log.e("SRA", "received snapshot image data");
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        resumed = true;
        startCamera();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ready = false;
        resumed = false;
        if(camDevice != null) {
            camDevice.close();
        }
    }

    void startCamera() {
        if(!resumed || !surface || !surface2)
            return;

        SurfaceTexture stPreview = tvPreview.getSurfaceTexture();
        stPreview.setDefaultBufferSize(1280, 720);

        surfaceList.clear();
        surfaceList.add(new Surface(stPreview));

        if(captureToTextureView) {
            /* capture into TextureView */
            SurfaceTexture stCapture = tvCapture.getSurfaceTexture();
            stCapture.setDefaultBufferSize(capWidth, capHeight);
            surfaceList.add(new Surface(stCapture));
            MyLog("snapshot into TextureView at " + capWidth + "x" + capHeight);
        } else {
            /* capture into ImageReader */
            imgReader = ImageReader.newInstance(capWidth, capHeight, ImageFormat.JPEG, 2);
            imgReader.setOnImageAvailableListener(snapshotImageCallback, null);
            surfaceList.add(imgReader.getSurface());
            MyLog("snapshot into ImageReader at " + capWidth + "x" + capHeight);
        }

        try {
            camManager.openCamera(camId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice cameraDevice) {
                    camDevice = cameraDevice;
                    try {
                        camDevice.createCaptureSession(surfaceList, new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                                camSession = cameraCaptureSession;

                                try {
                                    CaptureRequest.Builder b = camDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                    b.addTarget(surfaceList.get(0));
                                    camSession.setRepeatingRequest(b.build(), previewCallback, null);
                                    now = System.nanoTime();
                                    ready = true;
                                    if(logAllFrames) {
                                        showFrames = 1;
                                    }
                                } catch(Exception e) {
                                    MyLog("create preview failed: " + e.getMessage());
                                }
                            }

                            @Override
                            public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                                MyLog("configure failed");
                            }
                        }, null);
                    } catch (Exception e) {
                        MyLog("session create failed: " + e.getMessage());
                        camDevice = null;
                        finish();
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice cameraDevice) {
                    MyLog("camera disconnected");
                    camDevice = null;
                    finish();
                }

                @Override
                public void onError(@NonNull CameraDevice cameraDevice, int i) {
                    MyLog("camera failed: " + i);
                    camDevice = null;
                    finish();
                }
            }, null);
        } catch(Exception e) {
            MyLog("open failed: " + e.getMessage());
        }
    }

    public void btnTestModeClick(View v) {
        if(!ready)
            return;

        showFrames = 1;
        try {
            MyLog("request snapshot");
            snapReq = System.nanoTime();
            CaptureRequest.Builder b = camDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(surfaceList.get(0));
            b.addTarget(surfaceList.get(1));
            camSession.capture(b.build(), snapshotCallback, null);
        } catch(Exception e) {
            MyLog("take snapshot failed: " + e.getMessage());
        }
    }

    CameraCaptureSession.CaptureCallback previewCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            last = now; now = System.nanoTime();
            if(logAllFrames || showFrames > 0) {
                MyLog(String.format(Locale.US, "received preview - %d ms - id %d", (now - last) / 1000000, result.getFrameNumber()));
                if(showFrames > 1) {
                    showFrames = 0;
                }
            }
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
            super.onCaptureFailed(session, request, failure);
            MyLog("lost preview");
        }
    };

    CameraCaptureSession.CaptureCallback snapshotCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
            super.onCaptureProgressed(session, request, partialResult);
            MyLog("capture progressed");
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            last = now; now = System.nanoTime();
            showFrames = 2;
            MyLog(String.format(Locale.US, "received snapshot - %d ms - id %d (total %d ms)",
                    (now - last) / 1000000,
                    result.getFrameNumber(),
                    (System.nanoTime() - snapReq) / 1000000));
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
            super.onCaptureFailed(session, request, failure);
            MyLog("lost snapshot");
        }
    };

    ImageReader.OnImageAvailableListener snapshotImageCallback = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader imageReader) {
            MyLog("received snapshot image data");
        }
    };
}
