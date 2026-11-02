package sagex.miniclient.party;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import sagex.miniclient.MiniClient;
import sagex.miniclient.MiniClientConnection;
import sagex.miniclient.SageCommand;
import sagex.miniclient.events.PartyStateChanged;
import sagex.miniclient.logging.ILogger;

public final class PartyClient implements PartyPlaybackAdapter.LifecycleListener {
    public enum Availability { UNKNOWN, UNAVAILABLE, AVAILABLE }

    public static final class State {
        public final Availability availability;
        public final boolean joined;
        public final boolean host;
        public final boolean locallyStopped;
        public final boolean ready;
        public final boolean controlsEnabled;
        public final boolean prepareSupported;
        public final boolean currentPositionSupported;
        public final String partyId;
        public final String participantId;
        public final String joinCode;
        public final long joinCodeExpiresAtServerMonoMs;
        public final boolean joinCodeExpired;
        public final String status;
        public final String errorCode;
        public final JSONObject snapshot;
        public final List<String> connectedSessionHandles;
        public final String invitedPartyId;
        public final boolean mediaWindowPresent;
        public final boolean mediaGrowing;
        public final long availableEndMs;
        public final long playableEndMs;
        public final long liveEdgeDelayMs;
        public final JSONObject delivery;
        public final JSONObject capacity;

        private State(PartyClient source) {
            availability = source.availability;
            joined = source.partyId != null;
            host = source.isHost();
            locallyStopped = source.locallyStopped;
            ready = source.ready;
            controlsEnabled = source.ready && !source.correctionPending
                    && !source.readyRequestPending && !source.rejoinPending
                    && !source.transitionPending;
            prepareSupported = source.prepareSupported;
            currentPositionSupported = source.supportedStartModes.contains("CURRENT_POSITION");
            partyId = source.partyId;
            participantId = source.participantId;
            joinCode = source.joinCode;
            joinCodeExpiresAtServerMonoMs = source.joinCodeExpiresAtServerMonoMs;
            joinCodeExpired = source.isJoinCodeExpired();
            status = source.status;
            errorCode = source.errorCode;
            snapshot = source.snapshot == null ? null : new JSONObject(source.snapshot.toString());
            connectedSessionHandles = new ArrayList<String>(source.connectedSessionHandles);
            invitedPartyId = source.invitedPartyId;
            mediaWindowPresent = source.mediaWindow.isPresent();
            mediaGrowing = source.mediaWindow.isMediaGrowing();
            availableEndMs = source.mediaWindow.getAvailableEndMs();
            playableEndMs = source.mediaWindow.getPlayableEndMs();
            liveEdgeDelayMs = source.mediaWindow.getLiveEdgeDelayMs();
            delivery = source.currentDelivery == null ? null
                    : new JSONObject(source.currentDelivery.toString());
            capacity = source.currentCapacity == null ? null
                    : new JSONObject(source.currentCapacity.toString());
        }
    }

    private interface ReplyHandler {
        void onReply(JSONObject reply);
    }

    private static final class PendingRequest {
        final String type;
        final String requestId;
        final String serialized;
        final boolean retryAllowed;
        final ReplyHandler handler;
        int sends;

        PendingRequest(String type, String requestId, String serialized,
                       boolean retryAllowed, ReplyHandler handler) {
            this.type = type;
            this.requestId = requestId;
            this.serialized = serialized;
            this.retryAllowed = retryAllowed;
            this.handler = handler;
        }
    }

    private static final long REQUEST_TIMEOUT_MS = 15_000;
    static final long HELLO_REFRESH_INTERVAL_MS = 300_000;
    static final long SYNC_PLAYING_INTERVAL_MS = 10_000;
    static final long SYNC_PAUSED_INTERVAL_MS = 30_000;
    static final long SYNC_RECOVERY_INTERVAL_MS = 2_000;
    private static final long RENDERED_STALL_WINDOW_MS = 1_000;
    private static final long MIN_RENDERED_PROGRESS_MS = 100;
    private static final int BUFFER_RECOVERY_SAMPLES = 2;
    static final long CLOCK_CALIBRATION_WINDOW_MS = 2_000;
    static final int CLOCK_CALIBRATION_SAMPLE_COUNT = 5;
    static final long PUSH_COMMAND_SETTLE_MS = 300;
    private static final long COMMAND_EXECUTION_TIMEOUT_MS = 8_000;
    static final long CORRECTION_EXECUTION_TIMEOUT_MS = 8_000;

    private final MiniClient client;
    private final MiniClientConnection connection;
    private final PartyPlaybackAdapter adapter;
    private final PartyRecoveryState recovery;
    private final ILogger log;
    private final ScheduledExecutorService executor;
    private final ArrayDeque<PendingRequest> queue = new ArrayDeque<PendingRequest>();
    private final Set<Long> receivedCommands = new HashSet<Long>();
    private final PartyMediaWindow mediaWindow = new PartyMediaWindow();

    private Availability availability = Availability.UNKNOWN;
    private PendingRequest inFlight;
    private ScheduledFuture<?> requestTimeout;
    private ScheduledFuture<?> scheduledCommand;
    private ScheduledFuture<?> executionObserver;
    private ScheduledFuture<?> periodic;
    private long requestSeq;
    private String serverEpoch;
    private String sessionHandle;
    private String sessionToken;
    private long sessionTokenExpiresAtServerMonoMs;
    private long lastHelloAt;
    private String partyId;
    private String participantId;
    private String recoveryToken;
    private long recoveryTokenExpiresAtServerMonoMs;
    private JSONObject snapshot;
    private JSONObject currentDelivery;
    private JSONObject currentCapacity;
    private long stateVersion;
    private long commandSeq;
    private String joinCode;
    private long joinCodeExpiresAtServerMonoMs;
    private boolean prepareSupported;
    private boolean surfaceSelectionSupported;
    private final Set<String> supportedStartModes = new HashSet<String>();
    private boolean preparationLoaded;
    private boolean preparationPending;
    private boolean mediaAdmissionConfirmed;
    private boolean mediaAdmissionRequested;
    private boolean correctionRecoveryPending;
    private boolean readyRequestPending;
    private boolean correctionPending;
    private boolean rejoinPending;
    private boolean recoveryJoinPending;
    private boolean networkRecoveryPending;
    private boolean networkReconnectRequested;
    private boolean networkControlReady;
    private boolean networkMediaReady;
    private boolean bufferingRecoveryPending;
    private boolean transitionPending;
    private String deferredAction;
    private volatile boolean playbackHeld;
    private long synchronizationGeneration;
    private boolean ready;
    private boolean locallyStopped;
    private int clockModels;
    private PartyClockModel clockModel;
    private long lastSyncSentAt;
    private long clockCalibrationPendingUntil;
    private long lastRenderedPositionMs = -1;
    private long lastRenderedSampleMonoMs;
    private int renderedRecoverySamples;
    private String activeCorrectionId;
    private JSONObject activeCommand;
    private JSONObject supersededCommand;
    private JSONObject deferredCorrection;
    private long preparedPlaybackGeneration = -1;
    private long preparedPathGeneration;
    private String activeAdmissionContextId;
    private String status = "DISCONNECTED";
    private String errorCode;
    private boolean started;
    private boolean capabilitySessionNegotiated;
    private boolean sessionAuthorized;
    private long pendingOriginPlaybackGeneration = -1;
    private boolean pendingOriginPlaybackPrepared;
    private final List<String> connectedSessionHandles = new ArrayList<String>();
    private String invitedPartyId;

    public PartyClient(MiniClient client, MiniClientConnection connection,
                       PartyPlaybackAdapter adapter, PartyRecoveryState recovery,
                       ILogger log) {
        this.client = client;
        this.connection = connection;
        this.adapter = adapter == null ? PartyPlaybackAdapter.UNSUPPORTED : adapter;
        this.recovery = recovery;
        this.log = log;
        this.executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "PARTY-V1");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public boolean supportsRequiredGuarantees() {
        return adapter.supportsRequiredGuarantees();
    }

    public State getState() {
        return new State(this);
    }

    public boolean hasActivePlayback() {
        if (!sessionAuthorized || !capabilitySessionNegotiated
                || !connection.ownsFreshPlayback(connection.getMediaCmd())) {
            return false;
        }
        PartyPlaybackAdapter.Observation observation = adapter.observe();
        return observation != null && observation.activated
                && observation.state != PartyPlaybackAdapter.PlaybackState.STOPPED
                && observation.state != PartyPlaybackAdapter.PlaybackState.UNAVAILABLE;
    }

    public void createCurrent(final String startMode) {
        executor.execute(new Runnable() {
            @Override public void run() {
                if (!hasActivePlayback()) {
                    errorCode = "NO_CURRENT_MEDIA";
                    status = "ERROR";
                    publishState();
                    return;
                }
                create(startMode);
            }
        });
    }

    public void start() {
        if (!supportsRequiredGuarantees() || started) return;
        started = true;
        adapter.start(this);
        executor.execute(new Runnable() {
            @Override public void run() {
                periodic = executor.scheduleAtFixedRate(new Runnable() {
                    @Override public void run() {
                        maintain();
                    }
                }, 1, 1, TimeUnit.SECONDS);
            }
        });
    }

    public void onCapabilitiesNegotiated() {
        executor.execute(new Runnable() {
            @Override public void run() {
                if (capabilitySessionNegotiated) return;
                capabilitySessionNegotiated = true;
                sessionAuthorized = false;
                availability = Availability.UNKNOWN;
                ready = false;
                executor.schedule(new Runnable() {
                    @Override public void run() {
                        if (capabilitySessionNegotiated) sendHello();
                    }
                }, 500, TimeUnit.MILLISECONDS);
                publishState();
            }
        });
    }

    public void close() {
        if (!started) return;
        recovery.markDisconnected(adapter.elapsedRealtimeMs());
        cancelScheduledPlayback();
        adapter.stop();
        adapter.clearMediaAdmission();
        if (periodic != null) periodic.cancel(false);
        executor.shutdownNow();
    }

