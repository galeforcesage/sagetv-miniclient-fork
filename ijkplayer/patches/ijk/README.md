# Sage IJK patch series

These patches are ordered and intentionally small. The external source is
Bilibili IJKPlayer commit `cced91e3ae3730f5c63f3605b00d25eafcf5b97b`
(`k0.8.8`).

| Patch | Target | Purpose |
|---|---|---|
| `0001` | IJK checkout | Keep `-std=c99` on C compilation units only. |
| `0002` | Each IJK FFmpeg checkout | Avoid Clang's Linux `B0` macro collision. |
| `0003` | IJK checkout | Replace removed GCC standalone-toolchain assumptions with NDK r28 LLVM tools and repair obsolete C declarations/includes rejected by Clang 19. Legacy ARMv7 assembly is disabled because this FFmpeg version's syntax is rejected by the modern assembler; ARM64 NEON remains enabled. |
| `0004` | Each IJK FFmpeg checkout | Reject binary seeks on unindexed push/pipe streams instead of dereferencing a null index. |
| `0005` | SageTV-NG repository | Use a bounded seek target to make IJK flush stale push packet/decoder queues. |
| `0006` | IJK checkout | Enable flexible pages and 16 KB max/common page-size linking for FFmpeg, IJK, and SDL. |

`build-ijk.sh` restores only patch-owned files from their pinned revisions
after `init-android.sh`, then applies the named series with forward checks.
This keeps reruns deterministic even when a patch evolves. A patch that no
longer applies cleanly stops the build. The script also verifies that
repository-side patch `0005` is present.
