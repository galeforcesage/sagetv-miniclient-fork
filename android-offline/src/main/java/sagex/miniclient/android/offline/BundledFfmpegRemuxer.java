/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package sagex.miniclient.android.offline;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs a bundled ffmpeg binary for TS/PS -> MP4/MKV remux.
 *
 * Preferred package layout:
 *   src/main/jniLibs/<abi>/libffmpegexec.so
 *
 * Legacy fallback layout:
 *   assets/ffmpeg/<abi>/ffmpeg
 */
final class BundledFfmpegRemuxer {

    private static final Logger log = LoggerFactory.getLogger(BundledFfmpegRemuxer.class);
    @Nullable
    private static volatile String cachedExecutablePath;
    private static final String NATIVE_BINARY_NAME = "libffmpegexec.so";

    private BundledFfmpegRemuxer() {}

    static boolean isAvailable(Context context) {
        return findNativeBundledBinary(context) != null
                || pickBundledAbi(context.getAssets()) != null;
    }

    @Nullable
    static String probeFirstVideoCodec(Context context, Uri source, @Nullable String mediaFileId)
            throws IOException, InterruptedException {
        if (!"file".equals(source.getScheme())) {
            return null;
        }
        String path = source.getPath();
        if (path == null || path.isEmpty()) {
            return null;
        }
        File input = new File(path);
        if (!input.isFile() || input.length() <= 0) {
            return null;
        }

        String ffmpegPath = ensureBundledBinary(context);
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-hide_banner");
        cmd.add("-i");
        cmd.add(input.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output;
        try (InputStream is = p.getInputStream()) {
            output = readTail(is, 12000);
        }
        boolean finished = p.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            log.warn("ffmpeg_probe_timeout mediaFileID={} input={}", mediaFileId, input.getAbsolutePath());
            return null;
        }

        String codec = parseFirstVideoCodec(output);
        log.info("ffmpeg_probe_video mediaFileID={} input={} codec={} rc={}",
                mediaFileId, input.getAbsolutePath(), codec, p.exitValue());
        return codec;
    }

    static void remuxToMp4(Context context, Uri source, File outputMp4, @Nullable String mediaFileId)
            throws IOException, InterruptedException {
        if (outputMp4.exists() && !outputMp4.delete()) {
            throw new IOException("Cannot overwrite output: " + outputMp4);
        }
        File parent = outputMp4.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create output directory: " + parent);
        }

        String ffmpegPath = ensureBundledBinary(context);
        File tempInput = prepareInputFile(context, source, mediaFileId);
        boolean deleteTempInput = !"file".equals(source.getScheme());

