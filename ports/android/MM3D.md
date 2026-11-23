# Build MM3D-Android

MM3D-Android uses TriAevum's **Moonfall Curse** Android flavor (`mc`), whose package ID is `org.triaevum.moonfallcurse`. The APK contains the ARM64 runtime, a precompiled title module and Forge. On the phone, Forge uses the system file picker and prepares the game from your own decrypted **EUR** cartridge dump matching the [supported revision catalog](../../titles/mm3d/forge/supported_revisions.json). The USA v1.1 dump documented by MM3D-Mobile was used with the older prototype.

## Prepare the publisher build

Use a Linux x86-64 host with Android SDK Platform 35, NDK r29, JDK 17, Gradle 8.13, CMake 3.30 or newer, Ninja, Python 3, Rust/Cargo with the `aarch64-linux-android` target, and ADB.

The build needs the verified translated sources and reference package from the matching Moonfall Curse desktop release, its full source commit ID and the generated A32 registry directory `a32_generated_overlay_r18`. Run this from the checkout root after setting the indicated paths and commit:

```bash
python3 tools/android/android_release.py --title mm3d \
  --ndk "$ANDROID_NDK_HOME" \
  --work "$MM3D_BUILD" \
  --translated-sources "$MM3D_TRANSLATED_SOURCES" \
  --a32-registry "$MM3D_A32_REGISTRY" \
  --reference-package "$MM3D_DESKTOP_PACKAGE" \
  --source-commit "$MM3D_SOURCE_COMMIT" \
  --gradle "$GRADLE"
```

Use a build directory outside the checkout. The command prints the build recipe. Append `--run` to execute it after setting up the signing inputs in the [Android release guide](../../docs/TRIAEVUM_ANDROID_RELEASE.md). Gradle uses `:app:assembleMcRelease` and writes its APK under `ports/android/app/build/outputs/apk/mc/release/`. Follow the release guide to audit the package before installing it. Forge verifies the signature of its install package on the phone.

## Install and verify on a phone

Enable USB debugging, connect the phone and accept the ADB prompt. For a signed APK, set `MM3D_APK` to its path:

```bash
adb devices -l
adb install -r "$MM3D_APK"
```

Open **Moonfall Curse** and select your own compatible dump. The installer can prepare known shaders and Vulkan pipelines before you play. This option starts enabled and makes installation take longer, uses extra cache space and may warm up the phone. Turn it off to skip the optional device-pipeline warmup; Forge still prepares the required game data and portable shader pack.

On a test device, verify first launch, gameplay, saving and reloading, overlay editing, controller input and background/resume. The Android release guide tracks the remaining qualification checks.

If startup fails, collect the app's diagnostics or read the native logs:

```bash
MM3D_DATA=/sdcard/Android/data/org.triaevum.moonfallcurse/files
adb shell cat "$MM3D_DATA/data/logs/native-stdout.log"
adb shell cat "$MM3D_DATA/data/logs/native-stderr.log"
```

## Standalone Vulkan diagnostic

The `triaevum_android_nri_present_probe` target builds an ARM64 NativeActivity library that draws a triangle and tests Vulkan swapchain presentation independently of a ROM. Set `NATIVE_PROBE_BUILD` to a fresh build directory outside the checkout, then configure and build the probe:

```bash
cmake -S ports/android/native -B "$NATIVE_PROBE_BUILD" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 \
  -DANDROID_STL=c++_shared -DTRIAEVUM_ANDROID_BUILD_NRI=ON \
  -DTRIAEVUM_ANDROID_BUILD_NRI_PRESENT_PROBE=ON
cmake --build "$NATIVE_PROBE_BUILD" --target triaevum_android_nri_present_probe
```

A NativeActivity test APK must bundle the library, the matching ARM64 `libc++_shared.so` and a native activity with `android.app.lib_name` set to `triaevum_android_nri_present_probe`. Check the resulting `NriPresentProbe` messages in logcat. See [Device Verification](README.md#device-verification) for the separate NRI device/queue probe.

## Private MM3D-Mobile diagnostics

`tools/android/prepare_mm3d_data.py` stages a completed legacy MM3D-PC Setup installation for offline testing with a compatible developer runtime. It copies private inputs and writes a launch profile, so run it only with an output directory outside the checkout and separate from the installation:

```bash
python3 tools/android/prepare_mm3d_data.py \
  --installation "$MM3D_LEGACY_INSTALL" --output "$MM3D_LEGACY_STAGE"
```

The legacy staging directory contains ROM-derived data. Keep it private and use the Forge workflow above for Moonfall Curse.

## Mesa Turnip on compatible Adreno GPUs

The launcher offers **GPU driver** on builds with Turnip support. Choose
**Import Turnip ZIP** and select an AdrenoTools-compatible package from a
trusted source. The ZIP must contain one ARM64 driver or a `meta.json` with
`libraryName` beside the selected library. The game validates Vulkan 1.2 and
the Mesa Turnip driver identity during its next start. If the probe fails
normally, the runtime uses system Vulkan and records
`turnip_unavailable_using_system`. If the driver crashes during startup,
open the launcher again and select **Use system driver**.

Turnip is optional. Forge continues to prepare pipelines with system Vulkan,
and gameplay keeps Turnip's device pipeline caches in a separate directory.
Portable SPIR-V keeps the shared cache. Android builds include driver support
by default. A fresh system-only native build can disable it with
`-DTRIAEVUM_ANDROID_TURNIP=OFF`. The release staging step includes the loader
and its two hook libraries when present.

For a device comparison, use the same graphics preset and scene with each
driver. Record time to first frame, frame times for the first and repeated
runs, and any startup or presentation errors. Include the phone, Android
version, GPU and Turnip package version.

## Building without the portable shader corpus

Pass `--compile-shaders-on-device` to `tools/android/android_release.py` for a
smaller APK. Forge still checks and imports the cartridge dump. The runtime
compiles missing shaders on the device and keeps a persistent shader cache.
New effects and areas can stutter while the cache is cold. The installer
offers device pipeline prewarming only when the package includes its recipes.

On Android Adreno 610, the Vulkan renderer skips optional interactive grass,
screen-space reflections, contact shadows and NRI upscaling. This avoids
several compute shaders with workgroup barriers while keeping native GPU
rendering. Check for `vulkan_adreno610_compatibility`, `renderer_ready` and
`first_frame_presented` in the startup report when testing this GPU.
