/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sagex.miniclient.android.offline;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;

import androidx.documentfile.provider.DocumentFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.OutputStream;

/**
 * Handles storage location selection and file output for downloads.
 * Uses SAF (Storage Access Framework) on Android 11+ for user-selected directory,
 * falls back to app-private external storage on older versions.
 */
public class StorageHelper {
    private static final Logger log = LoggerFactory.getLogger(StorageHelper.class);
    private static final String PREFS_NAME = "download_storage";
    private static final String KEY_STORAGE_URI = "storage_uri";
    private static final String KEY_WIFI_ONLY = "wifi_only_downloads";
    private static final boolean DEFAULT_WIFI_ONLY = true;
    private static final long MIN_FREE_SPACE_BYTES = 500L * 1024 * 1024; // 500 MB minimum

    private final Context context;
    private final SharedPreferences prefs;

    public StorageHelper(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Returns true if the user has already chosen a download storage location.
     */
    public boolean hasStorageLocation() {
        return getStorageUri() != null;
    }

    /**
     * Returns the persisted SAF tree URI, or null if not set.
     */
    public Uri getStorageUri() {
        String uriStr = prefs.getString(KEY_STORAGE_URI, null);
        if (uriStr == null || uriStr.isEmpty()) return null;
        return Uri.parse(uriStr);
    }

    /**
     * Persist the user-selected SAF tree URI.
     */
    public void setStorageUri(Uri treeUri) {
        prefs.edit().putString(KEY_STORAGE_URI, treeUri.toString()).apply();
    }

    /**
     * Whether the user has restricted downloads to Wi-Fi (or Ethernet) only.
     * Defaults to {@code true} to avoid surprise cellular data charges.
     */
    public boolean isWifiOnlyDownloads() {
        return prefs.getBoolean(KEY_WIFI_ONLY, DEFAULT_WIFI_ONLY);
    }

    /**
     * Update the user-controlled Wi-Fi-only preference. Downloads currently
     * running on a metered/cellular link will be re-evaluated by the caller
     * (see {@link DownloadManager#onWifiOnlyPrefChanged()}).
     */
    public void setWifiOnlyDownloads(boolean wifiOnly) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY, wifiOnly).apply();
    }

    private static final String SAGETV_FOLDER = "SageTV-NG";
    private static final String DOWNLOADS_FOLDER = "Downloads";

    /**
     * Get the fallback storage directory for devices below Android 11
     * or when SAF is not configured.
     * Creates: &lt;app-external&gt;/Movies/SageTV-NG/Downloads/
     */
    public File getFallbackDirectory() {
        File base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (base == null) {
            base = context.getFilesDir();
        }
        File dir = new File(new File(base, SAGETV_FOLDER), DOWNLOADS_FOLDER);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Create an output file for a download. Returns the URI as a string.
     * For SAF: creates a SageTV-NG/Downloads subfolder inside the user-chosen tree.
     * For fallback: creates a file under the fallback directory.
     */
    public String createOutputFile(String fileName) {
        Uri treeUri = getStorageUri();
        if (treeUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
                if (tree != null && tree.canWrite()) {
                    // Create SageTV-NG/Downloads subfolder
                    DocumentFile sageTvDir = getOrCreateSubfolder(tree, SAGETV_FOLDER);
                    if (sageTvDir != null) {
                        DocumentFile downloadsDir = getOrCreateSubfolder(sageTvDir, DOWNLOADS_FOLDER);
                        if (downloadsDir != null) {
                            String mimeType = getMimeTypeForFile(fileName);
                            DocumentFile file = downloadsDir.createFile(mimeType, fileName);
                            if (file != null) {
                                return file.getUri().toString();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Failed to create SAF output file: {}", fileName, e);
            }
        }
        // Fallback to app-private storage
        File file = new File(getFallbackDirectory(), fileName);
        return Uri.fromFile(file).toString();
    }

    private static DocumentFile getOrCreateSubfolder(DocumentFile parent, String name) {
        DocumentFile existing = parent.findFile(name);
        if (existing != null && existing.isDirectory()) {
            return existing;
        }
        return parent.createDirectory(name);
    }

    /**
     * Open an OutputStream to the given URI (SAF or file://).
     */
    public OutputStream openOutputStream(String uriString, boolean append) throws Exception {
        Uri uri = Uri.parse(uriString);
        if ("file".equals(uri.getScheme())) {
            File file = new File(uri.getPath());
            return new java.io.FileOutputStream(file, append);
        } else {
            String mode = append ? "wa" : "w";
            return context.getContentResolver().openOutputStream(uri, mode);
        }
    }

    /**
     * Get available free space in bytes at the configured storage location.
     */
    public long getAvailableSpace() {
        Uri treeUri = getStorageUri();
        if (treeUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // For SAF, we can't directly query free space easily.
            // Fall back to checking the primary external storage.
            try {
                StatFs stat = new StatFs(Environment.getExternalStorageDirectory().getPath());
                return stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
            } catch (Exception e) {
                log.warn("Failed to check available space via StatFs", e);
            }
        }
        File dir = getFallbackDirectory();
        return dir.getUsableSpace();
    }

    /**
     * Check if there is sufficient space for the given file size.
     */
    public boolean hasEnoughSpace(long requiredBytes) {
        long available = getAvailableSpace();
        return available > (requiredBytes + MIN_FREE_SPACE_BYTES);
    }

    /**
     * Delete a downloaded file by its URI string.
     */
    public boolean deleteFile(String uriString) {
        if (uriString == null) return false;
        try {
            Uri uri = Uri.parse(uriString);
            if ("file".equals(uri.getScheme())) {
                File file = new File(uri.getPath());
                return file.delete();
            } else {
                DocumentFile doc = DocumentFile.fromSingleUri(context, uri);
                return doc != null && doc.delete();
            }
        } catch (Exception e) {
            log.error("Failed to delete file: {}", uriString, e);
            return false;
        }
    }

    /**
     * Whether SAF directory picker should be used (Android 11+).
     */
    public boolean shouldUseSAF() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    private static String getMimeTypeForFile(String fileName) {
        if (fileName == null) return "video/*";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".ts")) return "video/mp2t";
        if (lower.endsWith(".mpg") || lower.endsWith(".mpeg")) return "video/mpeg";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        return "video/*";
    }
}