    public void onTransportReconnected() {
        adapter.resetReadiness();
        executor.execute(new Runnable() {
            @Override public void run() {
                networkControlReady = true;
                if (networkRecoveryPending && capabilitySessionNegotiated) {
                    sendHello();
                }
            }
        });
    }

    public void onTransportGenerationChanged(final boolean capabilitySessionChanged) {
        adapter.resetReadiness();
        executor.execute(new Runnable() {
            @Override public void run() {
                recovery.markDisconnected(adapter.elapsedRealtimeMs());
                if (capabilitySessionChanged) {
                    capabilitySessionNegotiated = false;
                }
                sessionAuthorized = false;
                availability = Availability.UNKNOWN;
                ready = false;
                preparationLoaded = false;
                mediaAdmissionConfirmed = false;
                mediaAdmissionRequested = false;
                activeAdmissionContextId = null;
                preparedPlaybackGeneration = -1;
                preparedPathGeneration = 0;
                correctionRecoveryPending = false;
                readyRequestPending = false;
                correctionPending = false;
                rejoinPending = false;
                recoveryJoinPending = false;
                synchronizationGeneration++;
                cancelScheduledPlayback();
                adapter.clearMediaAdmission();
                queue.clear();
                clearInFlight();
                status = "RECONNECTING";
                publishState();
            }
        });
    }

    public void onMediaTransportReconnected() {
        executor.execute(new Runnable() {
            @Override public void run() {
                networkMediaReady = true;
                resumeNetworkRecovery();
            }
        });
    }

    public void onPlaybackGenerationChanged() {
        executor.execute(new Runnable() {
            @Override public void run() {
                if (pendingOriginPlaybackGeneration >= 0
                        && adapter.playbackGeneration() != pendingOriginPlaybackGeneration) {
                    pendingOriginPlaybackGeneration = -1;
                    pendingOriginPlaybackPrepared = false;
                }
                if (partyId == null) return;
                boolean prepareAlreadyPending = preparationPending;
                cancelScheduledPlayback();
                adapter.clearMediaAdmission();
                preparationLoaded = false;
                mediaAdmissionConfirmed = false;
                mediaAdmissionRequested = false;
                activeAdmissionContextId = null;
                preparedPlaybackGeneration = -1;
                preparedPathGeneration = 0;
                readyRequestPending = false;
                correctionPending = false;
                synchronizationGeneration++;
                ready = false;
                if (locallyStopped) {
                    status = "LOCALLY_STOPPED";
                } else if (prepareAlreadyPending) {
                    status = "PREPARING";
                } else if (prepareSupported) {
                    enqueuePrepare();
                } else {
                    status = "RECOVERING";
                }
                publishState();
            }
        });
    }

