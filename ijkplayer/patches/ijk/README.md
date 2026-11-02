# Sage IJK patch series

These patches are ordered and intentionally small. The external source is
Bilibili IJKPlayer commit `cced91e3ae3730f5c63f3605b00d25eafcf5b97b`
(`k0.8.8`).

| Patch | Target | Purpose |
|---|---|---|
| `0001` | IJK checkout | Keep `-std=c99` on C compilation units only. |
| `0002` | Legacy IJK FFmpeg checkout | Historical Clang `B0` macro fix; retained for provenance but obsolete with official FFmpeg 6.1.1. |
| `0003` | IJK checkout | Replace removed GCC standalone-toolchain assumptions with NDK r28 LLVM tools and repair obsolete C declarations/includes rejected by Clang 19. Legacy ARMv7 assembly is disabled because this FFmpeg version's syntax is rejected by the modern assembler; ARM64 NEON remains enabled. |
| `0004` | Legacy IJK FFmpeg checkout | Historical unindexed-seek guard; retained for provenance but superseded by the guarded FFmpeg 6.1.1 implementation. |
| `0005` | SageTV-NG repository | Use a bounded seek target to make IJK flush stale push packet/decoder queues. |
| `0006` | IJK checkout | Enable flexible pages and 16 KB max/common page-size linking for FFmpeg, IJK, and SDL. |
| `0007` | IJK checkout | Port IJK's player, MediaCodec pipeline, async I/O, metadata, and custom protocols to direct FFmpeg 6.1.1 APIs. |

`build-ijk.sh` restores only patch-owned files from their pinned revisions,
bootstraps official FFmpeg 6.1.1 separately, then applies the named series
with forward checks.
This keeps reruns deterministic even when a patch evolves. A patch that no
longer applies cleanly stops the build. The script also verifies that
repository-side patch `0005` is present.
