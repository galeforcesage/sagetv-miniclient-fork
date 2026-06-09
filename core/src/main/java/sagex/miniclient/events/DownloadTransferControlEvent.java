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
 * Posted to the EventBus when the server emits transfer control acknowledgements
 * for pause/resume/cancel operations.
 */
public class DownloadTransferControlEvent {
    public enum Action {
        PAUSE,
        RESUME,
        CANCEL
    }

    private final Action action;
    private final String sessionToken;
    private final String mediaFileID;
    private final String downloadUrl;
    private final long bytesTransferred;
    private final String sessionState;

    public DownloadTransferControlEvent(Action action,
                                        String sessionToken,
                                        String mediaFileID,
                                        String downloadUrl,
                                        long bytesTransferred,
                                        String sessionState) {
        this.action = action;
        this.sessionToken = sessionToken;
        this.mediaFileID = mediaFileID;
        this.downloadUrl = downloadUrl;
        this.bytesTransferred = bytesTransferred;
        this.sessionState = sessionState;
    }

    public Action getAction() {
        return action;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public String getMediaFileID() {
        return mediaFileID;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public long getBytesTransferred() {
        return bytesTransferred;
    }

    public String getSessionState() {
        return sessionState;
    }
}
