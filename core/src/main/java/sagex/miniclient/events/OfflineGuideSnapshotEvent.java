/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package sagex.miniclient.events;

/**
 * M6 — posted when the server pushes a `CMD_OFFLINE_GUIDE_SNAPSHOT` opcode
 * containing a full normalized EPG snapshot (channels + airings) for the
 * configured offline horizon. The Android offline module subscribes and
 * persists into the local EPG store so the guide remains browsable while
 * disconnected.
 *
 * <p>Payload is the raw JSON string from the wire, parsed lazily by the
 * subscriber. Carrying it as a string keeps the core module free of any
 * JSON-parser dependency (Android-side uses org.json; future desktop
 * subscribers can use whatever).
 */
public class OfflineGuideSnapshotEvent {
    private final String json;

    public OfflineGuideSnapshotEvent(String json) {
        this.json = json;
    }

    public String getJson() {
        return json;
    }
}
