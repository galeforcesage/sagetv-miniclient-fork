git pull the ExoPlayer repo and then make 2 changes...

1. edit core_settings.gradle and change version
2. edit build.gradle and disable javadoc
```groovy
subprojects {
    tasks.withType(Javadoc).all { enabled = false }
}
```

3. edit extensions/ffmpeg and add...
```groovy
ext {
    releaseArtifact = 'extension-ffmpeg'
    releaseDescription = 'FFMpeg extension for ExoPlayer.'
}
apply from: '../../publish.gradle'
```

## FFmpeg Extension Rebuild Workflow

### Phase 1: Keep FFmpeg line stable, rebuild for 16KB compatibility

Use this for Play compliance with minimum playback behavior change.

```bash
export FFMPEG_EXT_VERSION=2.19.1
export EXOPLAYER_VERSION=r2.19.1
export FFMPEG_VERSION=release/4.2
export NDK_TAG=r25c
export NDK_VERSION=25.2.9519653
bash ./buildffmpegext.sh all
```

This produces and deploys:

```text
libs/extension-ffmpeg-2.19.1.aar
```

### Phase 2: Upgrade FFmpeg line in a dedicated branch

Do this only after Phase 1 passes smoke tests. Example:

```bash
export FFMPEG_EXT_VERSION=2.19.1
export EXOPLAYER_VERSION=r2.19.1
export FFMPEG_VERSION=release/7.1
export NDK_TAG=r25c
export NDK_VERSION=25.2.9519653
bash ./buildffmpegext.sh all
```

Then run playback validation before replacing the shipped AAR.
