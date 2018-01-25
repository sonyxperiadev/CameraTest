# CameraTest application

Test application used to learn the Camera2 API and investigate the
behaviour of different Android camera stacks.

This application consists of multiple independent modules.

## Multi Camera

The Camera2 API allows multiple cameras to be opened simultaneously,
which this module allows the user to do. Each open camera runs a
TEMPLATE\_PREVIEW repeating request outputting into a TextureView.

Change _numCams_ to reflect the maximum number of cameras supported.

## Parallel Capture

Within a single camera session, multiple requests can be issued, e.g.
a repeating request for (low-resolution) preview and a normal request
to capture a high-resolution image.

This module runs a TEMPLATE\_PREVIEW repeating request and allows the
user to take a picture at maximum resolution (TEMPLATE\_STILL\_CAPTURE)
with a button press without interrupting this preview session.

Additionally, it allows using the Camera2 API *Reprocessing* feature
to be used. In this case, instead of using a JPEG surface, a PRIVATE
surface will be used to capture the image; this image will then be
handed down to be reprocessed into a JPEG image.

## Zero Shutter Lag (ZSL)

The Camera2 API supports ZSL through constantly capturing
high-resolution images (TEMPLATE\_ZERO\_SHUTTER\_LAG), and
reprocessing only images to be kept. This mode of operation is
exercised by this module.

## Test Mode

This module is used for testing.
