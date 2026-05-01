# Java & Android Upgrade Changes

This document details all changes made to upgrade the SageTV MiniClient from Java 8 / Android SDK 29 / Gradle 6 to Java 17 / Android SDK 34 / Gradle 8.

---

## Build System & Toolchain

### Gradle Wrapper (`gradle/wrapper/gradle-wrapper.properties`)
- Upgraded Gradle from **6.1.1** to **8.7**

### Root `build.gradle`
- Upgraded Android Gradle Plugin (AGP) from **4.0.2** to **8.3.2**
- Upgraded Google Services plugin from **4.3.10** to **4.4.1**
- Upgraded Firebase Crashlytics Gradle plugin from **2.8.1** to **2.9.9**
- Removed deprecated `jcenter()` repository (replaced by `mavenCentral()`)
- Removed `org.robovm:robovm-gradle-plugin` dependency (no longer used)
- Updated SDK versions:
  - `androidBuildToolsVersion`: 29.0.2 → **34.0.0**
  - `androidCompileSdkVersion`: 29 → **34**
  - `androidTargetSdkVersion`: 30 → **34**
- Updated Java version constants: 1.8 → **17**

### `settings.gradle`
- Added `pluginManagement` block with `google()`, `mavenCentral()`, and `gradlePluginPortal()` repositories (required by Gradle 8)

### `gradle.properties`
- Removed deprecated `android.enableDexingArtifactTransform=false`
- Added `android.defaults.buildfeatures.buildconfig=true` (AGP 8 requires explicit opt-in)
- Added `android.nonTransitiveRClass=false` (preserves existing R class behavior)
- Removed deprecated JVM arg `-XX:MaxPermSize=4096m` (removed in Java 8+)

### `core/build.gradle`
- Changed plugin from `java` to `java-library` (needed for `api` dependency scope)
- Updated `sourceCompatibility` and `targetCompatibility` from 1.8 to **17**
- Replaced deprecated `jar.baseName` with `archiveBaseName` inside `jar {}` block
- Changed `compile`/`implementation` dependencies to `api` for transitive exposure to dependent modules:
  - `com.jcraft:jzlib`
  - `org.slf4j:slf4j-api`
  - `org.nanohttpd:nanohttpd`
  - `org.ostermiller:utils`

### `android-shared/build.gradle`
- Added `namespace 'sagex.miniclient.android'` (AGP 8 requires namespace in build.gradle instead of AndroidManifest.xml)
- Updated `sourceCompatibility`/`targetCompatibility` from 1.8 to `JavaVersion.VERSION_17`
- Removed duplicate nested `android {}` / `compileOptions {}` blocks; consolidated into single declarations
- Replaced deprecated `lintOptions` with `lint` block
- Added `packaging.resources.excludes` for `META-INF/INDEX.LIST` and `META-INF/DEPENDENCIES`
- Added `androidx.media:media:1.7.0` dependency (required for MediaSession support)
- Removed `@aar` suffix from all ExoPlayer dependencies (allows transitive dependency resolution)

### `android-tv/build.gradle`
- Added `namespace 'sagex.miniclient.android.tv'`
- Removed duplicate nested `android {}` block; consolidated `compileOptions`
- Updated `sourceCompatibility`/`targetCompatibility` to `JavaVersion.VERSION_17`
- Added `packaging.resources.excludes` for `META-INF/INDEX.LIST` and `META-INF/DEPENDENCIES`

---

## Android Manifest Changes

### `android-shared/src/main/AndroidManifest.xml`
- Removed `package` attribute from `<manifest>` (now set via `namespace` in build.gradle)
- Changed `android:allowBackup` from `true` to `false` (security best practice)
- Added `android:usesCleartextTraffic="true"` (required on SDK 28+ for non-HTTPS network traffic to SageTV server)

