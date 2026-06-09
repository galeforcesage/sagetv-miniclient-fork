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
package sagex.miniclient.events;

/**
 * Posted when CMD_DOWNLOAD_REQUEST carries type=TRANSFER_SESSION_ERROR.
 */
public class DownloadTransferSessionErrorEvent {
    private final String mediaFileID;
    private final String correlationId;
    private final String errorCode;
    private final String message;
    private final boolean retriable;

    public DownloadTransferSessionErrorEvent(String mediaFileID,
                                             String correlationId,
                                             String errorCode,
                                             String message,
                                             boolean retriable) {
        this.mediaFileID = mediaFileID;
        this.correlationId = correlationId;
        this.errorCode = errorCode;
        this.message = message;
        this.retriable = retriable;
    }

    public String getMediaFileID() {
        return mediaFileID;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getMessage() {
        return message;
    }

    public boolean isRetriable() {
        return retriable;
    }
}
