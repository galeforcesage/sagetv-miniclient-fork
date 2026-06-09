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
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.BaseDataSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * Replacement for {@link com.google.android.exoplayer2.upstream.ContentDataSource}
 * that always reports a usable total length and supports byte-offset reads.
 *
 * <p>Android's {@code DocumentsProvider} returns an
 * {@code AssetFileDescriptor} whose {@code getDeclaredLength()} is
 * {@code UNKNOWN_LENGTH} for SAF documents, which prevents the TS
 * extractor from running its PCR-based duration scan and leaves the
 * media item non-seekable. {@code ParcelFileDescriptor#getStatSize()}
 * returns the real file size for any SAF URI backed by a regular file,
 * so we use that and {@link Os#lseek(java.io.FileDescriptor, long, int)}
 * to honour {@link DataSpec#position}.
 */
final class SafContentDataSource extends BaseDataSource {

    static final class Factory implements DataSource.Factory {
        private final Context context;
        @Nullable private TransferListener transferListener;

        Factory(Context context) {
            this.context = context.getApplicationContext();
        }

        Factory setTransferListener(@Nullable TransferListener transferListener) {
            this.transferListener = transferListener;
            return this;
        }

        @Override
        public DataSource createDataSource() {
            SafContentDataSource ds = new SafContentDataSource(context);
            if (transferListener != null) ds.addTransferListener(transferListener);
            return ds;
        }
    }

    private final ContentResolver resolver;
    @Nullable private Uri uri;
    @Nullable private ParcelFileDescriptor pfd;
    @Nullable private FileInputStream input;
    private long bytesRemaining;
    private boolean opened;

    SafContentDataSource(Context context) {
        super(/* isNetwork= */ false);
        this.resolver = context.getApplicationContext().getContentResolver();
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        try {
            uri = dataSpec.uri;
            transferInitializing(dataSpec);
            pfd = resolver.openFileDescriptor(uri, "r");
            if (pfd == null) {
                throw new IOException("openFileDescriptor returned null for " + uri);
            }
            input = new FileInputStream(pfd.getFileDescriptor());
            long totalLength = pfd.getStatSize();
            if (totalLength < 0) {
                totalLength = C.LENGTH_UNSET;
            }
            if (dataSpec.position > 0) {
                try {
                    Os.lseek(pfd.getFileDescriptor(), dataSpec.position, OsConstants.SEEK_SET);
                } catch (ErrnoException e) {
                    throw new IOException("lseek failed for " + uri, e);
                }
            }
            if (dataSpec.length != C.LENGTH_UNSET) {
                bytesRemaining = dataSpec.length;
            } else if (totalLength != C.LENGTH_UNSET) {
                bytesRemaining = totalLength - dataSpec.position;
                if (bytesRemaining < 0) bytesRemaining = 0;
            } else {
                bytesRemaining = C.LENGTH_UNSET;
            }
            opened = true;
            transferStarted(dataSpec);
            return bytesRemaining;
        } catch (IOException e) {
            closeQuietly();
            throw e;
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (bytesRemaining == 0) return C.RESULT_END_OF_INPUT;
        int toRead = bytesRemaining == C.LENGTH_UNSET
                ? length
                : (int) Math.min(length, bytesRemaining);
        int read = input != null ? input.read(buffer, offset, toRead) : -1;
        if (read == -1) {
            return C.RESULT_END_OF_INPUT;
        }
        if (bytesRemaining != C.LENGTH_UNSET) {
            bytesRemaining -= read;
        }
        bytesTransferred(read);
        return read;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        uri = null;
        closeQuietly();
        if (opened) {
            opened = false;
            transferEnded();
        }
    }

    private void closeQuietly() {
        try { if (input != null) input.close(); } catch (IOException ignored) {}
        input = null;
        try { if (pfd != null) pfd.close(); } catch (IOException ignored) {}
        pfd = null;
    }
}
