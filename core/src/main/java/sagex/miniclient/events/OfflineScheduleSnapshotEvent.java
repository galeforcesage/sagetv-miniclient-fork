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
 * M6 — posted when the server pushes a `CMD_OFFLINE_SCHED_SNAPSHOT` opcode
 * containing the set of currently-scheduled upcoming recordings for the
 * cached horizon. Paired with {@link OfflineGuideSnapshotEvent}; the
 * subscriber persists the snapshot atomically so the offline schedule
 * view always reflects the last-seen-from-server state.
 *
 * <p>Carries the raw JSON for the same reason as the guide event (keeps
 * the core module dependency-free).
 */
public class OfflineScheduleSnapshotEvent {
    private final String json;

    public OfflineScheduleSnapshotEvent(String json) {
        this.json = json;
    }

    public String getJson() {
        return json;
    }
}
