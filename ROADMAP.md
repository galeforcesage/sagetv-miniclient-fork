# Roadmap

Planned / candidate work for the Android MiniClient.

## Media stack

### Migrate from ExoPlayer 2.x to AndroidX Media3

**Why:**
- ExoPlayer 2.x is in maintenance-only mode; active development moved to AndroidX
  Media3 (same codebase, repackaged under `androidx.media3.*`).
- **AC-4 audio** decoding requires Media3 1.2+ (late 2023). It is not available
  in any 2.x release. Now that SageTV can record ATSC 3.0 streams with HEVC and
  AC-4, this is the blocker for playing those recordings on the client.
- Media3 continues to receive HEVC quirks, HDR metadata, and ATSC 3.0–related
  fixes that 2.x will not.

**Scope:**
- Package rename across all imports: `com.google.android.exoplayer2.*` →
  `androidx.media3.*`.
- Re-fork our custom files against Media3's current versions:
  - `SagePsExtractor` (forked from `PsExtractor`, includes
    `PrivateStream1Demuxer` and `LinearPsSeekMap`).
  - `Exo2PullDataSource`, `Exo2MediaPlayerImpl`, trickplay controller, etc.
- Replace the FFmpeg extension AAR (currently 2.x in `libs/`) with the Media3
  equivalent, or rebuild from source.
- Validate stable paths (push mode, completed-recording playback, seeking,
  comskip, live recording).

**Suggested approach:**
- Fresh branch off `java-upgrade`.
- Keep both AARs side-by-side during transition.
- Migrate one module at a time (`core` is largely unaffected; the bulk lives
  in `android-shared`).
- Schedule after the current live-playback fixes have baked for a couple of
  weeks so any regressions are easier to isolate.