        try {
            // Preferred path for TS AAC in ADTS framing.
            boolean ok = executeRemux(ffmpegPath, tempInput.getAbsolutePath(), outputMp4.getAbsolutePath(), true);
            if (!ok) {
                // Some streams are not AAC. Retry without audio bitstream filter.
                ok = executeRemux(ffmpegPath, tempInput.getAbsolutePath(), outputMp4.getAbsolutePath(), false);
            }
            if (!ok) {
                throw new IOException("Bundled ffmpeg remux failed");
            }
        } finally {
            if (deleteTempInput) {
                //noinspection ResultOfMethodCallIgnored
                tempInput.delete();
            }
        }
    }

    static void remuxToMkv(Context context, Uri source, File outputMkv, @Nullable String mediaFileId)
            throws IOException, InterruptedException {
        if (outputMkv.exists() && !outputMkv.delete()) {
            throw new IOException("Cannot overwrite output: " + outputMkv);
        }
        File parent = outputMkv.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create output directory: " + parent);
        }

        String ffmpegPath = ensureBundledBinary(context);
        File tempInput = prepareInputFile(context, source, mediaFileId);
        boolean deleteTempInput = !"file".equals(source.getScheme());

        try {
            boolean ok = executeRemuxToMkv(ffmpegPath, tempInput.getAbsolutePath(), outputMkv.getAbsolutePath());
            if (!ok) {
                throw new IOException("Bundled ffmpeg MKV remux failed");
            }
        } finally {
            if (deleteTempInput) {
                //noinspection ResultOfMethodCallIgnored
                tempInput.delete();
            }
        }
    }

    private static String ensureBundledBinary(Context context) throws IOException {
        if (cachedExecutablePath != null) {
            File cached = new File(cachedExecutablePath);
            if (cached.exists() && cached.canExecute()) {
                return cached.getAbsolutePath();
            }
            cachedExecutablePath = null;
        }

        File nativeBinary = findNativeBundledBinary(context);
        if (nativeBinary != null) {
            if (canExecuteBinary(nativeBinary)) {
                cachedExecutablePath = nativeBinary.getAbsolutePath();
                log.info("ffmpeg_native_binary_ready path={}", cachedExecutablePath);
                return cachedExecutablePath;
            }
            log.warn("ffmpeg_native_binary_not_executable path={}",
                    nativeBinary.getAbsolutePath());
        }

        String abi = pickBundledAbi(context.getAssets());
        if (abi == null) {
            throw new IOException("No bundled ffmpeg binary found in native libs or assets/ffmpeg/<abi>/ffmpeg");
        }

        File[] candidateRoots = new File[] {
                context.getCodeCacheDir(),
                context.getCacheDir(),
                context.getFilesDir()
        };

        StringBuilder failures = new StringBuilder();
        for (File root : candidateRoots) {
            if (root == null) continue;
            File outDir = new File(root, "ffmpeg-bin");
            if (!outDir.exists() && !outDir.mkdirs()) {
                appendFailure(failures, "mkdir_failed", outDir.getAbsolutePath());
                continue;
            }

            File target = new File(outDir, "ffmpeg-" + abi);
            if (!target.exists() || target.length() == 0) {
                copyAsset(context.getAssets(), "ffmpeg/" + abi + "/ffmpeg", target);
            }
            
            // Set permissions with enhanced diagnostics
            if (!setExecutablePermissions(target)) {
                appendFailure(failures, "chmod_failed", target.getAbsolutePath());
                continue;
            }

            if (canExecuteBinary(target)) {
                cachedExecutablePath = target.getAbsolutePath();
                log.info("ffmpeg_executable_ready path={}", cachedExecutablePath);
                return cachedExecutablePath;
            }
            appendFailure(failures, "not_executable", target.getAbsolutePath());
        }

        throw new IOException("Bundled ffmpeg present but cannot execute in app sandbox: " + failures);
    }

    @Nullable
    private static File findNativeBundledBinary(Context context) {
        ApplicationInfo appInfo = context.getApplicationInfo();
        if (appInfo == null || appInfo.nativeLibraryDir == null || appInfo.nativeLibraryDir.isEmpty()) {
            return null;
        }
        File nativeBinary = new File(appInfo.nativeLibraryDir, NATIVE_BINARY_NAME);
        if (!nativeBinary.exists() || nativeBinary.length() == 0) {
            return null;
        }
        return nativeBinary;
    }

    private static boolean setExecutablePermissions(File binary) {
        try {
            // Standard Java file permissions
            if (!binary.setReadable(true, false)) {
                log.warn("ffmpeg_chmod_readable_failed path={}", binary.getAbsolutePath());
            }
            if (!binary.setWritable(true, true)) {
                log.warn("ffmpeg_chmod_writable_failed path={}", binary.getAbsolutePath());
            }
            if (!binary.setExecutable(true, false)) {
                log.warn("ffmpeg_chmod_executable_failed path={}", binary.getAbsolutePath());
            }
            
            // Verify after setting
            if (!binary.canRead()) {
                log.warn("ffmpeg_verify_readable_failed path={}", binary.getAbsolutePath());
                return false;
            }
            if (!binary.canExecute()) {
                log.warn("ffmpeg_verify_executable_failed path={}", binary.getAbsolutePath());
                // Try shell chmod as fallback
                return tryShellChmod(binary);
            }
            return true;
        } catch (Exception e) {
            log.warn("ffmpeg_chmod_exception path={} err={}", binary.getAbsolutePath(), e.toString());
            return false;
        }
    }

    private static boolean tryShellChmod(File binary) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", 
                    "chmod 755 " + binary.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            int rc = p.exitValue();
            if (rc == 0) {
                log.info("ffmpeg_chmod_shell_success path={}", binary.getAbsolutePath());
                return binary.canExecute();
            }
            log.warn("ffmpeg_chmod_shell_failed path={} rc={}", binary.getAbsolutePath(), rc);
            return false;
        } catch (Exception e) {
            log.warn("ffmpeg_chmod_shell_exception path={} err={}", binary.getAbsolutePath(), e.toString());
            return false;
        }
    }

    private static void appendFailure(StringBuilder failures, String type, String path) {
        if (failures.length() > 0) failures.append("; ");
        failures.append(type).append('@').append(path);
    }

    private static boolean canExecuteBinary(File binary) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(binary.getAbsolutePath(), "-version");
            pb.redirectErrorStream(true);
            p = pb.start();
            String tail;
            try (InputStream is = p.getInputStream()) {
                tail = readTail(is, 512);
            }
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                log.warn("ffmpeg_exec_probe_timeout path={}", binary.getAbsolutePath());
                return false;
            }
            int rc = p.exitValue();
            if (rc == 0) {
                return true;
            }
            log.warn("ffmpeg_exec_probe_failed path={} rc={} tail={}",
                    binary.getAbsolutePath(), rc, tail);
            return false;
        } catch (Exception e) {
            log.warn("ffmpeg_exec_probe_error path={} err={}",
                    binary.getAbsolutePath(), e.toString());
            if (p != null) p.destroyForcibly();
            return false;
        }
    }

    @Nullable
    private static String pickBundledAbi(AssetManager assets) {
        for (String abi : Build.SUPPORTED_ABIS) {
            try (InputStream ignored = assets.open("ffmpeg/" + abi + "/ffmpeg")) {
                return abi;
            } catch (IOException ignored) {
                // keep searching
            }
        }
        return null;
    }

    private static void copyAsset(AssetManager assets, String path, File out) throws IOException {
        try (InputStream in = assets.open(path);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
        }
    }

    private static File prepareInputFile(Context context, Uri source, @Nullable String mediaFileId) throws IOException {
        if ("file".equals(source.getScheme())) {
            String p = source.getPath();
            if (p == null || p.isEmpty()) {
                throw new IOException("File URI has no path: " + source);
            }
            return new File(p);
        }

        ContentResolver resolver = context.getContentResolver();
        File inDir = new File(context.getFilesDir(), "remux-input");
        if (!inDir.exists() && !inDir.mkdirs()) {
            throw new IOException("Cannot create remux input dir: " + inDir);
        }
        String base = (mediaFileId == null || mediaFileId.isEmpty()) ? "offline" : mediaFileId;

        try (InputStream in = resolver.openInputStream(source)) {
            if (in == null) throw new IOException("Cannot open input stream: " + source);
            byte[] header = new byte[16];
            int headerLen = readUpTo(in, header, 0, header.length);
            String extension = chooseInputExtension(source, header, headerLen);
            File temp = new File(inDir, base + extension);
            if (temp.exists() && !temp.delete()) {
                throw new IOException("Cannot overwrite remux input: " + temp);
            }
            long copied = 0;
            try (FileOutputStream out = new FileOutputStream(temp)) {
                if (headerLen > 0) {
                    out.write(header, 0, headerLen);
                    copied += headerLen;
                }
            byte[] buf = new byte[256 * 1024];
            int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    copied += n;
                }
            }
            if (copied <= 0) {
                throw new IOException("Remux input copy produced empty file: " + source);
            }
            log.info("ffmpeg_remux_input_ready mediaFileID={} source={} temp={} bytes={} magic={}",
                    mediaFileId, source, temp.getAbsolutePath(), copied, magicString(header, headerLen));
            return temp;
        }
    }

    private static int readUpTo(InputStream in, byte[] b, int off, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = in.read(b, off + read, len - read);
            if (n < 0) break;
            read += n;
        }
        return read;
    }

    private static String chooseInputExtension(Uri source, byte[] header, int len) {
        if (isMpegProgramStream(header, len)) return ".mpg";
        if (isTransportStream(header, len)) return ".ts";
        String lower = source.toString().toLowerCase();
        if (lower.contains(".mpg") || lower.contains(".mpeg") || lower.contains(".vob") || lower.contains(".ps")) {
            return ".mpg";
        }
        return ".ts";
    }

    private static boolean isMpegProgramStream(byte[] header, int len) {
        return len >= 4
                && (header[0] & 0xFF) == 0x00
                && (header[1] & 0xFF) == 0x00
                && (header[2] & 0xFF) == 0x01
                && (((header[3] & 0xFF) == 0xBA) || ((header[3] & 0xFF) == 0xBB));
    }

    private static boolean isTransportStream(byte[] header, int len) {
        return len >= 1 && (header[0] & 0xFF) == 0x47;
    }

    private static String magicString(byte[] header, int len) {
        int count = Math.min(len, 8);
        StringBuilder sb = new StringBuilder(count * 3);
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(' ');
            int v = header[i] & 0xFF;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    private static boolean executeRemux(String ffmpeg,
                                        String input,
                                        String output,
                                        boolean addAacBsf) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        cmd.add("-y");
        cmd.add("-fflags");
        cmd.add("+genpts");
        cmd.add("-i");
        cmd.add(input);
        cmd.add("-map");
        cmd.add("0:v");
        cmd.add("-map");
        cmd.add("0:a?");
        cmd.add("-c:v");
        cmd.add("copy");
        cmd.add("-c:a");
        cmd.add("copy");
        if (addAacBsf) {
            cmd.add("-bsf:a");
            cmd.add("aac_adtstoasc");
        }
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add(output);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        // Drain stdout/stderr to avoid process stall on filled buffers.
        String tail;
        try (InputStream is = p.getInputStream()) {
            tail = readTail(is, 4000);
        }

        int rc = p.waitFor();
        if (rc == 0) {
            log.info("ffmpeg_remux_done bsfAac={} out={} size={}",
                    addAacBsf, output, new File(output).length());
            return true;
        }

        log.warn("ffmpeg_remux_failed bsfAac={} rc={} tail={}", addAacBsf, rc, tail);
        return false;
    }

    private static boolean executeRemuxToMkv(String ffmpeg,
                                             String input,
                                             String output) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        cmd.add("-y");
        cmd.add("-fflags");
        cmd.add("+genpts");
        cmd.add("-i");
        cmd.add(input);
        cmd.add("-map");
        cmd.add("0:v");
        cmd.add("-map");
        cmd.add("0:a?");
        cmd.add("-c");
        cmd.add("copy");
        cmd.add(output);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        String tail;
        try (InputStream is = p.getInputStream()) {
            tail = readTail(is, 4000);
        }

        int rc = p.waitFor();
        if (rc == 0) {
            log.info("ffmpeg_remux_mkv_done out={} size={}", output, new File(output).length());
            return true;
        }

        log.warn("ffmpeg_remux_mkv_failed rc={} tail={}", rc, tail);
        return false;
    }

    @Nullable
    private static String parseFirstVideoCodec(String ffmpegOutput) {
        if (ffmpegOutput == null || ffmpegOutput.isEmpty()) {
            return null;
        }
        String lower = ffmpegOutput.toLowerCase(java.util.Locale.US);
        int video = lower.indexOf("video:");
        if (video < 0) {
            return null;
        }
        int start = video + "video:".length();
        while (start < lower.length() && Character.isWhitespace(lower.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < lower.length()) {
            char c = lower.charAt(end);
            if (c == ',' || Character.isWhitespace(c)) {
                break;
            }
            end++;
        }
        if (end <= start) {
            return null;
        }
        return lower.substring(start, end);
    }

    private static String readTail(InputStream is, int maxChars) throws IOException {
        StringBuilder sb = new StringBuilder(maxChars + 128);
        byte[] buf = new byte[2048];
        int n;
        while ((n = is.read(buf)) > 0) {
            sb.append(new String(buf, 0, n));
            if (sb.length() > maxChars * 2) {
                sb.delete(0, sb.length() - maxChars);
            }
        }
        if (sb.length() > maxChars) {
            return sb.substring(sb.length() - maxChars);
        }
        return sb.toString();
    }
}