### `android-tv/src/main/AndroidManifest.xml`
- Removed `package` attribute from `<manifest>`
- Changed `android:allowBackup` from `true` to `false` with `tools:replace`
- Added `android:exported="true"` to `ServersActivity` and the leanback launcher activity (required by Android 12+ for activities with intent filters)
- Added `<provider>` block to remove `FirebaseInitProvider` via `tools:node="remove"` (prevents crash when using placeholder `google-services.json`)

---

## Application Code Changes

### Firebase / Crashlytics Safety (`Logger.java`, `MiniclientApplication.java`)
- **`MiniclientApplication.java`**: Wrapped Firebase Crashlytics initialization in try/catch to handle cases where Firebase is not properly configured (e.g., placeholder `google-services.json`)
- **`Logger.java`**: 
  - Added lazy-initialized static `getCrashlytics()` method with null fallback
  - All Crashlytics calls throughout the class now check for null before invoking
  - This prevents crashes when Firebase is not available

### Deprecated API Replacements

#### MediaCodecList (`AndroidMiniClientOptions.java`, `AppUtil.java`, `CodecSelector.java`)
- Replaced deprecated static `MediaCodecList.getCodecCount()` / `MediaCodecList.getCodecInfoAt(i)` with instance-based `new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()` across all files that enumerate codecs

#### Build.CPU_ABI (`MiniclientApplication.java`)
- Replaced deprecated `Build.CPU_ABI` with `Build.SUPPORTED_ABIS[0]`

### Rendering Thread Safety (`MiniClientGDXRenderer.java`, `OpenGLRenderer.java`)
- Changed render loop to snapshot the `renderQueue` inside the synchronized block, then release the lock before executing render operations
- Previously, the entire render loop (including GPU operations) held the lock, blocking the UI thread from adding new render commands
- Pattern: `synchronized(renderQueue) { snapshot = new ArrayList<>(renderQueue); renderQueue.clear(); }` then iterate `snapshot` outside the lock

### OpenGL Texture Optimization (`OpenGLTexture.java`)
- Added UV coordinate dirty-checking: caches source rect parameters (`sx`, `sy`, `sw`, `sh`) and only recalculates UV data when they change
- Avoids redundant `FloatBuffer.put()` calls when the same texture region is drawn repeatedly

### Image Cache (`ImageCache.java`)
- Changed `lruImageMap` from `HashMap` to `LinkedHashMap` with access-order mode (`true` in constructor)
- Simplified `getOldestImage()` to return the first entry (which is the least-recently-accessed) instead of scanning the entire map

### Logging Improvements (`MiniClientGDXRenderer.java`, `OpenGLRenderer.java`)
- Replaced string concatenation in log statements with SLF4J parameterized messages (`{}` placeholders) for better performance when logging is disabled

### ExoPlayer Improvements

#### Custom Media Codec Selector (`CustomMediaCodecSelector.java`)
- Improved codec selection logging: now reports whether selected codec is hardware or software, and logs fallback codecs at debug level
- Detects hardware codecs by checking for `OMX.google.` and `c2.android.` name prefixes

#### Audio Sink (`Exo2MediaPlayerImpl.java`)
- Added custom `AudioSink` builder override in `DefaultRenderersFactory` using `DefaultAudioSink.Builder` pattern
- Configures audio capabilities, float output, playback params, and offload mode

#### Playback Position (`Exo2MediaPlayerImpl.java`)
- Replaced `ReentrantLock`-based playback position access with `volatile` field
- Simplified `getPlaybackPosition()` and `setPlaybackPosition()` — the lock was unnecessary overhead for a single long field where volatile provides sufficient visibility guarantees

#### MediaSession Safety (`Exo2MediaPlayerImpl.java`)
- Wrapped `MediaSessionCompat` creation in try/catch with null fallback
- Added null checks before all `mediaSession` method calls
- Added video size logging when decoder becomes active

### IJKPlayer Improvements (`IJKMediaPlayerImpl.java`, `CodecSelector.java`)
- **`IJKMediaPlayerImpl.java`**: Added three new IJKPlayer options:
  - `mediacodec-all-videos`: enables hardware decode for all video codecs (MPEG4, VP8/VP9, etc.)
  - `mediacodec-auto-rotate`: lets hardware decoder handle rotation
  - `mediacodec-handle-resolution-change`: handles resolution changes without software fallback
