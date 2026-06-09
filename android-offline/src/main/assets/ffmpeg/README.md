Bundled FFmpeg Binary Layout

Place executable FFmpeg binaries at:

- ffmpeg/arm64-v8a/ffmpeg
- ffmpeg/armeabi-v7a/ffmpeg

Notes:

- File name must be exactly: ffmpeg
- Binary must be executable-compatible for the target ABI
- Keep binary scope minimal for licensing/size; use tools/ffmpeg/build-android-ffmpeg.sh profiles
- Build script now produces an ffmpeg executable (not just shared libs)
- Use tools/ffmpeg/install-bundled-ffmpeg.sh to copy built binaries into these asset paths

Runtime behavior:

- Preferred chain is MP4 copy (Exo) -> MKV copy (bundled FFmpeg).
- Bundled FFmpeg MP4 remux is retained as a final fallback.
- FFmpeg remux commands try:
  1) -bsf:a aac_adtstoasc
  2) retry without the bsf
  3) keep video copy and transcode audio to AAC (MP4 fallback path)
