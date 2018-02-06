/**
 * Copyright (c) 2018, Sony Mobile Communications Inc.
 * Licensed under the LICENSE.
 */
package com.sony.open.cameratest;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
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
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

@SuppressWarnings({"MissingPermission", "FieldCanBeLocal"})
public class ZslReprocessActivity extends Activity {
    private CameraManager        camManager;
    private CameraDevice         camDevice;
    private CameraCaptureSession camSession;
    private final static String camId = "0";

    private final int numUnprocessedImages = 50;
    private final int numProcessedImages   = 2;

    ImageReader irPreview, irReprocess;
    ImageWriter iwReprocess;

    // state
    private boolean isReady = false;
    List<Size> possibleSizes = new LinkedList<>();
    Size       captureSize;
    List<Surface> lSurfaces;

    private class ImagePair {
        TotalCaptureResult meta;
        Image              data;
    }

    // preview stream
    List<TotalCaptureResult> unprocessedMeta = new LinkedList<>();
    List<Image>              unprocessedData = new LinkedList<>();
    List<ImagePair> unprocessedImages = new ArrayList<>(3);

    // final results
    List<TotalCaptureResult> finalMeta = new LinkedList<>();
    List<Image>              finalData = new LinkedList<>();
    List<ImagePair> finalImages = new LinkedList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_zsl_reprocess);

        Button btnCapture = findViewById(R.id.btnZslReprocessCapture);
        btnCapture.setEnabled(false);

        // finish activity and notify user if camera permission missing
        if(checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Camera permission not granted.", Toast.LENGTH_SHORT).show();
            finish();
        }

        // check camera and decide capture size
        try {
            camManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics cc = camManager.getCameraCharacteristics(camId);

            int caps[] = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if (!contains(caps, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING)) {
                throw new Exception("PRIVATE_REPROCESSING not supported.");
            }

            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size iSizes[] = map.getInputSizes(ImageFormat.PRIVATE);
            Size oSizes[] = map.getOutputSizes(ImageFormat.JPEG);
            for (Size o : oSizes) {
                for (Size i : iSizes) {
                    if (o.getHeight() == i.getHeight() && o.getWidth() == i.getWidth()) {
                        possibleSizes.add(o);
                    }
                }
            }
            int mw = 0, mh = 0;
            for (Size s : possibleSizes) {
                if (s.getHeight() > mh) {
                    mh = s.getHeight();
                    mw = s.getWidth();
                } else if (s.getHeight() == mh && s.getWidth() > mw) {
                    mw = s.getWidth();
                }
            }
            if (mw == 0 || mh == 0) {
                throw new Exception("no valid input/output size found.");
            }

            captureSize = new Size(mw, mh);
        } catch (Exception e) {
            Toast.makeText(this, "ERROR: Camera feature check failed:\n" + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if(false) {
            // start preview now or when texture becomes ready (probably racy)
            TextureView.SurfaceTextureListener stl = new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
                    startPreview();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i1) {

                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                    return false;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

                }
            };
            TextureView tvPreview = findViewById(R.id.tvZslReprocessPreview);
            if (tvPreview.getSurfaceTexture() == null) {
                tvPreview.setSurfaceTextureListener(stl);
            } else {
                startPreview();
            }
        } else {
            // start preview after user has chosen resolution
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.select_dialog_singlechoice);
            for(Size s : possibleSizes) {
                adapter.add(s.toString());
            }

            AlertDialog.Builder b = new AlertDialog.Builder(this);
            b.setTitle("Choose capture resolution");
            b.setAdapter(adapter, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    captureSize = possibleSizes.get(i);
                    startPreview();
                }
            });
            b.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialogInterface) {
                    startPreview();
                }
            });
            b.show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        closeAll();
    }

    private void startPreview() {
        // destination surfaces
        lSurfaces = new LinkedList<>();
        TextureView tvPreview    = findViewById(R.id.tvZslReprocessPreview);
        SurfaceTexture stPreview = tvPreview.getSurfaceTexture();
        //stPreview.setDefaultBufferSize(captureSize.getWidth(), captureSize.getHeight());
        stPreview.setDefaultBufferSize(1280, 720);
        lSurfaces.add(new Surface(stPreview));
        irPreview = ImageReader.newInstance(captureSize.getWidth(), captureSize.getHeight(), ImageFormat.PRIVATE, numUnprocessedImages);
        irPreview.setOnImageAvailableListener(previewImageCallback, null);
        lSurfaces.add(irPreview.getSurface());
        irReprocess = ImageReader.newInstance(captureSize.getWidth(), captureSize.getHeight(), ImageFormat.JPEG, numProcessedImages);
        irReprocess.setOnImageAvailableListener(reprocessImageCallback, null);
        lSurfaces.add(irReprocess.getSurface());

        try {
            camManager.openCamera(camId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice cameraDevice) {
                    camDevice = cameraDevice;
                    try {
                        InputConfiguration ic = new InputConfiguration(captureSize.getWidth(), captureSize.getHeight(), ImageFormat.PRIVATE);
                        cameraDevice.createReprocessableCaptureSession(ic, lSurfaces, new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                                camSession = cameraCaptureSession;
                                iwReprocess = ImageWriter.newInstance(cameraCaptureSession.getInputSurface(), 2);

                                try {
                                    CaptureRequest.Builder b = camDevice.createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG);
                                    b.addTarget(lSurfaces.get(0));  // TextureView
                                    b.addTarget(lSurfaces.get(1));  // ImageReader irPreview
                                    cameraCaptureSession.setRepeatingRequest(b.build(), previewCaptureCallback, null);
                                } catch(Exception e) {
                                    Toast.makeText(ZslReprocessActivity.this, "ERROR: Failed to create preview stream:\n" + e.getMessage(), Toast.LENGTH_LONG).show();
                                    closeAll();
                                    finish();
                                }

                                Toast.makeText(ZslReprocessActivity.this,
                                        String.format(Locale.US, "Will capture at %dx%d.", captureSize.getWidth(), captureSize.getHeight()),
                                        Toast.LENGTH_SHORT).show();
                            }

                            @Override
                            public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                                Toast.makeText(ZslReprocessActivity.this, "ERROR: Failed to configure session.", Toast.LENGTH_LONG).show();
                                closeAll();
                                finish();
                            }
                        }, null);

                    } catch(Exception e) {
                        Toast.makeText(ZslReprocessActivity.this, "ERROR: Failed to open session:\n" + e.getMessage(), Toast.LENGTH_LONG).show();
                        closeAll();
                        finish();
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice cameraDevice) {
                    Toast.makeText(ZslReprocessActivity.this, "Camera was disconnected, closing.", Toast.LENGTH_SHORT).show();
                    closeAll();
                    finish();
                }

                @Override
                public void onError(@NonNull CameraDevice cameraDevice, int err) {
                    Toast.makeText(ZslReprocessActivity.this, "Camera received error " + err + ", closing." , Toast.LENGTH_SHORT).show();
                    closeAll();
                    finish();
                }
            }, null);
        } catch(Exception e) {
            Toast.makeText(this, "ERROR: Failed to open camera:\n" + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }
    CameraCaptureSession.CaptureCallback previewCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        long now, then = 0;
        double n = 0, s = 0, sq = 0;

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);

            if(then == 0) {
                // first frame
                then = System.nanoTime();
                return;
            }

            // gather statistics
            now = System.nanoTime();
            double diff = (now - then) / 1000000.0;
            n  += 1;
            s  += diff;
            sq += diff*diff;
            then = now;

            // print and reset regularly
            if(n >= 50) {
                // https://www-user.tu-chemnitz.de/~heha/hs/mr610.htm
                double avg = s / n;
                double sfq = sq - (s*s)/n;
                double std = Math.sqrt(sfq / (n - 1));
                Log.d("SRA", String.format(Locale.US, "preview at %.2f fps (%.2f ± %.2f ms)", 1000/avg, avg, std));

                n  = 0;
                s  = 0;
                sq = 0;
            }

            unprocessedMeta.add(result);
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
            super.onCaptureFailed(session, request, failure);
            Log.w("SRA", "preview failed");
        }

        @Override
        public void onCaptureBufferLost(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull Surface target, long frameNumber) {
            super.onCaptureBufferLost(session, request, target, frameNumber);
            Log.w("SRA", "preview lost buffer");
        }
    };
    ImageReader.OnImageAvailableListener previewImageCallback = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader ir) {
            unprocessedData.add(ir.acquireNextImage());

            while(unprocessedMeta.size() >= 1 && unprocessedData.size() >= 1) {
                ImagePair nextImage = new ImagePair();
                nextImage.meta = unprocessedMeta.remove(0);
                nextImage.data = unprocessedData.remove(0);
                unprocessedImages.add(nextImage);

                if(unprocessedImages.size() > 1) {
                    ImagePair oldImage = unprocessedImages.remove(0);
                    oldImage.data.close();

                    if(!isReady) {
                        Button btn = findViewById(R.id.btnZslReprocessCapture);
                        btn.setEnabled(true);
                        isReady = true;
                    }
                }
            }
        }
    };

    public void btnCapture(View v) {
        if(!isReady || unprocessedImages.size() < 1) {
            Log.w("SRA", "Picture not taken: No unprocessed image available.");
            return;
        }

        try {
            ImagePair todo = unprocessedImages.remove(0);
            iwReprocess.queueInputImage(todo.data);
            CaptureRequest.Builder b = camDevice.createReprocessCaptureRequest(todo.meta);
            b.addTarget(lSurfaces.get(2));
            camSession.capture(b.build(), reprocessCaptureCallback, null);
        } catch(Exception e) {
            Toast.makeText(ZslReprocessActivity.this, "ERROR: Failed to reprocess:\n" + e.getMessage(), Toast.LENGTH_LONG).show();
            closeAll();
            finish();
        }
    }

    CameraCaptureSession.CaptureCallback reprocessCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            finalMeta.add(result);
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
            super.onCaptureFailed(session, request, failure);
            Log.w("SRA", "reprocess failed");
        }

        @Override
        public void onCaptureBufferLost(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull Surface target, long frameNumber) {
            super.onCaptureBufferLost(session, request, target, frameNumber);
            Log.w("SRA", "reprocess lost buffer");
        }
    };
    ImageReader.OnImageAvailableListener reprocessImageCallback = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader ir) {
            finalData.add(ir.acquireNextImage());

            while(finalMeta.size() > 0 && finalData.size() > 0) {
                ImagePair finalImage = new ImagePair();
                finalImage.meta = finalMeta.remove(0);
                finalImage.data = finalData.remove(0);

                if(finalImage.data.getFormat() == ImageFormat.JPEG) {
                    // retrieve bytes and release buffer
                    ByteBuffer buf = finalImage.data.getPlanes()[0].getBuffer();
                    byte[] jpegBytes = new byte[buf.remaining()];
                    buf.get(jpegBytes);
                    finalImage.data.close();

                    // decode and display
                    Bitmap bmpImage = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length-1);
                    ImageView ivThumbnail = findViewById(R.id.ivZslReprocessThumbnail);
                    ivThumbnail.setImageBitmap(bmpImage);
                } else {
                    Log.e("SRA", "format " + finalImage.data.getFormat() + " not supported");
                    finalImage.data.close();
                }
            }
        }
    };

    private void closeAll() {
        Button b = findViewById(R.id.btnZslReprocessCapture);
        b.setEnabled(false);

        isReady = false;
        captureSize = null;

        if(lSurfaces != null) {
            lSurfaces.clear();
            lSurfaces = null;
        }

        if(irPreview != null) {
            irPreview.close();
            irPreview = null;
        }

        if(camDevice != null) {
            if(camSession != null) {
                camSession.close();
                camSession = null;
            }
            camDevice.close();
            camDevice = null;
        }
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
