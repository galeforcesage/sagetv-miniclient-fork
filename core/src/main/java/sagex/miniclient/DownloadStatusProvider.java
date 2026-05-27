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
package sagex.miniclient;

/**
 * Interface for querying download status from the protocol layer.
 * Implemented in the Android layer (DownloadManager) and registered on MiniClient
 * so that MiniClientConnection can respond to DOWNLOAD_STATUS_ GetProperty requests.
 */
public interface DownloadStatusProvider {
    /**
     * Get the status string for a download by its media file ID.
     * @param mediaFileID the media file identifier
     * @return status string (e.g. "QUEUED", "DOWNLOADING|45", "COMPLETE", "FAILED|error msg"),
     *         or null if the media file ID is not in the download queue
     */
    String getDownloadStatus(String mediaFileID);
}
