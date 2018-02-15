# Copyright (c) 2018, Sony Mobile Communications, Inc.
# Licensed under the LICENSE.

LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := \
    $(call all-java-files-under, app/src/main/java)

LOCAL_MANIFEST_FILE := app/src/main/AndroidManifest.xml
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/app/src/main/res

LOCAL_PACKAGE_NAME := CameraTest
LOCAL_SDK_VERSION := current
LOCAL_CERTIFICATE := platform
LOCAL_STATIC_JAVA_LIBRARIES := android-support-v4

include $(BUILD_PACKAGE)