    public void onServerEnvelope(final String wireValue) {
        if (!supportsRequiredGuarantees()) return;
        final long receivedMonoMs = adapter.elapsedRealtimeMs();
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    handleServerEnvelope(PartyProtocol.parseEnvelope(wireValue), receivedMonoMs);
                } catch (PartyProtocolException e) {
                    errorCode = "INVALID_SERVER_MESSAGE";
                    status = "ERROR";
                    log.logWarning("Rejected invalid PARTY_V1 envelope: " + e.getMessage());
                    publishState();
                }
            }
        });
    }

    public boolean intercept(SageCommand command) {
        if (command == null || partyId == null) return false;
        if (command == SageCommand.STOP) {
            localStop();
            return true;
        }
        final String action = actionFor(command);
        if (action == null) return false;
        executor.execute(new Runnable() {
            @Override public void run() {
                if (!locallyStopped && ready && !correctionPending
                        && !readyRequestPending && !rejoinPending) {
                    sendAction(action);
                } else {
                    status = blockedControlStatus(status, rejoinPending,
                            correctionPending, readyRequestPending);
                    errorCode = null;
                    publishState();
                }
            }
        });
        return true;
    }

    static String blockedControlStatus(String currentStatus,
                                       boolean rejoinPending,
                                       boolean correctionPending,
                                       boolean readyRequestPending) {
        if (rejoinPending) return "REJOINING";
        if (correctionPending || readyRequestPending) return "SYNCHRONIZING";
        return currentStatus;
    }

    public void create(final String startMode) {
        executor.execute(new Runnable() {
            @Override public void run() {
                final PartyPlaybackAdapter.Observation before = adapter.observe();
                final long generation = adapter.playbackGeneration();
                final boolean wasPlaying = before != null
                        && before.state == PartyPlaybackAdapter.PlaybackState.PLAYING;
                pendingOriginPlaybackGeneration = generation;
                pendingOriginPlaybackPrepared = before != null
                        && before.prepared && before.activated;
                setPlaybackHold(true);
                JSONObject fields = createFields(startMode);
                enqueueRequest("CREATE", fields, false, new ReplyHandler() {
                    @Override public void onReply(JSONObject reply) {
                        if (!"CREATED".equals(reply.optString("type"))) {
                            pendingOriginPlaybackGeneration = -1;
                            pendingOriginPlaybackPrepared = false;
                            adapter.restoreAfterCreateFailure(generation, wasPlaying);
                        }
                    }
                });
            }
        });
    }

    public void join(final String enteredCode, final String invitedPartyId,
                     final Integer mediaFileId) throws PartyProtocolException {
        PartyProtocol.requireJoinCode(enteredCode);
        executor.execute(new Runnable() {
            @Override public void run() {
                JSONObject fields = new JSONObject().put("joinCode", enteredCode);
                if (invitedPartyId != null) fields.put("partyId", invitedPartyId);
                if (mediaFileId != null) fields.put("mediaFileId", mediaFileId);
                enqueueRequest("JOIN", fields, true, new ReplyHandler() {
                    @Override public void onReply(JSONObject reply) {
                        // JOINED is applied by handleServerEnvelope. Non-host members
                        // immediately enter the prepare path there.
                    }
                });
            }
        });
    }

    public void prepare() {
        executor.execute(new Runnable() {
            @Override public void run() {
                if (!prepareSupported || partyId == null || locallyStopped) return;
                enqueuePrepare();
            }
        });
    }

    static JSONObject createFields(String startMode) {
        JSONObject fields = new JSONObject();
        if (startMode != null) fields.put("startMode", startMode);
        return fields;
    }

    private void enqueuePrepare() {
        if (preparationPending || !prepareSupported || partyId == null || locallyStopped) return;
        preparationPending = true;
        setPlaybackHold(true);
        preparationLoaded = false;
        mediaAdmissionConfirmed = false;
        mediaAdmissionRequested = false;
        readyRequestPending = false;
        correctionPending = false;
        synchronizationGeneration++;
        ready = false;
        JSONObject fields = new JSONObject()
                .put("partyId", partyId)
                .put("intent", "JOIN_AND_PREPARE")
                .put("confirmReplace", true);
        enqueueRequest("PREPARE", fields, true, new ReplyHandler() {
            @Override public void onReply(JSONObject reply) {
                if ("ERROR".equals(reply.optString("type"))) {
                    preparationPending = false;
                }
            }
        });
        status = "PREPARING";
        publishState();
    }

    public void listClients() {
        executor.execute(new Runnable() {
            @Override public void run() {
                enqueueRequest("LIST_CLIENTS", new JSONObject(), true, new ReplyHandler() {
                    @Override public void onReply(JSONObject reply) {
                        if (!"CLIENTS".equals(reply.optString("type"))) return;
                        connectedSessionHandles.clear();
                        JSONArray handles = reply.optJSONArray("sessionHandles");
                        if (handles != null) {
                            for (int i = 0; i < handles.length(); i++) {
                                String handle = handles.optString(i, null);
                                if (handle != null && !handle.equals(sessionHandle)) {
                                    connectedSessionHandles.add(handle);
                                }
                            }
                        }
                        publishState();
                    }
                });
            }
        });
    }

    public void invite(final String sessionHandle) {
        executor.execute(new Runnable() {
            @Override public void run() {
                enqueueRequest("INVITE", new JSONObject()
                        .put("partyId", partyId)
                        .put("sessionHandle", sessionHandle), true, null);
            }
        });
    }

    public void retrieveJoinCode(boolean rotate) {
        final String type = rotate ? "ROTATE_CODE" : "JOIN_CODE";
        executor.execute(new Runnable() {
            @Override public void run() {
                enqueueRequest(type, new JSONObject().put("partyId", partyId), true,
                        new ReplyHandler() {
                            @Override public void onReply(JSONObject reply) {
                                if ("JOIN_CODE".equals(reply.optString("type"))) {
                                    joinCode = reply.optString("joinCode", null);
                                    joinCodeExpiresAtServerMonoMs =
                                            reply.optLong("expiresAtServerMonoMs", 0);
                                    publishState();
                                }
                            }
                        });
            }
        });
    }

    public void localStop() {
        executor.execute(new Runnable() {
            @Override public void run() {
                if (partyId == null || locallyStopped) return;
                cancelScheduledPlayback();
                adapter.localStop();
                adapter.clearMediaAdmission();
                locallyStopped = true;
                ready = false;
                transitionPending = false;
                deferredAction = null;
                preparationLoaded = false;
                mediaAdmissionConfirmed = false;
                sendStatus("LOCALLY_STOPPED");
                status = "LOCALLY_STOPPED";
                publishState();
            }
        });
    }

    public void rejoin() {
        executor.execute(new Runnable() {
            @Override public void run() {
                if (partyId == null) return;
                rejoinPending = true;
                recoveryJoinPending = true;
                enqueueRequest("REJOIN", new JSONObject().put("partyId", partyId), true,
                        new ReplyHandler() {
                            @Override public void onReply(JSONObject reply) {
                                // JOINED is applied by handleServerEnvelope.
                            }
                        });
            }
        });
    }

    public void leave() {
        terminalMembershipRequest("LEAVE");
    }

    public void end() {
        terminalMembershipRequest("END");
    }

    @Override
    public void onForegroundConfidenceLost() {
        executor.execute(new Runnable() {
            @Override public void run() {
                if (partyId == null || locallyStopped) return;
                ready = false;
                adapter.resetReadiness();
                cancelScheduledPlayback();
                synchronizationGeneration++;
                resetClock();
                setPlaybackHold(true);
                status = "RECOVERING";
                sendStatus("RECOVERING");
                startClockCalibration();
                requestSnapshot();
                publishState();
            }
        });
    }

    @Override
    public void onNetworkBoundary(final PartyPlaybackAdapter.NetworkBoundary boundary) {
        executor.execute(new Runnable() {
            @Override public void run() {
                beginNetworkRecovery(boundary);
            }
        });
    }

    @Override
    public void onPlaybackStateChanged() {
        executor.execute(new Runnable() {
            @Override public void run() {
                PartyPlaybackAdapter.Observation observation = adapter.observe();
                updatePlaybackHealth(observation);
                evaluateReadiness();
                sendCombinedSync(true);
            }
        });
    }

    @Override
    public void onReadinessInvalidated() {
        executor.execute(new Runnable() {
            @Override public void run() {
                if (partyId == null || locallyStopped) return;
                if ("BUFFERING".equals(status) || networkRecoveryPending
                        || bufferingRecoveryPending) {
                    return;
                }
                if (ready && !correctionPending && !readyRequestPending
                        && !correctionRecoveryPending) {
                    return;
                }
                ready = false;
                status = preparationLoaded ? "PREPARING" : status;
                publishState();
            }
        });
    }

    private void sendHello() {
        if (inFlight != null && "HELLO".equals(inFlight.type)) return;
        for (PendingRequest pending : queue) {
            if ("HELLO".equals(pending.type)) return;
        }
        enqueueRequest("HELLO", new JSONObject(), false, new ReplyHandler() {
            @Override public void onReply(JSONObject reply) {
                if (!"AVAILABLE".equals(reply.optString("type"))) return;
                PartyRecoveryState.RecoveryAction action =
                        recovery.recoveryAction(adapter.elapsedRealtimeMs());
                if (action == PartyRecoveryState.RecoveryAction.RESUME
                        || action == PartyRecoveryState.RecoveryAction.REJOIN) {
                    recoveryJoinPending = true;
                    rejoinPending = true;
                    enqueueRequest(action.name(), new JSONObject()
                            .put("partyId", recovery.partyId)
                            .put("participantId", recovery.participantId)
                            .put("recoveryToken", recovery.recoveryToken),
                            false, null);
                } else if (recovery.hasDisconnectedAuthority()) {
                    clearMembership("REJOIN_EXPIRED");
                } else {
                    resumeNetworkRecovery();
                }
            }
        });
    }

    private void applyAvailable(JSONObject reply) {
        if (!capabilitySessionNegotiated) return;
        String availableServerEpoch = reply.optString("serverEpoch", "");
        String availableSessionHandle = reply.optString("sessionHandle", "");
        String availableSessionToken = reply.optString("sessionToken", "");
        if (availableServerEpoch.isEmpty() || availableSessionHandle.isEmpty()
                || availableSessionToken.isEmpty()
                || reply.optLong("sessionTokenExpiresAtServerMonoMs", 0) <= 0) {
            sessionAuthorized = false;
            availability = Availability.UNKNOWN;
            errorCode = "INVALID_AVAILABLE_SESSION";
            status = "ERROR";
            publishState();
            return;
        }
        sessionAuthorized = true;
        availability = Availability.AVAILABLE;
        serverEpoch = availableServerEpoch;
        sessionHandle = availableSessionHandle;
        sessionToken = availableSessionToken;
        sessionTokenExpiresAtServerMonoMs =
                reply.optLong("sessionTokenExpiresAtServerMonoMs", 0);
        lastHelloAt = adapter.elapsedRealtimeMs();
        prepareSupported = reply.optBoolean("prepareSupported", false);
        surfaceSelectionSupported = reply.optBoolean("surfaceSelectionSupported", false);
        supportedStartModes.clear();
        JSONArray modes = reply.optJSONArray("supportedStartModes");
        if (modes != null) {
            for (int i = 0; i < modes.length(); i++) {
                String mode = modes.optString(i, null);
                if (mode != null) supportedStartModes.add(mode);
            }
        }
        if (reply.has("recoveryToken") && partyId != null) {
            recoveryToken = reply.optString("recoveryToken", recoveryToken);
            recoveryTokenExpiresAtServerMonoMs =
                    reply.optLong("recoveryTokenExpiresAtServerMonoMs",
                            recoveryTokenExpiresAtServerMonoMs);
            recovery.update(partyId, participantId, recoveryToken,
                    recoveryTokenExpiresAtServerMonoMs);
        }
        status = partyId == null ? "AVAILABLE" : status;
        errorCode = null;
        publishState();
    }

    private void applyJoined(JSONObject reply) {
        boolean prepareAfterRecovery = recoveryJoinPending || rejoinPending;
        if (reply.optLong("resourceGraceMs", -1) != 15_000
                || reply.optLong("reconnectGraceMs", -1) != 90_000
                || reply.optLong("rejoinTombstoneMs", -1) != 300_000) {
            errorCode = "INVALID_SERVER_MESSAGE";
            status = "ERROR";
            publishState();
            return;
        }
        partyId = reply.optString("partyId", null);
        participantId = reply.optString("participantId", null);
        recoveryToken = reply.optString("recoveryToken", null);
        recoveryTokenExpiresAtServerMonoMs =
                reply.optLong("recoveryTokenExpiresAtServerMonoMs", 0);
        snapshot = reply.optJSONObject("snapshot");
        if (snapshot == null) {
            errorCode = "INVALID_SERVER_MESSAGE";
            status = "ERROR";
            publishState();
            return;
        }
        applySnapshot(snapshot);
        if ("ERROR".equals(status)) return;
        JSONObject preparation = reply.optJSONObject("preparation");
        currentDelivery = consumeDelivery(preparation);
        if (preparation != null && currentDelivery == null) {
            errorCode = "INVALID_SERVER_MESSAGE";
            status = "ERROR";
            publishState();
            return;
        }
        recovery.update(partyId, participantId, recoveryToken,
                recoveryTokenExpiresAtServerMonoMs);
        locallyStopped = false;
        ready = false;
        preparationLoaded = false;
        preparationPending = false;
        mediaAdmissionConfirmed = false;
        mediaAdmissionRequested = false;
        correctionRecoveryPending = false;
        readyRequestPending = false;
        correctionPending = false;
        networkRecoveryPending = false;
        networkReconnectRequested = false;
        bufferingRecoveryPending = false;
        activeCorrectionId = null;
        deferredCorrection = null;
        transitionPending = false;
        deferredAction = null;
        preparedPlaybackGeneration = -1;
        preparedPathGeneration = 0;
        activeAdmissionContextId = null;
        resetRenderedProgress(null);
        synchronizationGeneration++;
        resetClock();
        status = "PREPARING";
        errorCode = null;
        publishState();
        if ((!isHost() || prepareAfterRecovery) && prepareSupported) {
            enqueuePrepare();
        }
    }

    private void handleServerEnvelope(JSONObject envelope, long receivedMonoMs) {
        String type = envelope.optString("type", "");
        String envelopePartyId = envelope.optString("partyId", null);
        String requestId = envelope.optString("requestId", null);
        boolean positionReply = inFlight != null
                && "POSITION".equals(inFlight.type)
                && inFlight.requestId.equals(requestId);
        boolean syncReply = inFlight != null
                && "SYNC".equals(inFlight.type)
                && inFlight.requestId.equals(requestId);
        if ((partyId != null && partyId.equals(envelopePartyId)
                || positionReply || syncReply)
                && !mediaWindow.update(envelope)) {
            ready = false;
            errorCode = "INVALID_GROWING_MEDIA_WINDOW";
            status = "ERROR";
            publishState();
            return;
        }
        if ("ERROR".equals(type)) {
            handleError(envelope);
        } else if ("AVAILABLE".equals(type)) {
            applyAvailable(envelope);
        } else if ("SNAPSHOT".equals(type)) {
            applySnapshot(envelope);
        } else if ("COMMAND".equals(type)) {
            handleCommand(envelope);
        } else if ("CORRECTION".equals(type)) {
            handleCorrection(envelope);
        } else if ("CLOCK_PROBE".equals(type)) {
            handleClockProbe(envelope, receivedMonoMs);
        } else if ("CLOCK_MODEL".equals(type)) {
            handleClockModel(envelope, receivedMonoMs);
        } else if ("SYNC_ACK".equals(type)) {
            handleSyncAck(envelope, receivedMonoMs);
        } else if ("PREPARATION_RESULT".equals(type)) {
            handlePreparationResult(envelope);
        } else if ("MEDIA_ADMISSION_HANDLE".equals(type)) {
            handleMediaAdmission(envelope);
        } else if ("MEDIA_ADMISSION_REVOKED".equals(type)) {
            String contextId = envelope.optString("contextId", null);
            if (activeAdmissionContextId == null
                    || activeAdmissionContextId.equals(contextId)) {
                mediaAdmissionConfirmed = false;
                ready = false;
                activeAdmissionContextId = null;
                adapter.clearMediaAdmission();
                status = "RECOVERING";
                publishState();
            }
        } else if ("CREATED".equals(type)) {
            applyCreated(envelope);
        } else if ("JOINED".equals(type)) {
            applyJoined(envelope);
        } else if ("JOIN_CODE".equals(type)) {
            joinCode = envelope.optString("joinCode", null);
            joinCodeExpiresAtServerMonoMs = envelope.optLong("expiresAtServerMonoMs", 0);
            publishState();
        } else if ("CLIENTS".equals(type)) {
            connectedSessionHandles.clear();
            JSONArray handles = envelope.optJSONArray("sessionHandles");
            if (handles != null) {
                for (int i = 0; i < handles.length(); i++) {
                    String handle = handles.optString(i, null);
                    if (handle != null && !handle.equals(sessionHandle)) {
                        connectedSessionHandles.add(handle);
                    }
                }
            }
            publishState();
        } else if ("INVITED".equals(type)) {
            invitedPartyId = envelope.optString("partyId", null);
            status = "INVITED";
            publishState();
        } else if ("ENDED".equals(type)) {
            clearMembership("ENDED");
        } else if ("LEFT".equals(type)) {
            clearMembership("LEFT");
        } else if ("PARTICIPANT_STATE".equals(type)
                && participantId != null
                && participantId.equals(envelope.optString("participantId"))) {
            handleParticipantState(envelope);
        } else if ("BUFFER_RECOMMENDATION".equals(type)
                || "COMMAND_OUTCOME".equals(type)) {
            if ("COMMAND_OUTCOME".equals(type)
                    && envelope.optLong("commandSeq", -1) == commandSeq
                    && !"PENDING".equals(envelope.optString("outcome"))) {
                transitionPending = false;
                runDeferredAction();
            }
            publishState();
        }

        if (inFlight != null && inFlight.requestId.equals(requestId)) {
            PendingRequest completed = inFlight;
            clearInFlight();
            if (completed.handler != null) completed.handler.onReply(envelope);
            sendNext();
        }
    }

    private void handleError(JSONObject envelope) {
        errorCode = envelope.optString("code", "UNKNOWN");
        if ("NOT_AVAILABLE".equals(errorCode)) {
            availability = Availability.UNAVAILABLE;
            status = "NOT_AVAILABLE";
        } else if ("SERVER_EPOCH_CHANGED".equals(errorCode)
                || "PARTY_GONE".equals(errorCode)) {
            clearMembership(errorCode);
        } else if ("STALE_STATE".equals(errorCode)) {
            cancelScheduledPlayback();
            requestSnapshot();
        } else if ("STALE_CORRECTION".equals(errorCode)) {
            adapter.resetReadiness();
            cancelScheduledPlayback();
            ready = false;
            readyRequestPending = false;
            correctionPending = false;
            resetClock();
            status = "RECOVERING";
            correctionRecoveryPending = true;
            synchronizationGeneration++;
            requestSnapshot();
        } else if ("CLOCK_UNCERTAIN".equals(errorCode)) {
            errorCode = null;
            refreshClockCalibration();
        } else if ("MEDIA_ADMISSION_UNCONFIRMED".equals(errorCode)) {
            cancelScheduledPlayback();
            synchronizationGeneration++;
            preparationLoaded = false;
            preparationPending = false;
            mediaAdmissionConfirmed = false;
            mediaAdmissionRequested = false;
            readyRequestPending = false;
            correctionPending = false;
            correctionRecoveryPending = false;
            ready = false;
            setPlaybackHold(true);
            status = "RECOVERING";
        } else if ("REJOIN_REQUIRED".equals(errorCode)) {
            queue.clear();
            cancelScheduledPlayback();
            adapter.resetReadiness();
            ready = false;
            readyRequestPending = false;
            correctionPending = false;
            correctionRecoveryPending = false;
            mediaAdmissionConfirmed = false;
            mediaAdmissionRequested = false;
            activeAdmissionContextId = null;
            preparedPlaybackGeneration = -1;
            preparedPathGeneration = 0;
            synchronizationGeneration++;
            status = "REJOINING";
            if (!rejoinPending && partyId != null) {
                rejoinPending = true;
                recoveryJoinPending = true;
                enqueueRequest("REJOIN", new JSONObject().put("partyId", partyId),
                        true, null);
            }
        } else if ("NOT_READY".equals(errorCode)) {
            errorCode = null;
            status = correctionPending ? "SYNCHRONIZING" : "PREPARING";
        }
        publishState();
    }

    private void handleParticipantState(JSONObject participantState) {
        String newStatus = participantState.optString("status", status);
        if ("DEGRADED".equals(newStatus)) {
            cancelScheduledPlayback();
            adapter.resetReadiness();
            ready = false;
            correctionPending = false;
            correctionRecoveryPending = false;
            synchronizationGeneration++;
            resetClock();
            setPlaybackHold(true);
            status = "REJOINING";
            if (!rejoinPending && partyId != null) {
                rejoinPending = true;
                recoveryJoinPending = true;
                enqueueRequest("REJOIN", new JSONObject().put("partyId", partyId),
                        true, null);
            }
            publishState();
            return;
        }
        if (isDefinitiveRecoveryStatus(newStatus) && !newStatus.equals(status)) {
            cancelScheduledPlayback();
            adapter.resetReadiness();
            ready = false;
            readyRequestPending = false;
            correctionPending = false;
            correctionRecoveryPending = true;
            synchronizationGeneration++;
            resetClock();
            setPlaybackHold(true);
            errorCode = null;
            status = newStatus;
            startClockCalibration();
        } else {
            status = newStatus;
            if (isClockSuspect(participantState)) {
                errorCode = null;
                refreshClockCalibration();
            }
        }
        publishState();
    }

    static boolean isDefinitiveRecoveryStatus(String participantStatus) {
        return "RECOVERING".equals(participantStatus)
                || "DEGRADED".equals(participantStatus);
    }

    static boolean isClockSuspect(JSONObject participantState) {
        return "SUSPECT".equals(participantState.optString("syncState"))
                || "CLOCK_UNCERTAIN".equals(participantState.optString("reason"));
    }

    static boolean isClockUncertainPositionAck(JSONObject reply) {
        return "ACK".equals(reply.optString("type"))
                && reply.has("clockConfident")
                && !reply.optBoolean("clockConfident", true);
    }

    private void applySnapshot(JSONObject newSnapshot) {
        String snapshotParty = newSnapshot.optString("partyId", null);
        if (partyId != null && snapshotParty != null && !partyId.equals(snapshotParty)) return;
        snapshot = new JSONObject(newSnapshot.toString());
        currentCapacity = consumeCapacity(snapshot.optJSONObject("capacity"));
        if (currentCapacity == null) {
            ready = false;
            errorCode = "INVALID_SERVER_MESSAGE";
            status = "ERROR";
            publishState();
            return;
        }
        if (!mediaWindow.update(snapshot)) {
            ready = false;
            errorCode = "INVALID_GROWING_MEDIA_WINDOW";
            status = "ERROR";
            publishState();
            return;
        }
        stateVersion = snapshot.optLong("stateVersion", stateVersion);
        commandSeq = snapshot.optLong("commandSeq", commandSeq);
        if ("STALE_CORRECTION".equals(errorCode) || correctionRecoveryPending
                || networkRecoveryPending || bufferingRecoveryPending) {
            errorCode = null;
            status = "PREPARING";
            correctionRecoveryPending = false;
            networkRecoveryPending = false;
            bufferingRecoveryPending = false;
            readyRequestPending = false;
            correctionPending = false;
            startClockCalibration();
            evaluateReadiness();
        }
        JSONObject pending = snapshot.optJSONObject("pendingCommand");
        if (pending != null) {
            transitionPending = true;
            handleCommand(pending);
        } else {
            transitionPending = false;
            runDeferredAction();
        }
        publishState();
    }

    private void handleClockProbe(JSONObject probe, long c1) {
        String probeId = probe.optString("probeId", null);
        if (probeId == null || partyId == null) return;
        enqueueRequest("CLOCK_REPLY", clockReplyFields(
                partyId, probeId, c1, adapter.elapsedRealtimeMs()), true, null);
    }

    private void handleClockModel(JSONObject model, long receivedMonoMs) {
        clockModels++;
        clockModel = new PartyClockModel(
                model.optDouble("serverMinusClientMs", 0),
                model.optDouble("uncertaintyMs", Double.MAX_VALUE),
                model.optLong("p95RttMs", Long.MAX_VALUE),
                model.optBoolean("confident", false),
                clockModels, receivedMonoMs);
        if (clockModel.isConfident(receivedMonoMs)) {
            clockCalibrationPendingUntil = 0;
        }
        evaluateReadiness();
    }

    private void handleSyncAck(JSONObject reply, long receivedMonoMs) {
        handleClockModel(reply, receivedMonoMs);
        String probeId = reply.optString("probeId", null);
        if (probeId != null && partyId != null) {
            enqueueRequest("CLOCK_REPLY", clockReplyFields(
                    partyId, probeId, receivedMonoMs,
                    adapter.elapsedRealtimeMs()), true, null);
        }
        if (reply.has("clockConfident")
                && !reply.optBoolean("clockConfident", true)) {
            refreshClockCalibration();
        }
    }

    static JSONObject clockReplyFields(String partyId, String probeId,
                                       long receivedMonoMs, long sentMonoMs) {
        return new JSONObject()
                .put("partyId", partyId)
                .put("probeId", probeId)
                .put("c1", receivedMonoMs)
                .put("c2", sentMonoMs);
    }

    private void handlePreparationResult(JSONObject result) {
        if (!partyIdEquals(result)) return;
        preparationPending = false;
        if ("LOADED".equals(result.optString("status")) && result.optInt("watchCode", -1) == 0) {
            preparationLoaded = true;
            preparedPlaybackGeneration = result.optLong("playbackGeneration", -1);
            preparedPathGeneration = result.optLong("pathGeneration", 0);
            JSONObject loadedDelivery = result.optJSONObject("delivery");
            if (loadedDelivery != null) {
                currentDelivery = consumeDelivery(loadedDelivery);
                if (currentDelivery == null) {
                    preparationLoaded = false;
                    errorCode = "INVALID_SERVER_MESSAGE";
                    status = "ERROR";
                    publishState();
                    return;
                }
            }
            status = "PREPARING";
            startClockCalibration();
            String mode = adapter.deliveryMode();
            if (isBoundProducerMode(mode)) {
                requestMediaAdmission();
            } else {
                mediaAdmissionConfirmed = "push".equals(mode);
                evaluateReadiness();
            }
        } else {
            preparationLoaded = false;
            preparedPlaybackGeneration = -1;
            preparedPathGeneration = 0;
            setPlaybackHold(false);
            errorCode = result.optString("code", "PREPARATION_FAILED");
            status = "PREPARATION_FAILED";
            publishState();
        }
    }

    private void applyCreated(JSONObject reply) {
        String createdJoinCode = reply.optString("joinCode", null);
        try {
            PartyProtocol.requireJoinCode(createdJoinCode);
        } catch (PartyProtocolException e) {
            errorCode = "INVALID_SERVER_MESSAGE";
            status = "ERROR";
            log.logWarning("Rejected CREATED reply without a three-digit joinCode");
            publishState();
            return;
        }
        applyJoined(reply);
        if ("ERROR".equals(status) || partyId == null) return;
        joinCode = createdJoinCode;
        joinCodeExpiresAtServerMonoMs = reply.optLong("expiresAtServerMonoMs", 0);
        preparationLoaded = pendingOriginPlaybackPrepared
                && pendingOriginPlaybackGeneration >= 0
                && adapter.playbackGeneration() == pendingOriginPlaybackGeneration;
        pendingOriginPlaybackGeneration = -1;
        pendingOriginPlaybackPrepared = false;
        currentDelivery = consumeDelivery(reply.optJSONObject("preparation"));
        if (preparationLoaded) {
            startClockCalibration();
            String mode = adapter.deliveryMode();
            if (isBoundProducerMode(mode)) {
                requestMediaAdmission();
            } else {
                mediaAdmissionConfirmed = "push".equals(mode);
                evaluateReadiness();
            }
        }
        status = preparationLoaded ? "PREPARING" : "RECOVERING";
        publishState();
    }

    private void handleMediaAdmission(JSONObject reply) {
        if (!partyIdEquals(reply)) return;
        String handle = reply.optString("handle", null);
        String mode = reply.optString("mode", "");
        long playbackGeneration = reply.optLong("playbackGeneration", -1);
        long pathGeneration = reply.optLong("pathGeneration", -1);
        boolean exactContext = serverEpoch != null && serverEpoch.equals(
                reply.optString("serverEpoch", null))
                && sessionHandle != null && sessionHandle.equals(
                        reply.optString("sessionHandle", null))
                && !reply.optString("contextId", "").isEmpty()
                && playbackGeneration >= 0
                && (preparedPlaybackGeneration < 0
                        || playbackGeneration == preparedPlaybackGeneration)
                && pathGeneration == preparedPathGeneration;
        mediaAdmissionConfirmed = exactContext
                && handle != null && mode.equals(adapter.deliveryMode())
                && isBoundProducerMode(mode)
                && adapter.bindMediaAdmission(handle);
        mediaAdmissionRequested = mediaAdmissionConfirmed;
        if (!mediaAdmissionConfirmed) {
            mediaAdmissionRequested = false;
            errorCode = "MEDIA_ADMISSION_UNCONFIRMED";
            status = "RECOVERING";
            activeAdmissionContextId = null;
        } else {
            preparedPlaybackGeneration = playbackGeneration;
            activeAdmissionContextId = reply.optString("contextId", null);
        }
        evaluateReadiness();
    }

    private void startClockCalibration() {
        scheduleClockCalibration(true);
    }

    private void refreshClockCalibration() {
        scheduleClockCalibration(false);
    }

    private void scheduleClockCalibration(boolean resetCurrentModel) {
        long now = adapter.elapsedRealtimeMs();
        if (clockCalibrationPendingUntil > now) return;
        if (resetCurrentModel) resetClock();
        clockCalibrationPendingUntil = now + CLOCK_CALIBRATION_WINDOW_MS;
        for (int i = 0; i < CLOCK_CALIBRATION_SAMPLE_COUNT; i++) {
            executor.schedule(new Runnable() {
                @Override public void run() {
                    if (partyId != null && !locallyStopped) {
                        enqueueRequest("CLOCK", new JSONObject().put("partyId", partyId),
                                true, null);
                    }
                }
            }, i * 150L, TimeUnit.MILLISECONDS);
        }
    }

    private void evaluateReadiness() {
        if (!preparationLoaded || ready || readyRequestPending || correctionPending
                || rejoinPending || locallyStopped || partyId == null
                || networkRecoveryPending || bufferingRecoveryPending
                || "BUFFERING".equals(status)) return;
        PartyPlaybackAdapter.Observation observation = adapter.observe();
        if (observation == null || !observation.prepared || !observation.activated
                || clockModel == null
                || !clockModel.isConfident(observation.sampleMonoMs)) return;
        long bufferAheadMs = mediaWindow.clampBufferAhead(
                observation.positionMs, observation.bufferAheadMs);
        long requiredBuffer = Math.min(1500,
                mediaWindow.remainingPlayableMs(
                        observation.positionMs, observation.durationMs));
        if (bufferAheadMs < requiredBuffer) return;
        long reportedBufferAheadMs = Math.max(0, Math.min(3_600_000, bufferAheadMs));
        String mode = adapter.deliveryMode();
        if (isBoundProducerMode(mode) && !mediaAdmissionConfirmed) return;

        JSONObject fields = new JSONObject()
                .put("partyId", partyId)
                .put("stateVersion", stateVersion)
                .put("prepared", observation.prepared)
                .put("activated", observation.activated)
                .put("bufferAheadMs", reportedBufferAheadMs)
                .put("precisionMs", Math.max(0, Math.min(1000, observation.precisionMs)))
                .put("rateAdjust", false);
        final long generation = synchronizationGeneration;
        readyRequestPending = true;
        enqueueRequest("READY", fields, true, new ReplyHandler() {
            @Override public void onReply(JSONObject reply) {
                if (generation != synchronizationGeneration) return;
                readyRequestPending = false;
                if ("CORRECTION".equals(reply.optString("type"))) {
                    status = "SYNCHRONIZING";
                    handleCorrection(reply);
                    publishState();
                }
            }
        });
    }

    private void handleCommand(JSONObject command) {
        if (!partyIdEquals(command)) return;
        long incomingSeq = command.optLong("commandSeq", -1);
        long incomingVersion = command.optLong("stateVersion", -1);
        if (incomingSeq < 0 || incomingVersion < 0 || receivedCommands.contains(incomingSeq)) return;
        if (incomingVersion < stateVersion || incomingSeq < commandSeq) return;
        receivedCommands.add(incomingSeq);
        transitionPending = true;
        stateVersion = incomingVersion;
        commandSeq = incomingSeq;
        sendCommandAck(command, "RECEIVED", null, false);
        scheduleCommand(command, false);
        publishState();
    }

    private void handleCorrection(JSONObject correction) {
        if (!partyIdEquals(correction) || locallyStopped) return;
        if (correction.optLong("stateVersion", -1) != stateVersion) return;
        if (!isHardCorrection(correction)) {
            errorCode = "INVALID_SERVER_MESSAGE";
            status = "ERROR";
            publishState();
            return;
        }
        JSONObject delivery = correction.optJSONObject("delivery");
        boolean readyReply = inFlight != null && "READY".equals(inFlight.type)
                && inFlight.requestId.equals(correction.optString("requestId", null));
        if (readyReply && delivery == null) {
            errorCode = "INVALID_SERVER_MESSAGE";
            status = "ERROR";
            publishState();
            return;
        }
        if (delivery != null) {
            currentDelivery = consumeDelivery(delivery);
            if (currentDelivery == null
                    || (readyReply && !adapter.deliveryMode().equals(
                            currentDelivery.optString("mode")))) {
                errorCode = "INVALID_SERVER_MESSAGE";
                status = "ERROR";
                publishState();
                return;
            }
        }
        String correctionId = correctionKey(correction, commandSeq);
        if (correctionPending) {
            if (correctionId.equals(activeCorrectionId)) return;
            deferredCorrection = new JSONObject(correction.toString());
            return;
        }
        if (activeCommand != null
                && !correctionSupersedesCommand(activeCommand, correction)) {
            deferredCorrection = new JSONObject(correction.toString());
            return;
        }
        activeCorrectionId = correctionId;
        correctionPending = true;
        ready = false;
        status = "SYNCHRONIZING";
        scheduleCommand(correction, true);
    }

    private void scheduleCommand(final JSONObject message, final boolean correction) {
        if (clockModel == null || !clockModel.isConfident(adapter.elapsedRealtimeMs())) {
            recoverClock("schedule");
            return;
        }
        JSONObject commandSupersededByCorrection = correction
                && correctionSupersedesCommand(activeCommand, message)
                ? new JSONObject(activeCommand.toString()) : null;
        cancelScheduledPlayback();
        if (correction) {
            supersededCommand = commandSupersededByCorrection;
        } else {
            activeCommand = new JSONObject(message.toString());
        }
        final long playbackCommandGeneration = adapter.playbackCommandGeneration();
        final long deadline = clockModel.clientDeadline(
                message.optLong("executeAtServerMonoMs"));
        long delay = Math.max(0, deadline - adapter.elapsedRealtimeMs());
        scheduledCommand = executor.schedule(new Runnable() {
            @Override public void run() {
                String mode = adapter.deliveryMode();
                boolean applied = true;
                if ("push".equals(mode)) {
                    adapter.setCanonicalPosition(projectedTargetPosition(
                            message, adapter.elapsedRealtimeMs()));
                } else {
                    applied = adapter.execute(toScheduledCommand(message, deadline));
                }
                observeExecution(message, correction, applied,
                        playbackCommandGeneration, "push".equals(mode));
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private PartyPlaybackAdapter.ScheduledCommand toScheduledCommand(
            JSONObject message, long deadline) {
        return new PartyPlaybackAdapter.ScheduledCommand(
                message.optLong("commandSeq", commandSeq),
                message.optLong("stateVersion", stateVersion),
                message.optString("cause", message.optString("kind", "")),
                mediaWindow.clampTarget(message.optLong("targetPositionMs", 0)),
                playbackState(message.optString("targetState", "PAUSED")),
                message.optDouble("targetRate", 1.0),
                deadline,
                "FAST_SYNC".equals(message.optString("seekAccuracy"))
                        ? PartyPlaybackAdapter.SeekAccuracy.FAST_SYNC
                        : PartyPlaybackAdapter.SeekAccuracy.EXACT);
    }

    private void observeExecution(final JSONObject message, final boolean correction,
                                  final boolean applied,
                                  final long playbackCommandGeneration,
                                  final boolean requireServerCommand) {
        final long startedAt = adapter.elapsedRealtimeMs();
        final long generation = synchronizationGeneration;
        final long playbackGeneration = adapter.playbackGeneration();
        final boolean[] pushStateApplied = new boolean[] { false };
        final long[] observedCommandGeneration =
                new long[] { playbackCommandGeneration };
        final long[] commandStableSinceMs = new long[] { -1 };
        executionObserver = executor.scheduleAtFixedRate(new Runnable() {
            @Override public void run() {
                if (generation != synchronizationGeneration
                        || playbackGeneration != adapter.playbackGeneration()) {
                    if (executionObserver != null) executionObserver.cancel(false);
                    if (correction && generation == synchronizationGeneration) {
                        completeSupersededCommand(null, false);
                        recoverFromFailedCorrection();
                    }
                    return;
                }
                PartyPlaybackAdapter.Observation observation = adapter.observe();
                long currentCommandGeneration = adapter.playbackCommandGeneration();
                if (requireServerCommand
                        && currentCommandGeneration > observedCommandGeneration[0]) {
                    observedCommandGeneration[0] = currentCommandGeneration;
                    commandStableSinceMs[0] = adapter.elapsedRealtimeMs();
                    pushStateApplied[0] = false;
                    adapter.setCanonicalPosition(projectedTargetPosition(
                            message, adapter.elapsedRealtimeMs()));
                    observation = adapter.observe();
                }
                boolean commandObserved = !requireServerCommand
                        || observedCommandGeneration[0] > playbackCommandGeneration;
                boolean commandSettled = !requireServerCommand
                        || commandGenerationSettled(commandObserved,
                                commandStableSinceMs[0], adapter.elapsedRealtimeMs());
                PartyPlaybackAdapter.PlaybackState targetState =
                        playbackState(message.optString("targetState", "PAUSED"));
                if (requireServerCommand && commandObserved && observation != null
                        && observation.state != targetState && !pushStateApplied[0]) {
                    pushStateApplied[0] = true;
                    adapter.applyPlaybackState(targetState);
                    observation = adapter.observe();
                }
                boolean success = applied && commandObserved && commandSettled
                        && landingMatches(message, observation);
                long timeoutMs = correction
                        ? CORRECTION_EXECUTION_TIMEOUT_MS
                        : COMMAND_EXECUTION_TIMEOUT_MS;
                boolean timedOut = observation.sampleMonoMs - startedAt >= timeoutMs;
                if (!success && !timedOut) return;
                if (executionObserver != null) executionObserver.cancel(false);
                if (log != null) {
                    log.logInfo("Party correction landing id="
                            + message.optString("correctionId", "")
                            + " success=" + success
                            + " applied=" + applied
                            + " commandObserved=" + commandObserved
                            + " commandSettled=" + commandSettled
                            + " commandGeneration=" + adapter.playbackCommandGeneration()
                            + " baselineCommandGeneration=" + playbackCommandGeneration
                            + " playbackGeneration=" + playbackGeneration
                            + " targetPositionMs="
                            + projectedTargetPosition(
                                    message, observation.sampleMonoMs)
                            + " observedPositionMs=" + observation.positionMs
                            + " targetState=" + message.optString("targetState", "PAUSED")
                            + " observedState=" + observation.state
                            + " timedOut=" + timedOut);
                }
                if (correction) {
                    sendCorrectionResult(message, observation, success);
                    completeSupersededCommand(observation, success);
                    if (success) {
                        correctionRecoveryPending = false;
                        correctionPending = false;
                        activeCorrectionId = null;
                        ready = true;
                        status = wireState(observation.state);
                        resetRenderedProgress(observation);
                        setPlaybackHold(false);
                        sendCombinedSync(true);
                        publishState();
                        runDeferredCorrection();
                    } else {
                        recoverFromFailedCorrection();
                    }
                } else {
                    sendCommandAck(message, "EXECUTED", observation, success);
                    activeCommand = null;
                    transitionPending = false;
                    runDeferredAction();
                    sendCombinedSync(true);
                    runDeferredCorrection();
                }
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
    }

    private void completeSupersededCommand(
            PartyPlaybackAdapter.Observation observation, boolean success) {
        JSONObject command = supersededCommand;
        supersededCommand = null;
        if (command == null) return;
        sendCommandAck(command, "EXECUTED", observation, success);
        transitionPending = false;
        runDeferredAction();
    }

    static boolean commandGenerationSettled(boolean commandObserved,
                                            long stableSinceMs, long nowMs) {
        return commandObserved && stableSinceMs >= 0
                && nowMs - stableSinceMs >= PUSH_COMMAND_SETTLE_MS;
    }

    static boolean correctionSupersedesCommand(JSONObject command,
                                                JSONObject correction) {
        return command != null && correction != null
                && command.optLong("commandSeq", -1)
                        == correction.optLong("commandSeq", -2)
                && command.optLong("stateVersion", -1)
                        == correction.optLong("stateVersion", -2)
                && command.optString("targetState", "")
                        .equals(correction.optString("targetState", ""));
    }

    private void recoverFromFailedCorrection() {
        adapter.resetReadiness();
        ready = false;
        readyRequestPending = false;
        correctionPending = false;
        activeCorrectionId = null;
        deferredCorrection = null;
        transitionPending = false;
        deferredAction = null;
        resetClock();
        correctionRecoveryPending = true;
        synchronizationGeneration++;
        status = "RECOVERING";
        requestSnapshot();
        publishState();
    }

    private boolean landingMatches(JSONObject message,
                                   PartyPlaybackAdapter.Observation observation) {
        if (observation == null) return false;
        PartyPlaybackAdapter.PlaybackState target =
                playbackState(message.optString("targetState", "PAUSED"));
        if (target != observation.state) return false;
        long targetPosition = projectedTargetPosition(
                message, observation.sampleMonoMs);
        long deadband = Math.max(observation.precisionMs,
                clockModel != null && clockModel.getP95RttMs() > 40 ? 250 : 125);
        return Math.abs(observation.positionMs - targetPosition) <= deadband;
    }

    private long projectedTargetPosition(JSONObject message, long sampleMonoMs) {
        long targetPosition = message.optLong("targetPositionMs", 0);
        boolean playing = playbackState(message.optString(
                "targetState", "PAUSED"))
                == PartyPlaybackAdapter.PlaybackState.PLAYING;
        if (playing && clockModel != null) {
            targetPosition = projectPlayingTarget(
                    targetPosition,
                    message.optLong("executeAtServerMonoMs"),
                    sampleMonoMs,
                    clockModel.getServerMinusClientMs());
        }
        return mediaWindow.clampTarget(targetPosition);
    }

    static long projectPlayingTarget(long targetPositionMs,
                                     long executeAtServerMonoMs,
                                     long sampleMonoMs,
                                     double serverMinusClientMs) {
        long sampleServerMonoMs = Math.round(
                sampleMonoMs + serverMinusClientMs);
        return targetPositionMs + Math.max(
                0, sampleServerMonoMs - executeAtServerMonoMs);
    }

    private void sendCommandAck(JSONObject command, String stage,
                                PartyPlaybackAdapter.Observation observation,
                                boolean success) {
        JSONObject fields = new JSONObject()
                .put("partyId", partyId)
                .put("stateVersion", command.optLong("stateVersion"))
                .put("commandSeq", command.optLong("commandSeq"))
                .put("stage", stage);
        if ("EXECUTED".equals(stage) && observation != null) {
            fields.put("success", success)
                    .put("sampleMonoMs", observation.sampleMonoMs)
                    .put("positionMs", observation.positionMs)
                    .put("state", wireState(observation.state));
        }
        enqueueRequest("ACK", fields, true, null);
    }

    private void sendCorrectionResult(JSONObject correction,
                                      PartyPlaybackAdapter.Observation observation,
                                      boolean success) {
        if (observation == null) {
            observation = new PartyPlaybackAdapter.Observation(
                    adapter.elapsedRealtimeMs(), 0, 0, 0,
                    PartyPlaybackAdapter.PlaybackState.UNAVAILABLE,
                    false, false, 1000);
        }
        enqueueRequest("CORRECTION_RESULT", new JSONObject()
                .put("partyId", partyId)
                .put("stateVersion", correction.optLong("stateVersion"))
                .put("correctionId", correction.optString("correctionId"))
                .put("success", success)
                .put("sampleMonoMs", observation.sampleMonoMs)
                .put("positionMs", observation.positionMs), true, null);
    }

    private void sendAction(String action) {
        if (transitionPending) {
            deferredAction = action;
            return;
        }
        transitionPending = true;
        enqueueRequest("ACTION", new JSONObject()
                .put("partyId", partyId)
                .put("stateVersion", stateVersion)
                .put("action", action), true, new ReplyHandler() {
                    @Override public void onReply(JSONObject reply) {
                        if ("ERROR".equals(reply.optString("type"))) {
                            String code = reply.optString("code");
                            if ("TRANSITION_PENDING".equals(code)) {
                                requestSnapshot();
                            } else {
                                transitionPending = false;
                                runDeferredAction();
                            }
                        }
                    }
                });
    }

    private void sendStatus(String newStatus) {
        enqueueRequest("STATUS", new JSONObject()
                .put("partyId", partyId)
                .put("status", newStatus), true, null);
    }

    private void sendCombinedSync(boolean immediate) {
        PartyPlaybackAdapter.Observation observation = adapter.observe();
        if (observation == null || locallyStopped || partyId == null
                || hasPendingRequest("SYNC")) return;
        long interval = syncIntervalMs(status, observation.state);
        if (!immediate && observation.sampleMonoMs - lastSyncSentAt < interval) return;
        lastSyncSentAt = observation.sampleMonoMs;
        JSONObject fields = new JSONObject().put("partyId", partyId);
        if (!"PREPARING".equals(status) && !"REJOINING".equals(status)
                && observation.prepared
                && observation.state != PartyPlaybackAdapter.PlaybackState.UNAVAILABLE
                && observation.state != PartyPlaybackAdapter.PlaybackState.STOPPED) {
            fields.put("stateVersion", stateVersion)
                    .put("sampleMonoMs", observation.sampleMonoMs)
                    .put("positionMs", observation.positionMs)
                    .put("bufferAheadMs", Math.max(0, Math.min(3_600_000,
                            mediaWindow.clampBufferAhead(
                                    observation.positionMs, observation.bufferAheadMs))))
                    .put("state", "BUFFERING".equals(status)
                            ? "BUFFERING" : wireState(observation.state));
        }
        enqueueRequest("SYNC", fields, true, null);
    }

    private void requestMediaAdmission() {
        if (ready || mediaAdmissionConfirmed || mediaAdmissionRequested
                || !isBoundProducerMode(adapter.deliveryMode())) {
            return;
        }
        mediaAdmissionRequested = true;
        enqueueRequest("MEDIA_ADMISSION", new JSONObject().put("partyId", partyId),
                true, null);
    }

    private void requestSnapshot() {
        if (partyId != null) {
            enqueueRequest("SNAPSHOT", new JSONObject().put("partyId", partyId),
                    true, null);
        }
    }

    private void recoverClock(String reason) {
        executor.execute(new Runnable() {
            @Override public void run() {
                if (partyId == null || locallyStopped) return;
                adapter.resetReadiness();
                cancelScheduledPlayback();
                ready = false;
                resetClock();
                sendStatus("RECOVERING");
                status = "RECOVERING";
                startClockCalibration();
                publishState();
            }
        });
    }

    private void maintain() {
        long now = adapter.elapsedRealtimeMs();
        if (availability == Availability.AVAILABLE && sessionTokenExpiresAtServerMonoMs > 0
                && clockModel != null) {
            long serverNow = Math.round(now
                    + clockModel.getServerMinusClientMs());
            if (sessionTokenExpiresAtServerMonoMs - serverNow < 60_000
                    && inFlight == null) {
                sendHello();
            }
        }
        if (availability == Availability.AVAILABLE
                && now - lastHelloAt >= HELLO_REFRESH_INTERVAL_MS
                && inFlight == null && queue.isEmpty()) {
            sendHello();
        }
        if (partyId != null && !locallyStopped) {
            PartyPlaybackAdapter.Observation observation = adapter.observe();
            updatePlaybackHealth(observation);
            if ((clockModel == null || !clockModel.isConfident(now))
                    && clockCalibrationPendingUntil <= now) {
                startClockCalibration();
            }
            evaluateReadiness();
            sendCombinedSync(false);
        }
    }

    private void beginNetworkRecovery(PartyPlaybackAdapter.NetworkBoundary boundary) {
        if (partyId == null || locallyStopped) return;
        recovery.markDisconnected(adapter.elapsedRealtimeMs());
        cancelScheduledPlayback();
        adapter.cancelScheduledAction();
        adapter.resetReadiness();
        queue.clear();
        clearInFlight();
        ready = false;
        readyRequestPending = false;
        correctionPending = false;
        activeCorrectionId = null;
        deferredCorrection = null;
        transitionPending = false;
        deferredAction = null;
        correctionRecoveryPending = false;
        bufferingRecoveryPending = false;
        networkRecoveryPending = true;
        networkControlReady = false;
        networkMediaReady = false;
        if (boundary == PartyPlaybackAdapter.NetworkBoundary.LOST) {
            networkReconnectRequested = false;
        }
        preparationLoaded = false;
        mediaAdmissionConfirmed = false;
        mediaAdmissionRequested = false;
        activeAdmissionContextId = null;
        preparedPlaybackGeneration = -1;
        preparedPathGeneration = 0;
        synchronizationGeneration++;
        resetClock();
        resetRenderedProgress(null);
        adapter.clearMediaAdmission();
        setPlaybackHold(true);
        status = "RECOVERING";
        errorCode = null;
        publishState();
        if (boundary != PartyPlaybackAdapter.NetworkBoundary.LOST
                && !networkReconnectRequested) {
            networkReconnectRequested = true;
            adapter.reconnectMediaTransport();
            adapter.reconnectControlTransport();
        }
    }

    private void resumeNetworkRecovery() {
        if (!networkRecoveryPending || partyId == null || locallyStopped
                || !sessionAuthorized || !networkControlReady || !networkMediaReady) {
            return;
        }
        networkReconnectRequested = false;
        startClockCalibration();
        requestSnapshot();
        if (prepareSupported && !preparationLoaded && !preparationPending) {
            enqueuePrepare();
        }
    }

    private void updatePlaybackHealth(PartyPlaybackAdapter.Observation observation) {
        if (observation == null || locallyStopped || partyId == null
                || correctionPending || readyRequestPending || rejoinPending) {
            return;
        }
        if (observation.state == PartyPlaybackAdapter.PlaybackState.BUFFERING) {
            enterBuffering();
            resetRenderedProgress(observation);
            return;
        }
        if (observation.state != PartyPlaybackAdapter.PlaybackState.PLAYING
                || observation.renderedPositionMs < 0) {
            resetRenderedProgress(observation);
            return;
        }
        if (lastRenderedSampleMonoMs <= 0) {
            resetRenderedProgress(observation);
            return;
        }
        long elapsed = observation.sampleMonoMs - lastRenderedSampleMonoMs;
        if (elapsed < RENDERED_STALL_WINDOW_MS) return;
        long progress = observation.renderedPositionMs - lastRenderedPositionMs;
        lastRenderedPositionMs = observation.renderedPositionMs;
        lastRenderedSampleMonoMs = observation.sampleMonoMs;
        if (renderedOutputStalled(elapsed, progress)) {
            renderedRecoverySamples = 0;
            enterBuffering();
            return;
        }
        if (!renderedOutputAdvancing(elapsed, progress)) {
            renderedRecoverySamples = 0;
            return;
        }
        if ("BUFFERING".equals(status)) {
            long bufferAheadMs = mediaWindow.clampBufferAhead(
                    observation.positionMs, observation.bufferAheadMs);
            long requiredBufferMs = Math.min(1500,
                    mediaWindow.remainingPlayableMs(
                            observation.positionMs, observation.durationMs));
            if (!hasRecoveryBuffer(bufferAheadMs, requiredBufferMs)) {
                renderedRecoverySamples = 0;
                return;
            }
            renderedRecoverySamples++;
            if (renderedRecoverySamples >= BUFFER_RECOVERY_SAMPLES) {
                renderedRecoverySamples = 0;
                bufferingRecoveryPending = true;
                status = "PREPARING";
                requestSnapshot();
                publishState();
            }
        }
    }

    private void enterBuffering() {
        if ("BUFFERING".equals(status)) return;
        ready = false;
        readyRequestPending = false;
        status = "BUFFERING";
        sendStatus("BUFFERING");
        sendCombinedSync(true);
        publishState();
    }

    static boolean renderedOutputStalled(long elapsedMs, long progressMs) {
        return elapsedMs >= RENDERED_STALL_WINDOW_MS
                && progressMs >= 0
                && progressMs < MIN_RENDERED_PROGRESS_MS;
    }

    static boolean renderedOutputAdvancing(long elapsedMs, long progressMs) {
        return elapsedMs >= RENDERED_STALL_WINDOW_MS
                && progressMs >= MIN_RENDERED_PROGRESS_MS;
    }

    static boolean hasRecoveryBuffer(long bufferAheadMs, long requiredBufferMs) {
        return bufferAheadMs < 0 || bufferAheadMs >= requiredBufferMs;
    }

    private void resetRenderedProgress(PartyPlaybackAdapter.Observation observation) {
        lastRenderedPositionMs = observation == null
                ? -1 : observation.renderedPositionMs;
        lastRenderedSampleMonoMs = observation == null
                ? 0 : observation.sampleMonoMs;
        renderedRecoverySamples = 0;
    }

    private void runDeferredCorrection() {
        JSONObject next = deferredCorrection;
        deferredCorrection = null;
        if (next == null || correctionPending) return;
        long executeAtServerMonoMs = next.optLong("executeAtServerMonoMs", 0);
        long nowServerMonoMs = clockModel == null ? Long.MAX_VALUE
                : Math.round(adapter.elapsedRealtimeMs()
                        + clockModel.getServerMinusClientMs());
        if (executeAtServerMonoMs <= 0 || executeAtServerMonoMs > nowServerMonoMs) {
            handleCorrection(next);
        } else {
            requestSnapshot();
        }
    }

    static String correctionKey(JSONObject correction, long fallbackCommandSeq) {
        String correctionId = correction.optString("correctionId", null);
        if (correctionId != null && !correctionId.isEmpty()) return correctionId;
        return "correction-" + correction.optLong("commandSeq", fallbackCommandSeq)
                + "-" + correction.optLong("stateVersion", -1)
                + "-" + correction.optLong("targetPositionMs", -1)
                + "-" + correction.optString("targetState", "");
    }

    private void enqueueRequest(String type, JSONObject fields,
                                boolean retryAllowed, ReplyHandler handler) {
        if (!supportsRequiredGuarantees()) return;
        if (queue.size() >= 64) {
            errorCode = "OUTPUT_OVERFLOW";
            status = "ERROR";
            cancelScheduledPlayback();
            publishState();
            return;
        }
        JSONObject envelope = new JSONObject()
                .put("schemaVersion", 1)
                .put("type", type)
                .put("requestId", requestId())
                .put("requestSeq", ++requestSeq);
        if (!"HELLO".equals(type)) {
            if (serverEpoch == null || sessionToken == null) return;
            envelope.put("serverEpoch", serverEpoch).put("sessionToken", sessionToken);
        }
        if (fields != null) {
            for (String key : fields.keySet()) envelope.put(key, fields.get(key));
        }
        try {
            PartyProtocol.validateEnvelope(envelope);
        } catch (PartyProtocolException e) {
            errorCode = "INVALID_REQUEST";
            publishState();
            return;
        }
        PendingRequest request = new PendingRequest(type,
                envelope.getString("requestId"), envelope.toString(),
                retryAllowed && !"CREATE".equals(type), handler);
        queue.add(request);
        sendNext();
    }

    private void sendNext() {
        if (inFlight != null || queue.isEmpty()) return;
        inFlight = queue.remove();
        sendInFlight();
    }

    private void sendInFlight() {
        if (inFlight == null) return;
        inFlight.sends++;
        connection.postPartyMessage(inFlight.serialized);
        if (requestTimeout != null) requestTimeout.cancel(false);
        requestTimeout = executor.schedule(new Runnable() {
            @Override public void run() {
                if (inFlight == null) return;
                if (inFlight.retryAllowed && inFlight.sends == 1) {
                    sendInFlight();
                } else {
                    errorCode = "REQUEST_TIMEOUT";
                    status = "RECOVERING";
                    clearInFlight();
                    sendNext();
                    publishState();
                }
            }
        }, REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void clearInFlight() {
        if (requestTimeout != null) requestTimeout.cancel(false);
        requestTimeout = null;
        inFlight = null;
    }

    private void terminalMembershipRequest(final String type) {
        executor.execute(new Runnable() {
            @Override public void run() {
                if (partyId == null) return;
                enqueueRequest(type, new JSONObject().put("partyId", partyId), false,
                        new ReplyHandler() {
                            @Override public void onReply(JSONObject reply) {
                                if ("LEFT".equals(reply.optString("type"))
                                        || "ENDED".equals(reply.optString("type"))) {
                                    clearMembership(reply.optString("type"));
                                }
                            }
                        });
            }
        });
    }

    private void clearMembership(String terminalStatus) {
        cancelScheduledPlayback();
        setPlaybackHold(false);
        adapter.clearMediaAdmission();
        recovery.clear();
        partyId = null;
        participantId = null;
        recoveryToken = null;
        snapshot = null;
        currentDelivery = null;
        currentCapacity = null;
        joinCode = null;
        invitedPartyId = null;
        pendingOriginPlaybackGeneration = -1;
        pendingOriginPlaybackPrepared = false;
        connectedSessionHandles.clear();
        ready = false;
        locallyStopped = false;
        preparationLoaded = false;
        preparationPending = false;
        mediaAdmissionConfirmed = false;
        mediaAdmissionRequested = false;
        correctionRecoveryPending = false;
        readyRequestPending = false;
        correctionPending = false;
        rejoinPending = false;
        recoveryJoinPending = false;
        networkRecoveryPending = false;
        networkReconnectRequested = false;
        bufferingRecoveryPending = false;
        activeCorrectionId = null;
        deferredCorrection = null;
        transitionPending = false;
        deferredAction = null;
        activeAdmissionContextId = null;
        preparedPlaybackGeneration = -1;
        preparedPathGeneration = 0;
        resetRenderedProgress(null);
        synchronizationGeneration++;
        mediaWindow.clear();
        status = terminalStatus;
        publishState();
    }

    private void cancelScheduledPlayback() {
        if (scheduledCommand != null) scheduledCommand.cancel(false);
        if (executionObserver != null) executionObserver.cancel(false);
        scheduledCommand = null;
        executionObserver = null;
        activeCommand = null;
        supersededCommand = null;
        adapter.cancelScheduledAction();
    }

    public boolean isPlaybackHeld() {
        return playbackHeld;
    }

    private void setPlaybackHold(boolean hold) {
        playbackHeld = hold;
        adapter.setPartyHold(hold);
    }

    private void resetClock() {
        clockModels = 0;
        clockModel = null;
        clockCalibrationPendingUntil = 0;
    }

    private boolean isHost() {
        return snapshot != null && participantId != null
                && participantId.equals(snapshot.optString("hostParticipantId", null));
    }

    private boolean isJoinCodeExpired() {
        if (joinCode == null || joinCodeExpiresAtServerMonoMs <= 0 || clockModel == null) {
            return false;
        }
        long serverNow = Math.round(adapter.elapsedRealtimeMs()
                + clockModel.getServerMinusClientMs());
        return serverNow >= joinCodeExpiresAtServerMonoMs;
    }

    private boolean partyIdEquals(JSONObject envelope) {
        return partyId != null && partyId.equals(envelope.optString("partyId", null));
    }

    private static boolean isBoundProducerMode(String mode) {
        return "pull".equals(mode) || "pull-xcode".equals(mode) || "hls".equals(mode);
    }

    static long syncIntervalMs(String status,
                               PartyPlaybackAdapter.PlaybackState playbackState) {
        if ("BUFFERING".equals(status) || "RECOVERING".equals(status)
                || "REJOINING".equals(status) || "SYNCHRONIZING".equals(status)
                || "PREPARING".equals(status)) {
            return SYNC_RECOVERY_INTERVAL_MS;
        }
        return playbackState == PartyPlaybackAdapter.PlaybackState.PAUSED
                ? SYNC_PAUSED_INTERVAL_MS : SYNC_PLAYING_INTERVAL_MS;
    }

    static boolean isHardCorrection(JSONObject correction) {
        Long durationMs = exactNonNegativeInteger(correction.opt("durationMs"));
        Object rate = correction.opt("targetRate");
        return "HARD".equals(correction.optString("kind"))
                && rate instanceof Number
                && ((Number) rate).doubleValue() == 1.0
                && durationMs != null && durationMs == 0;
    }

    private boolean hasPendingRequest(String type) {
        if (inFlight != null && type.equals(inFlight.type)) return true;
        for (PendingRequest pending : queue) {
            if (type.equals(pending.type)) return true;
        }
        return false;
    }

    private void runDeferredAction() {
        String action = deferredAction;
        deferredAction = null;
        if (action != null && ready && !locallyStopped && !rejoinPending) {
            sendAction(action);
        }
    }

    static JSONObject consumeDelivery(JSONObject delivery) {
        if (delivery == null) return null;
        Long planned = exactPositiveInteger(delivery.opt("plannedReservationKbps"));
        Long selected = exactPositiveInteger(delivery.opt("selectedBitrateKbps"));
        if (delivery.optString("surface", "").isEmpty()
                || delivery.optString("mode", "").isEmpty()
                || planned == null || selected == null
                || !(delivery.opt("transcode") instanceof Boolean)) {
            return null;
        }
        return new JSONObject(delivery.toString());
    }

    static JSONObject consumeCapacity(JSONObject capacity) {
        if (capacity == null
                || exactNonNegativeInteger(capacity.opt("plannedReservationKbps")) == null
                || exactNonNegativeInteger(capacity.opt("plannedRemoteEgressKbps")) == null
                || exactNonNegativeInteger(capacity.opt("selectedOutputKbps")) == null
                || exactNonNegativeInteger(capacity.opt("actualOutputKbps")) == null
                || exactNonNegativeInteger(capacity.opt("activeTranscodes")) == null
                || exactNonNegativeInteger(capacity.opt("maxReservationKbps")) == null
                || exactNonNegativeInteger(capacity.opt("maxTranscodes")) == null) {
            return null;
        }
        return new JSONObject(capacity.toString());
    }

    private static Long exactPositiveInteger(Object value) {
        Long parsed = exactNonNegativeInteger(value);
        return parsed != null && parsed > 0 ? parsed : null;
    }

    private static Long exactNonNegativeInteger(Object value) {
        if (!(value instanceof Number)) return null;
        double asDouble = ((Number) value).doubleValue();
        long asLong = ((Number) value).longValue();
        if (!Double.isFinite(asDouble) || asDouble != asLong || asLong < 0
                || asLong > 9_007_199_254_740_991L) {
            return null;
        }
        return asLong;
    }

    private String deliveryMode(JSONObject delivery) {
        if (delivery != null) {
            String mode = delivery.optString("mode", "");
            if (!mode.isEmpty()) return mode;
        }
        return adapter.deliveryMode();
    }

    private String actionFor(SageCommand command) {
        if (command == SageCommand.PLAY) return "PLAY";
        if (command == SageCommand.PAUSE) return "PAUSE";
        if (command == SageCommand.PLAY_PAUSE) {
            PartyPlaybackAdapter.Observation observation = adapter.observe();
            return observation != null
                    && observation.state == PartyPlaybackAdapter.PlaybackState.PLAYING
                    ? "PAUSE" : "PLAY";
        }
        if (command == SageCommand.FF) return "FF";
        if (command == SageCommand.REW) return "REW";
        if (command == SageCommand.FF_2) return "FF2";
        if (command == SageCommand.REW_2) return "REW2";
        return null;
    }

    private static PartyPlaybackAdapter.PlaybackState playbackState(String value) {
        try {
            return PartyPlaybackAdapter.PlaybackState.valueOf(value);
        } catch (Exception e) {
            return PartyPlaybackAdapter.PlaybackState.UNAVAILABLE;
        }
    }

    private static String wireState(PartyPlaybackAdapter.PlaybackState state) {
        if (state == PartyPlaybackAdapter.PlaybackState.STOPPED
                || state == PartyPlaybackAdapter.PlaybackState.UNAVAILABLE) {
            return "PAUSED";
        }
        return state.name();
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    private void publishState() {
        State state = new State(this);
        log.logInfo("Party state availability=" + state.availability
                + " status=" + state.status
                + " joined=" + state.joined
                + (state.errorCode == null ? "" : " error=" + state.errorCode));
        client.eventbus().post(new PartyStateChanged(state));
    }
}
