#!/bin/bash
# Build hev-socks5-tunnel as a standalone executable for the root transparent proxy mode.
# Output: app/executableSo/<abi>/libhevsockstun.so (named lib*.so so the installer extracts it
# into nativeLibraryDir, where it can be executed). Adapted from v2rayNG's compile-hevtun.sh.
set -e

source "buildScript/init/env_ndk.sh"

HEV_REPO="https://github.com/heiher/hev-socks5-tunnel"
HEV_COMMIT="d9dca26c7ad0e494492244f0309e80ee583e739e"
ABIS="armeabi-v7a arm64-v8a x86 x86_64"

ROOT=$(pwd)
OUT="$ROOT/app/executableSo"
TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

git clone --quiet "$HEV_REPO" "$TMPDIR/jni/hev-socks5-tunnel"
git -C "$TMPDIR/jni/hev-socks5-tunnel" checkout --quiet "$HEV_COMMIT"
git -C "$TMPDIR/jni/hev-socks5-tunnel" submodule update --init --recursive --quiet

cat > "$TMPDIR/jni/exec.mk" <<'EXECMK'
TOP_PATH := $(call my-dir)/hev-socks5-tunnel

ifeq ($(filter $(modules-get-list),yaml),)
    include $(TOP_PATH)/third-part/yaml/Android.mk
endif
ifeq ($(filter $(modules-get-list),lwip),)
    include $(TOP_PATH)/third-part/lwip/Android.mk
endif
ifeq ($(filter $(modules-get-list),hev-task-system),)
    include $(TOP_PATH)/third-part/hev-task-system/Android.mk
endif

LOCAL_PATH := $(TOP_PATH)
SRCDIR := $(LOCAL_PATH)/src

include $(CLEAR_VARS)
include $(LOCAL_PATH)/build.mk
LOCAL_MODULE    := hevsockstun
LOCAL_SRC_FILES := $(patsubst $(SRCDIR)/%,src/%,$(SRCFILES))
LOCAL_C_INCLUDES := \
	$(LOCAL_PATH)/src \
	$(LOCAL_PATH)/src/misc \
	$(LOCAL_PATH)/src/core/include \
	$(LOCAL_PATH)/third-part/yaml/include \
	$(LOCAL_PATH)/third-part/lwip/src/include \
	$(LOCAL_PATH)/third-part/lwip/src/ports/include \
	$(LOCAL_PATH)/third-part/hev-task-system/include
LOCAL_CFLAGS += -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED
LOCAL_CFLAGS += $(VERSION_CFLAGS)
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
LOCAL_CFLAGS += -mfpu=neon
endif
LOCAL_STATIC_LIBRARIES := yaml lwip hev-task-system
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
LOCAL_LDFLAGS += -Wl,-z,common-page-size=16384
include $(BUILD_EXECUTABLE)
EXECMK

pushd "$TMPDIR" >/dev/null
"$ANDROID_NDK_HOME/ndk-build" \
  NDK_PROJECT_PATH=. \
  APP_BUILD_SCRIPT=jni/exec.mk \
  "APP_ABI=$ABIS" \
  APP_PLATFORM=android-24 \
  NDK_LIBS_OUT="$TMPDIR/libs" \
  NDK_OUT="$TMPDIR/obj" \
  "APP_CFLAGS=-O3" \
  "APP_LDFLAGS=-Wl,--build-id=none -Wl,--hash-style=gnu"
popd >/dev/null

for abi in $ABIS; do
  mkdir -p "$OUT/$abi"
  cp "$TMPDIR/libs/$abi/hevsockstun" "$OUT/$abi/libhevsockstun.so"
done
echo ">> hev-socks5-tunnel built into $OUT"
