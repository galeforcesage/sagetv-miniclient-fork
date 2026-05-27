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

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodic recovery pass that requeues interrupted sessions after process death
 * and lets the DownloadManager enforce current policy constraints.
 */
public class DownloadRecoveryWorker extends Worker {
    private static final Logger log = LoggerFactory.getLogger(DownloadRecoveryWorker.class);

    public DownloadRecoveryWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            DownloadManager.getInstance(getApplicationContext()).recoverInterruptedSessions();
            return Result.success();
        } catch (Throwable t) {
            log.warn("Download recovery pass failed: {}", t.getMessage());
            return Result.retry();
        }
    }
}