- **`CodecSelector.java`**: Updated codec enumeration to use `MediaCodecList` instance API; improved codec selection logging with hardware detection

### Network & I/O Improvements

#### Push Buffer (`PushBufferDataSource.java`)
- Increased `PIPE_SIZE` from 4 MB to **8 MB** for better streaming throughput
- Restructured `read()` method: avoids calling `in.available()` twice; eliminates redundant zero-length read attempts
- Added 1ms sleep in the empty-buffer polling path to prevent CPU spin-lock and give the push thread time to write data
- Properly handles `InterruptedException` by restoring the thread's interrupt flag

#### Pull Data Source (`SimplePullDataSource.java`)
- Wrapped socket output stream with `BufferedOutputStream` for reduced system call overhead

#### Regex Compilation (`MiniClientConnection.java`)
- Pre-compiled the comma-split regex pattern (`\\s*,\\s*`) as a static `Pattern` constant instead of recompiling on every call

### Security Improvements

#### Path Traversal Prevention (`MiniClientConnection.java`)
- Added `isValidFsPath()` method that rejects paths containing `..` sequences
- Added `getSafeFsPath()` wrapper that validates paths from server commands before use
- Applied path validation to all filesystem commands: `FSCMD_CREATE_DIRECTORY`, `FSCMD_GET_FILE_SIZE`, `FSCMD_DELETE_FILE`, `FSCMD_GET_PATH_ATTRIBUTES`, `FSCMD_GET_PATH_MODIFIED_TIME`, `FSCMD_DIR_LIST`, and `FSCMD_DOWNLOAD_FILE`/`FSCMD_UPLOAD_FILE`
- Returns `FS_RV_NO_PERMISSIONS` for rejected paths

#### Secure Random (`ClientIDGenerator.java`)
- Replaced `java.util.Random` with `java.security.SecureRandom` for client ID generation (cryptographically secure random number generator)

---

## Files Changed Summary

| File | Type of Change |
|------|---------------|
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 6.1.1 → 8.7 |
| `build.gradle` | AGP 8.3.2, SDK 34, Java 17, remove jcenter |
| `settings.gradle` | Add pluginManagement block |
| `gradle.properties` | Remove deprecated props, add AGP 8 flags |
| `core/build.gradle` | java-library plugin, Java 17, api dependencies |
| `android-shared/build.gradle` | Namespace, Java 17, lint, media dep |
| `android-tv/build.gradle` | Namespace, Java 17, packaging |
| `android-shared/src/main/AndroidManifest.xml` | Remove package, allowBackup, cleartext |
| `android-tv/src/main/AndroidManifest.xml` | exported, Firebase provider removal |
| `MiniclientApplication.java` | Firebase safety, deprecated API fix |
| `Logger.java` | Crashlytics null safety |
| `AndroidMiniClientOptions.java` | MediaCodecList API update |
| `AppUtil.java` | MediaCodecList API update |
| `MiniClientGDXRenderer.java` | Render thread safety, SLF4J logging |
| `OpenGLRenderer.java` | Render thread safety, SLF4J logging |
| `OpenGLTexture.java` | UV dirty-checking optimization |
| `ImageCache.java` | LinkedHashMap LRU, simplified lookup |
| `CustomMediaCodecSelector.java` | Improved codec logging |
| `Exo2MediaPlayerImpl.java` | AudioSink builder, volatile position, MediaSession safety |
| `CodecSelector.java` | MediaCodecList API, codec logging |
| `IJKMediaPlayerImpl.java` | Additional HW decode options |
| `MiniClientConnection.java` | Path traversal prevention, regex caching |
| `PushBufferDataSource.java` | Larger buffer, spin-lock fix |
| `SimplePullDataSource.java` | BufferedOutputStream |
| `ClientIDGenerator.java` | SecureRandom |
