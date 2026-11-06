package sagex.miniclient.party;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
        public final boolean playbackHeld;
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
        public final int preparationWatchCode;
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
            playbackHeld = source.playbackHeld;
            controlsEnabled = source.ready && !source.locallyStopped
                    && !source.converging;
            prepareSupported = source.prepareSupported;
            currentPositionSupported = true;
            partyId = source.partyId;
            participantId = source.participantId;
            joinCode = source.joinCode;
            joinCodeExpiresAtServerMonoMs = source.joinCodeExpiresAtServerMonoMs;
            joinCodeExpired = source.isJoinCodeExpired();
            status = source.status;
            errorCode = source.errorCode;
            preparationWatchCode = source.preparationWatchCode;
            snapshot = source.snapshot == null ? null
                    : new JSONObject(source.snapshot.toString());
            connectedSessionHandles =
                    new ArrayList<String>(source.connectedSessionHandles);
            invitedPartyId = source.invitedPartyId;
            mediaWindowPresent = source.mediaWindow.isPresent();
            mediaGrowing = source.mediaWindow.isMediaGrowing();
            availableEndMs = source.mediaWindow.getAvailableEndMs();
            playableEndMs = source.mediaWindow.getPlayableEndMs();
            liveEdgeDelayMs = source.mediaWindow.getLiveEdgeDelayMs();
            delivery = source.currentDelivery == null ? null
                    : new JSONObject(source.currentDelivery.toString());
            capacity = null;
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
    static final long HEARTBEAT_ACTIVE_INTERVAL_MS = 1_000;
    static final long HEARTBEAT_PAUSED_INTERVAL_MS = 5_000;
    static final int CLOCK_CALIBRATION_SAMPLE_COUNT = 5;

    private final MiniClient client;
    private final MiniClientConnection connection;
    private final PartyPlaybackAdapter adapter;
    private final PartyResumeState resumeState;
    private final ILogger log;
    private final ScheduledExecutorService executor;
    private final ArrayDeque<PendingRequest> queue =
            new ArrayDeque<PendingRequest>();
    private final Set<String> executedDirectives = new HashSet<String>();
    private final Set<String> acceptedClockReplies = new HashSet<String>();
    private final PartyMediaWindow mediaWindow = new PartyMediaWindow();
    private final List<String> connectedSessionHandles =
            new ArrayList<String>();
    private final AtomicReference<State> publishedState;

    private Availability availability = Availability.UNKNOWN;
    private PendingRequest inFlight;
    private ScheduledFuture<?> requestTimeout;
    private ScheduledFuture<?> scheduledDirective;
    private long directiveExecutionGeneration;
    private ScheduledFuture<?> periodic;
    private long requestSeq;
    private String serverEpoch;
    private String sessionHandle;
    private String sessionToken;
    private long sessionTokenExpiresAtServerMonoMs;
    private long reconnectGraceMs = PartyResumeState.DEFAULT_GRACE_MS;
    private long lastHelloAt;
    private long lastHeartbeatAt;
    private String partyId;
    private String participantId;
    private String recoveryToken;
    private JSONObject snapshot;
    private JSONObject currentDelivery;
    private long stateVersion;
    private long commandSeq;
    private String joinCode;
    private long joinCodeExpiresAtServerMonoMs;
    private String invitedPartyId;
    private boolean prepareSupported;
    private boolean preparationLoaded;
    private boolean preparationPending;
    private long preparationPlaybackGeneration = -1;
    private int preparationWatchCode = -1;
    private boolean readyRequestPending;
    private boolean readyTransitionSent;
    private boolean ready;
    private boolean locallyStopped;
    private boolean converging;
    private boolean playbackHeld;
    private boolean heartbeatStarted;
    private PartyPlaybackAdapter.PlaybackState expectedPlaybackState =
            PartyPlaybackAdapter.PlaybackState.UNAVAILABLE;
    private volatile boolean started;
    private boolean capabilitySessionNegotiated;
    private boolean sessionAuthorized;
    private boolean resumeRequired;
    private int clockSamples;
    private PartyClockModel clockModel;
    private String status = "DISCONNECTED";
    private String errorCode;

    public PartyClient(MiniClient client, MiniClientConnection connection,
                       PartyPlaybackAdapter adapter, PartyResumeState resumeState,
                       ILogger log) {
        this.client = client;
        this.connection = connection;
        this.adapter = adapter == null
                ? PartyPlaybackAdapter.UNSUPPORTED : adapter;
        this.resumeState = resumeState;
        this.log = log;
        executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "PARTY-V1");
                thread.setDaemon(true);
                return thread;
            }
        });
        publishedState = new AtomicReference<State>(new State(this));
    }

    public boolean supportsRequiredGuarantees() {
        return adapter.supportsRequiredGuarantees();
    }

    public State getState() {
        return publishedState.get();
    }

    public boolean hasActivePlayback() {
        if (getState().availability != Availability.AVAILABLE
                || !connection.ownsFreshPlayback(connection.getMediaCmd())) {
            return false;
        }
        PartyPlaybackAdapter.Observation observation = adapter.observe();
        return observation != null && observation.prepared
                && observation.state != PartyPlaybackAdapter.PlaybackState.STOPPED
                && observation.state != PartyPlaybackAdapter.PlaybackState.UNAVAILABLE;
    }

    public synchronized void start() {
        if (!supportsRequiredGuarantees() || started) return;
        started = true;
        adapter.start(this);
        periodic = executor.scheduleAtFixedRate(new Runnable() {
            @Override public void run() {
                maintain();
            }
        }, 250, 250, TimeUnit.MILLISECONDS);
    }

    public void onCapabilitiesNegotiated() {
        executor.execute(new Runnable() {
            @Override public void run() {
                capabilitySessionNegotiated = true;
                availability = Availability.UNKNOWN;
                sendHello();
                publishState();
            }
        });
    }

    public synchronized void close() {
        if (!started) return;
        started = false;
        executor.execute(new Runnable() {
            @Override public void run() {
                resumeState.markDisconnected(adapter.elapsedRealtimeMs());
                cancelScheduledDirective();
                adapter.stop();
                if (periodic != null) periodic.cancel(false);
                executor.shutdown();
            }
        });
    }

    public void onTransportReconnected() {
        executor.execute(new Runnable() {
            @Override public void run() {
                if (inFlight != null) {
                    inFlight.sends = 0;
                    sendInFlight();
                } else if (partyId != null && !resumeRequired) {
                    requireResumeAfterDisconnect();
                    sendHello();
                } else if (capabilitySessionNegotiated) {
                    sendHello();
                }
            }
        });
    }

    public void onTransportGenerationChanged(final boolean capabilitySessionChanged) {
        executor.execute(new Runnable() {
            @Override public void run() {
                resumeState.markDisconnected(adapter.elapsedRealtimeMs());
                cancelScheduledDirective();
                queue.clear();
                clearInFlight();
                sessionAuthorized = false;
                availability = Availability.UNKNOWN;
                ready = false;
                readyRequestPending = false;
                preparationLoaded = false;
                preparationPending = false;
                converging = false;
                resetClock();
                if (partyId != null) {
                    resumeRequired = true;
                    setPlaybackHold(true);
                    status = "RECONNECTING";
                }
                if (capabilitySessionChanged) {
                    capabilitySessionNegotiated = false;
                    requestSeq = 0;
                }
                publishState();
            }
        });
    }

    public void onMediaTransportReconnected() {
    }

    public void onPlaybackGenerationChanged() {
        executor.execute(new Runnable() {
            @Override public void run() {
                if (partyId != null && !locallyStopped) publishState();
            }
        });
    }

    public void onServerEnvelope(final String wireValue) {
        if (!supportsRequiredGuarantees()) return;
        final long receivedMonoMs = adapter.elapsedRealtimeMs();
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    handleServerEnvelope(
                            PartyProtocol.parseEnvelope(wireValue), receivedMonoMs);
                } catch (PartyProtocolException e) {
                    errorCode = "INVALID_SERVER_MESSAGE";
                    status = "ERROR";
                    log.logWarning("Rejected invalid PARTY_V1 envelope: "
                            + e.getMessage());
                    publishState();
                }
            }
        });
    }

    public boolean intercept(SageCommand command) {
        State current = getState();
        if (command == null || !current.joined) return false;
        if (command == SageCommand.STOP) {
            localStop();
            return true;
        }
        final String action = actionFor(command);
        if (action == null) return false;
        executor.execute(new Runnable() {
            @Override public void run() {
                if (ready && !locallyStopped && !converging) {
                    sendAction(action, null);
                }
            }
        });
        return true;
    }

    public boolean createCurrent(final String startMode) {
        if (!hasActivePlayback()) return false;
        executor.execute(new Runnable() {
            @Override public void run() {
                status = "CREATING";
                errorCode = null;
                publishState();
                enqueueRequest("CREATE", createFields(startMode), true, null);
            }
        });
        return true;
    }

    public boolean create(final String startMode) {
        return createCurrent(startMode);
    }

    static JSONObject createFields(String startMode) {
        String normalized = "CURRENT_POSITION".equals(startMode)
                ? "CURRENT_POSITION" : "BEGINNING";
        return new JSONObject().put("startMode", normalized);
    }

    public void join(final String enteredCode, final String expectedPartyId,
                     final Integer mediaFileId) throws PartyProtocolException {
        PartyProtocol.requireJoinCode(enteredCode);
        executor.execute(new Runnable() {
            @Override public void run() {
                JSONObject fields = new JSONObject().put("joinCode", enteredCode);
                if (expectedPartyId != null) fields.put("partyId", expectedPartyId);
                if (mediaFileId != null) fields.put("mediaFileId", mediaFileId);
                enqueueRequest("JOIN", fields, true, null);
            }
        });
    }

    public void prepare() {
        executor.execute(new Runnable() {
            @Override public void run() {
                enqueuePrepare();
            }
        });
    }

    public void resume() {
        executor.execute(new Runnable() {
            @Override public void run() {
                enqueueResumeIfEligible();
            }
        });
    }

    public void listClients() {
        executor.execute(new Runnable() {
            @Override public void run() {
                enqueueRequest("LIST_CLIENTS", new JSONObject(), true, null);
            }
        });
    }

    public void invite(final String targetSessionHandle) {
        executor.execute(new Runnable() {
            @Override public void run() {
                if (partyId == null || !isHost()
                        || targetSessionHandle == null) return;
                enqueueRequest("INVITE", new JSONObject()
                        .put("partyId", partyId)
                        .put("sessionHandle", targetSessionHandle), true, null);
            }
        });
    }

    public void retrieveJoinCode(final boolean rotate) {
        executor.execute(new Runnable() {
            @Override public void run() {
                if (partyId == null || !isHost()) return;
                enqueueRequest("JOIN_CODE", new JSONObject()
                        .put("partyId", partyId)
                        .put("rotate", rotate), true, null);
            }
        });
    }

    public void localStop() {
        executor.execute(new Runnable() {
            @Override public void run() {
                if (partyId == null || locallyStopped) return;
                PartyPlaybackAdapter.Observation observation = adapter.observe();
                locallyStopped = true;
                ready = false;
                converging = false;
                cancelScheduledDirective();
                setPlaybackHold(false);
                sendHeartbeat(observation, "STOPPED", true);
                adapter.localStop();
                status = "STOPPED";
                publishState();
            }
        });
    }

    public void leave() {
        terminalMembershipRequest("LEAVE");
    }

    public void end() {
        executor.execute(new Runnable() {
            @Override public void run() {
                if (partyId != null && isHost()) {
                    enqueueRequest("END",
                            new JSONObject().put("partyId", partyId),
                            true, null);
                }
            }
        });
    }

    public boolean interceptSeek(final long targetPositionMs) {
        if (!getState().joined) return false;
        executor.execute(new Runnable() {
            @Override public void run() {
                if (ready && !locallyStopped && !converging) {
                    sendAction("SEEK", Math.max(0, targetPositionMs));
                }
            }
        });
        return true;
    }

    public void comskip() {
        executor.execute(new Runnable() {
            @Override public void run() {
                if (isHost() && ready && !locallyStopped && !converging) {
                    sendAction("COMSKIP", null);
                }
            }
        });
    }

    @Override
    public void onForegroundConfidenceLost() {
        beginReconnect();
    }

    @Override
    public void onNetworkBoundary(PartyPlaybackAdapter.NetworkBoundary boundary) {
        beginReconnect();
    }

    @Override
    public void onPlaybackStateChanged() {
        executor.execute(new Runnable() {
            @Override public void run() {
                if (partyId == null || locallyStopped) return;
                if (!heartbeatStarted) return;
                PartyPlaybackAdapter.Observation observation = adapter.observe();
                if (observation != null
                        && observation.state == PartyPlaybackAdapter.PlaybackState.BUFFERING) {
                    String observedState = heartbeatState(
                            observation.state, expectedPlaybackState,
                            playbackHeld);
                    status = observedState;
                    sendHeartbeat(observation, observedState, true);
                }
                publishState();
            }
        });
    }

    @Override
    public void onReadinessChanged(final boolean activated) {
        executor.execute(new Runnable() {
            @Override public void run() {
                if (partyId == null || locallyStopped) return;
                if (activated) {
                    if (!ready) evaluateReadiness();
                } else {
                    ready = false;
                    readyRequestPending = false;
                    readyTransitionSent = false;
                    converging = false;
                    cancelScheduledDirective();
                    setPlaybackHold(true);
                    if (preparationLoaded) status = "ACTIVATING";
                    errorCode = null;
                }
                publishState();
            }
        });
    }

    public boolean isPlaybackHeld() {
        return getState().playbackHeld;
    }

    private void sendHello() {
        if (!capabilitySessionNegotiated || hasPendingRequest("HELLO")) return;
        lastHelloAt = adapter.elapsedRealtimeMs();
        enqueueRequest("HELLO", new JSONObject(), true, null);
    }

    private void applyAvailable(JSONObject available) {
        if (!"PARTY_V1_COORDINATOR".equals(
                available.optString("contract", ""))) {
            availability = Availability.UNAVAILABLE;
            status = "NOT_AVAILABLE";
            publishState();
            return;
        }
        serverEpoch = available.optString("serverEpoch", null);
        sessionHandle = available.optString("sessionHandle", null);
        sessionToken = available.optString("sessionToken", null);
        sessionTokenExpiresAtServerMonoMs =
                available.optLong("sessionTokenExpiresAtServerMonoMs", 0);
        reconnectGraceMs = available.optLong(
                "reconnectGraceMs", PartyResumeState.DEFAULT_GRACE_MS);
        sessionAuthorized = serverEpoch != null && sessionToken != null;
        availability = sessionAuthorized
                ? Availability.AVAILABLE : Availability.UNAVAILABLE;
        prepareSupported = sessionAuthorized
                && "SAGETV_WATCH".equals(available.optString("playbackPath", ""))
                && "WIZARD_MEDIAFILE".equals(
                        available.optString("sourceResolution", ""));
        status = sessionAuthorized ? "AVAILABLE" : "NOT_AVAILABLE";
        errorCode = null;
        if (sessionAuthorized && (partyId == null || resumeRequired)) {
            enqueueResumeIfEligible();
        }
        publishState();
    }

    private void enqueueResumeIfEligible() {
        long now = adapter.elapsedRealtimeMs();
        if (!sessionAuthorized || !resumeState.canResume(now)
                || hasPendingRequest("RESUME")) {
            return;
        }
        enqueueRequest("RESUME", new JSONObject()
                .put("partyId", resumeState.getPartyId())
                .put("participantId", resumeState.getParticipantId())
                .put("recoveryToken", resumeState.getRecoveryToken()),
                true, null);
        status = "RESUMING";
        publishState();
    }

    private void applyMembership(JSONObject reply) {
        String newPartyId = reply.optString("partyId", null);
        String newParticipantId = reply.optString("participantId", null);
        String newRecoveryToken = reply.optString("recoveryToken", null);
        if (newPartyId == null || newParticipantId == null
                || newRecoveryToken == null) {
            errorCode = "INVALID_SERVER_MESSAGE";
            status = "ERROR";
            publishState();
            return;
        }
        partyId = newPartyId;
        participantId = newParticipantId;
        recoveryToken = newRecoveryToken;
        resumeState.update(partyId, participantId, recoveryToken, reconnectGraceMs);
        resumeRequired = false;
        String returnedCode = reply.optString("joinCode", null);
        if (returnedCode != null && !returnedCode.isEmpty()) {
            try {
                PartyProtocol.requireJoinCode(returnedCode);
                joinCode = returnedCode;
            } catch (PartyProtocolException e) {
                errorCode = "INVALID_SERVER_MESSAGE";
                status = "ERROR";
                publishState();
                return;
            }
        }
        joinCodeExpiresAtServerMonoMs =
                reply.optLong("expiresAtServerMonoMs", joinCodeExpiresAtServerMonoMs);
        JSONObject returnedSnapshot = reply.optJSONObject("snapshot");
        if (returnedSnapshot != null) applySnapshot(returnedSnapshot);
        locallyStopped = false;
        ready = false;
        converging = false;
        preparationLoaded = false;
        preparationPending = false;
        preparationPlaybackGeneration = -1;
        preparationWatchCode = -1;
        readyRequestPending = false;
        readyTransitionSent = false;
        heartbeatStarted = false;
        expectedPlaybackState = PartyPlaybackAdapter.PlaybackState.UNAVAILABLE;
        currentDelivery = null;
        executedDirectives.clear();
        resetClock();
        setPlaybackHold(true);
        status = "PREPARING";
        errorCode = null;
        publishState();
        enqueuePrepare();
    }

    private void enqueuePrepare() {
        if (!prepareSupported || partyId == null || preparationPending
                || hasPendingRequest("PREPARE")) {
            return;
        }
        locallyStopped = false;
        ready = false;
        converging = false;
        preparationLoaded = false;
        preparationPlaybackGeneration = -1;
        preparationWatchCode = -1;
        readyRequestPending = false;
        readyTransitionSent = false;
        heartbeatStarted = false;
        expectedPlaybackState = PartyPlaybackAdapter.PlaybackState.UNAVAILABLE;
        preparationPending = true;
        resetClock();
        setPlaybackHold(true);
        status = "PREPARING";
        enqueueRequest("PREPARE",
                new JSONObject().put("partyId", partyId), true, null);
        publishState();
    }

    private void handlePreparationResult(JSONObject result) {
        if (!partyIdEquals(result)) return;
        preparationPending = false;
        preparationWatchCode = result.optInt("watchCode", -1);
        if (!"LOADED".equals(result.optString("status"))
                || preparationWatchCode != 0) {
            preparationLoaded = false;
            setPlaybackHold(false);
            errorCode = result.optString("code", "PREPARATION_FAILED");
            status = "PREPARATION_FAILED";
            publishState();
            return;
        }
        preparationPlaybackGeneration =
                result.optLong("playbackGeneration", -1);
        if (preparationPlaybackGeneration < 0) {
            preparationLoaded = false;
            setPlaybackHold(false);
            errorCode = "INVALID_PREPARATION_GENERATION";
            status = "PREPARATION_FAILED";
            publishState();
            return;
        }
        preparationWatchCode = -1;
        preparationLoaded = true;
        currentDelivery = result.optJSONObject("delivery");
        adapter.setPartyHold(true);
        status = "CLOCKING";
        resetClock();
        requestClock();
        publishState();
    }

    private void requestClock() {
        if (partyId == null || clockSamples >= CLOCK_CALIBRATION_SAMPLE_COUNT
                || hasPendingRequest("CLOCK")
                || hasPendingRequest("CLOCK_REPLY")) {
            return;
        }
        enqueueRequest("CLOCK",
                new JSONObject().put("partyId", partyId), true, null);
    }

    private void handleClockProbe(JSONObject probe, long receivedMonoMs) {
        String probeId = probe.optString("probeId", null);
        if (probeId == null || partyId == null) return;
        enqueueRequest("CLOCK_REPLY", clockReplyFields(
                partyId, probeId, receivedMonoMs, adapter.elapsedRealtimeMs()),
                true, null);
    }

    static JSONObject clockReplyFields(String partyId, String probeId,
                                       long receivedMonoMs, long sentMonoMs) {
        return new JSONObject()
                .put("partyId", partyId)
                .put("probeId", probeId)
                .put("c1", receivedMonoMs)
                .put("c2", sentMonoMs);
    }

    private void handleClockModel(JSONObject model, long receivedMonoMs) {
        if (!partyIdEquals(model)) return;
        String requestId = model.optString("requestId", null);
        if (requestId == null || !acceptedClockReplies.add(requestId)) return;
        clockSamples++;
        clockModel = new PartyClockModel(
                model.optDouble("serverMinusClientMs", 0),
                model.optDouble("uncertaintyMs", Double.MAX_VALUE),
                model.optLong("p95RttMs", Long.MAX_VALUE),
                model.optBoolean("confident", false),
                clockSamples, receivedMonoMs);
        if (clockSamples < CLOCK_CALIBRATION_SAMPLE_COUNT) {
            requestClock();
        } else {
            evaluateReadiness();
        }
    }

    private void evaluateReadiness() {
        if (!readinessTransitionOpen(preparationLoaded, ready,
                readyRequestPending, readyTransitionSent)
                || clockModel == null
                || !clockModel.isConfident(adapter.elapsedRealtimeMs())) {
            return;
        }
        PartyPlaybackAdapter.Observation observation = adapter.observe();
        if (!isActivationReady(observation)) {
            status = "ACTIVATING";
            publishState();
            return;
        }
        if (observation.state == PartyPlaybackAdapter.PlaybackState.PLAYING) {
            adapter.setPartyHold(true);
            return;
        }
        readyRequestPending = true;
        readyTransitionSent = true;
        status = "READYING";
        enqueueRequest("READY", readyFields(
                partyId, stateVersion, preparationPlaybackGeneration,
                observation.bufferAheadMs),
                true, null);
        publishState();
    }

    private void handleReadyAck(JSONObject ack) {
        if (!partyIdEquals(ack)) return;
        long incomingVersion = ack.optLong("stateVersion", stateVersion);
        if (isStaleStateVersion(stateVersion, incomingVersion)) return;
        stateVersion = incomingVersion;
        commandSeq = Math.max(commandSeq,
                ack.optLong("commandSeq", commandSeq));
        readyRequestPending = false;
        readyTransitionSent = true;
        PartyPlaybackAdapter.Observation observation = adapter.observe();
        if (!isActivationReady(observation)) {
            ready = false;
            readyTransitionSent = false;
            setPlaybackHold(true);
            status = "ACTIVATING";
            publishState();
            return;
        }
        ready = true;
        converging = false;
        heartbeatStarted = true;
        status = observation == null ? "READY" : wireState(observation.state);
        errorCode = null;
        sendHeartbeat(observation, null, true);
        publishState();
    }

    private void applySnapshot(JSONObject newSnapshot) {
        if (!partyIdEqualsOrUnset(newSnapshot)) return;
        long incomingVersion = newSnapshot.optLong("stateVersion", stateVersion);
        if (incomingVersion < stateVersion) return;
        snapshot = new JSONObject(newSnapshot.toString());
        stateVersion = incomingVersion;
        commandSeq = Math.max(commandSeq,
                newSnapshot.optLong("commandSeq", commandSeq));
        if (!mediaWindow.update(newSnapshot)) {
            errorCode = "INVALID_GROWING_MEDIA_WINDOW";
            status = "ERROR";
        }
        publishState();
    }

    private void handleDirective(JSONObject directive, boolean correction) {
        if (!partyIdEquals(directive) || isStaleStateVersion(
                stateVersion, directive.optLong("stateVersion", -1))) {
            return;
        }
        long incomingStateVersion =
                directive.optLong("stateVersion", stateVersion);
        long incomingCommandSeq =
                directive.optLong("commandSeq", commandSeq);
        if (incomingCommandSeq < commandSeq) return;
        String key = incomingStateVersion + ":" + incomingCommandSeq + ":"
                + correction + ":" + directive.optLong("executeAtServerMonoMs", -1);
        if (!executedDirectives.add(key)) return;
        if (!correction && incomingStateVersion == stateVersion
                && incomingCommandSeq <= commandSeq) {
            return;
        }
        if (clockModel == null
                || !clockModel.isConfident(adapter.elapsedRealtimeMs())) {
            executedDirectives.remove(key);
            status = "CLOCKING";
            requestClock();
            publishState();
            return;
        }
        final PartyPlaybackAdapter.PlaybackState targetState =
                playbackState(directive.optString("targetState", ""));
        if (targetState != PartyPlaybackAdapter.PlaybackState.PLAYING
                && targetState != PartyPlaybackAdapter.PlaybackState.PAUSED) {
            errorCode = "INVALID_SERVER_MESSAGE";
            status = "ERROR";
            publishState();
            return;
        }
        expectedPlaybackState = targetState;
        if (!isActivationReady(adapter.observe())) {
            executedDirectives.remove(key);
            ready = false;
            converging = false;
            status = "ACTIVATING";
            errorCode = "NOT_READY";
            if (!correction) {
                sendCommandAck(new PartyPlaybackAdapter.ScheduledCommand(
                        incomingCommandSeq, incomingStateVersion,
                        directive.optString("cause", "COMMAND"),
                        mediaWindow.clampTarget(
                                directive.optLong("targetPositionMs", 0)),
                        targetState,
                        directive.optDouble("targetRate", 1.0),
                        adapter.elapsedRealtimeMs()), false);
            }
            publishState();
            return;
        }
        stateVersion = incomingStateVersion;
        commandSeq = Math.max(commandSeq, incomingCommandSeq);
        heartbeatStarted = true;
        readyRequestPending = false;
        ready = true;
        converging = true;
        status = "CONVERGING";
        cancelScheduledDirective();
        final long executionGeneration = directiveExecutionGeneration;
        long clientDeadline = clockModel.clientDeadline(
                directive.optLong("executeAtServerMonoMs"));
        final PartyPlaybackAdapter.ScheduledCommand command =
                new PartyPlaybackAdapter.ScheduledCommand(
                        incomingCommandSeq,
                        incomingStateVersion,
                        correction ? "CORRECTION"
                                : directive.optString("cause", "COMMAND"),
                        mediaWindow.clampTarget(
                                directive.optLong("targetPositionMs", 0)),
                        targetState,
                        directive.optDouble("targetRate", 1.0),
                        clientDeadline);
        long delay = Math.max(0, clientDeadline - adapter.elapsedRealtimeMs());
        scheduledDirective = executor.schedule(new Runnable() {
            @Override public void run() {
                if (command.stateVersion < stateVersion || locallyStopped) return;
                scheduledDirective = null;
                adapter.execute(command,
                        new PartyPlaybackAdapter.ExecutionCallback() {
                    @Override public void onComplete(final boolean success) {
                        executor.execute(new Runnable() {
                            @Override public void run() {
                                completeDirective(command, correction, success,
                                        executionGeneration);
                            }
                        });
                    }
                });
            }
        }, delay, TimeUnit.MILLISECONDS);
        publishState();
    }

    private void completeDirective(PartyPlaybackAdapter.ScheduledCommand command,
                                   boolean correction, boolean success,
                                   long executionGeneration) {
        if (!correction) sendCommandAck(command, success);
        if (executionGeneration != directiveExecutionGeneration
                || command.stateVersion < stateVersion || locallyStopped) {
            return;
        }
        converging = false;
        PartyPlaybackAdapter.Observation observation = adapter.observe();
        ready = success && isActivationReady(observation);
        if (success) {
            playbackHeld = command.targetState
                    == PartyPlaybackAdapter.PlaybackState.PAUSED;
        }
        if (success && !ready) {
            readyRequestPending = false;
            readyTransitionSent = false;
            status = "ACTIVATING";
        } else {
            status = success ? command.targetState.name() : "ERROR";
        }
        errorCode = success ? null : "COMMAND_FAILED";
        sendHeartbeat(observation, null, true);
        publishState();
    }

    private void sendCommandAck(PartyPlaybackAdapter.ScheduledCommand command,
                                boolean success) {
        enqueueRequest("ACK", new JSONObject()
                .put("partyId", partyId)
                .put("stateVersion", command.stateVersion)
                .put("commandSeq", command.commandSeq)
                .put("stage", "EXECUTED")
                .put("success", success), true, null);
    }

    private void sendAction(String action, Long targetPositionMs) {
        JSONObject fields = new JSONObject()
                .put("partyId", partyId)
                .put("stateVersion", stateVersion)
                .put("action", action);
        if ("SEEK".equals(action) && targetPositionMs != null) {
            fields.put("targetPositionMs", targetPositionMs);
        }
        enqueueRequest("ACTION", fields, true, null);
    }

    private void sendHeartbeat(PartyPlaybackAdapter.Observation observation,
                               String forcedState, boolean immediate) {
        if (partyId == null || !sessionAuthorized) return;
        if (!canSendHeartbeat(heartbeatStarted, forcedState)) return;
        if (observation == null) observation = adapter.observe();
        if (observation == null) return;
        String heartbeatState = forcedState == null
                ? heartbeatState(observation.state, expectedPlaybackState,
                        playbackHeld)
                : forcedState;
        long interval = heartbeatIntervalMs(
                heartbeatState, converging);
        if (!immediate
                && observation.sampleMonoMs - lastHeartbeatAt < interval) {
            return;
        }
        discardPendingHeartbeats();
        lastHeartbeatAt = observation.sampleMonoMs;
        enqueueRequest("HEARTBEAT", new JSONObject()
                .put("partyId", partyId)
                .put("stateVersion", stateVersion)
                .put("sampleMonoMs", observation.sampleMonoMs)
                .put("positionMs", Math.max(0, observation.positionMs))
                .put("bufferAheadMs", Math.max(0,
                        mediaWindow.clampBufferAhead(
                                observation.positionMs,
                                observation.bufferAheadMs)))
                .put("state", heartbeatState), true, null);
    }

    private void discardPendingHeartbeats() {
        if (inFlight != null && "HEARTBEAT".equals(inFlight.type)) {
            clearInFlight();
        }
        Iterator<PendingRequest> iterator = queue.iterator();
        while (iterator.hasNext()) {
            if ("HEARTBEAT".equals(iterator.next().type)) iterator.remove();
        }
    }

    static long heartbeatIntervalMs(String state, boolean converging) {
        return !converging && "PAUSED".equals(state)
                ? HEARTBEAT_PAUSED_INTERVAL_MS
                : HEARTBEAT_ACTIVE_INTERVAL_MS;
    }

    static String heartbeatState(
            PartyPlaybackAdapter.PlaybackState observedState,
            PartyPlaybackAdapter.PlaybackState expectedState,
            boolean playbackHeld) {
        if (playbackHeld
                && expectedState == PartyPlaybackAdapter.PlaybackState.PAUSED
                && observedState == PartyPlaybackAdapter.PlaybackState.BUFFERING) {
            return "PAUSED";
        }
        return wireState(observedState);
    }

    static boolean isStaleStateVersion(long current, long incoming) {
        return incoming < current;
    }

    static boolean readinessTransitionOpen(boolean preparationLoaded,
                                           boolean ready,
                                           boolean readyRequestPending,
                                           boolean readyTransitionSent) {
        return preparationLoaded && !ready && !readyRequestPending
                && !readyTransitionSent;
    }

    static JSONObject readyFields(String partyId, long stateVersion,
                                  long playbackGeneration, long bufferAheadMs) {
        return new JSONObject()
                .put("partyId", partyId)
                .put("stateVersion", stateVersion)
                .put("playbackGeneration", playbackGeneration)
                .put("bufferAheadMs", Math.max(0, bufferAheadMs));
    }

    static boolean canSendHeartbeat(boolean heartbeatStarted, String forcedState) {
        return heartbeatStarted || "STOPPED".equals(forcedState);
    }

    static boolean isTransientNotPrepared(String errorCode,
                                          boolean preparationPending,
                                          String status) {
        return "NOT_PREPARED".equals(errorCode)
                && (preparationPending || "PREPARING".equals(status));
    }

    static boolean completesRequest(String pendingType, String pendingRequestId,
                                    String incomingType, String incomingRequestId) {
        if (pendingRequestId != null
                && pendingRequestId.equals(incomingRequestId)) {
            return true;
        }
        return incomingRequestId == null
                && (("JOIN_CODE".equals(pendingType)
                && "JOIN_CODE".equals(incomingType))
                || ("ACTION".equals(pendingType)
                && "COMMAND".equals(incomingType)));
    }

    private void handleServerEnvelope(JSONObject envelope, long receivedMonoMs) {
        String type = envelope.optString("type", "");
        String requestId = envelope.optString("requestId", null);
        if ("ERROR".equals(type)) {
            handleError(envelope);
        } else if ("AVAILABLE".equals(type)) {
            applyAvailable(envelope);
        } else if ("CREATED".equals(type)
                || "JOINED".equals(type)
                || "RESUMED".equals(type)) {
            applyMembership(envelope);
        } else if ("SNAPSHOT".equals(type)) {
            applySnapshot(envelope);
        } else if ("CLOCK_PROBE".equals(type)) {
            handleClockProbe(envelope, receivedMonoMs);
        } else if ("CLOCK_MODEL".equals(type)) {
            handleClockModel(envelope, receivedMonoMs);
        } else if ("PREPARE_ACCEPTED".equals(type)) {
            status = "PREPARING";
            publishState();
        } else if ("PREPARATION_RESULT".equals(type)) {
            handlePreparationResult(envelope);
        } else if ("READY_ACK".equals(type)
                && inFlight != null
                && "READY".equals(inFlight.type)
                && inFlight.requestId.equals(requestId)) {
            handleReadyAck(envelope);
        } else if ("CORRECTION".equals(type)) {
            handleDirective(envelope, true);
        } else if ("COMMAND".equals(type)) {
            handleDirective(envelope, false);
        } else if ("HEARTBEAT_ACK".equals(type)) {
            long incomingVersion =
                    envelope.optLong("stateVersion", stateVersion);
            if (!isStaleStateVersion(stateVersion, incomingVersion)) {
                stateVersion = incomingVersion;
                commandSeq = Math.max(commandSeq,
                        envelope.optLong("commandSeq", commandSeq));
            }
        } else if ("JOIN_CODE".equals(type)) {
            joinCode = envelope.optString("joinCode", joinCode);
            joinCodeExpiresAtServerMonoMs =
                    envelope.optLong("expiresAtServerMonoMs",
                            joinCodeExpiresAtServerMonoMs);
            publishState();
        } else if ("HOST_CHANGED".equals(type)) {
            if (snapshot == null) snapshot = new JSONObject();
            snapshot.put("hostParticipantId",
                    envelope.optString("hostParticipantId", null));
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
        } else if ("LEFT".equals(type) || "ENDED".equals(type)) {
            clearMembership(type);
        }

        if (inFlight != null && completesRequest(
                inFlight.type, inFlight.requestId, type, requestId)) {
            PendingRequest completed = inFlight;
            clearInFlight();
            if (completed.handler != null) completed.handler.onReply(envelope);
            sendNext();
        }
        if ("CLOCK_MODEL".equals(type)) requestClock();
    }

    private void handleError(JSONObject envelope) {
        errorCode = envelope.optString("code", "UNKNOWN");
        if (isTransientNotPrepared(errorCode, preparationPending, status)) {
            errorCode = null;
            status = "PREPARING";
            publishState();
            return;
        }
        if ("NOT_AVAILABLE".equals(errorCode)) {
            availability = Availability.UNAVAILABLE;
            status = "NOT_AVAILABLE";
        } else if ("PARTY_GONE".equals(errorCode)
                || "NOT_MEMBER".equals(errorCode)
                || "RESUME_EXPIRED".equals(errorCode)) {
            clearMembership(errorCode);
            return;
        } else if ("STALE_STATE".equals(errorCode)) {
            JSONObject current = envelope.optJSONObject("snapshot");
            if (current != null) applySnapshot(current);
            status = "STALE_STATE";
        } else if ("CLOCK_UNCERTAIN".equals(errorCode)) {
            resetClock();
            requestClock();
            status = "CLOCKING";
        } else if ("PREPARATION_FAILED".equals(errorCode)) {
            preparationPending = false;
            preparationLoaded = false;
            setPlaybackHold(false);
            status = "PREPARATION_FAILED";
        } else {
            status = "ERROR";
        }
        publishState();
    }

    private void maintain() {
        long now = adapter.elapsedRealtimeMs();
        if (availability == Availability.AVAILABLE
                && now - lastHelloAt >= HELLO_REFRESH_INTERVAL_MS
                && inFlight == null && queue.isEmpty()) {
            sendHello();
        }
        if (partyId == null || locallyStopped) return;
        if (preparationLoaded && !ready) {
            if (clockSamples < CLOCK_CALIBRATION_SAMPLE_COUNT) requestClock();
            else evaluateReadiness();
        }
        if (!heartbeatStarted) return;
        PartyPlaybackAdapter.Observation observation = adapter.observe();
        if (observation == null) return;
        String observedState = heartbeatState(
                observation.state, expectedPlaybackState, playbackHeld);
        if ("BUFFERING".equals(observedState)) status = "BUFFERING";
        else if (!converging && ready) status = observedState;
        sendHeartbeat(observation, observedState, false);
    }

    private void beginReconnect() {
        executor.execute(new Runnable() {
            @Override public void run() {
                if (partyId == null || resumeRequired) return;
                requireResumeAfterDisconnect();
                adapter.reconnectControlTransport();
            }
        });
    }

    private void requireResumeAfterDisconnect() {
        resumeState.markDisconnected(adapter.elapsedRealtimeMs());
        resumeRequired = true;
        clearInFlight();
        queue.clear();
        requestSeq = 0;
        serverEpoch = null;
        sessionHandle = null;
        sessionToken = null;
        sessionTokenExpiresAtServerMonoMs = 0;
        sessionAuthorized = false;
        availability = Availability.UNKNOWN;
        ready = false;
        converging = false;
        readyRequestPending = false;
        readyTransitionSent = false;
        cancelScheduledDirective();
        setPlaybackHold(true);
        status = "RECONNECTING";
        publishState();
    }

    private void terminalMembershipRequest(final String type) {
        executor.execute(new Runnable() {
            @Override public void run() {
                if (partyId == null) return;
                enqueueRequest(type,
                        new JSONObject().put("partyId", partyId),
                        true, null);
            }
        });
    }

    private void clearMembership(String terminalStatus) {
        cancelScheduledDirective();
        setPlaybackHold(false);
        resumeState.clear();
        resumeRequired = false;
        partyId = null;
        participantId = null;
        recoveryToken = null;
        snapshot = null;
        currentDelivery = null;
        joinCode = null;
        invitedPartyId = null;
        connectedSessionHandles.clear();
        ready = false;
        locallyStopped = false;
        converging = false;
        preparationLoaded = false;
        preparationPending = false;
        preparationPlaybackGeneration = -1;
        preparationWatchCode = -1;
        readyRequestPending = false;
        readyTransitionSent = false;
        heartbeatStarted = false;
        expectedPlaybackState = PartyPlaybackAdapter.PlaybackState.UNAVAILABLE;
        executedDirectives.clear();
        mediaWindow.clear();
        resetClock();
        status = terminalStatus;
        publishState();
    }

    private void enqueueRequest(String type, JSONObject fields,
                                boolean retryAllowed, ReplyHandler handler) {
        if (!supportsRequiredGuarantees() || queue.size() >= 64) return;
        if (!"HELLO".equals(type)
                && (serverEpoch == null || sessionToken == null)) return;
        JSONObject envelope = requestEnvelope(type, requestId(), ++requestSeq,
                serverEpoch, sessionToken, fields);
        try {
            PartyProtocol.validateEnvelope(envelope);
        } catch (PartyProtocolException e) {
            errorCode = "INVALID_REQUEST";
            status = "ERROR";
            publishState();
            return;
        }
        PendingRequest pending = new PendingRequest(
                type, envelope.getString("requestId"), envelope.toString(),
                retryAllowed, handler);
        queue.add(pending);
        sendNext();
    }

    static JSONObject requestEnvelope(String type, String requestId,
                                      long requestSeq, String serverEpoch,
                                      String sessionToken, JSONObject fields) {
        JSONObject envelope = new JSONObject()
                .put("schemaVersion", PartyProtocol.SCHEMA_VERSION)
                .put("type", type)
                .put("requestId", requestId)
                .put("requestSeq", requestSeq);
        if (!"HELLO".equals(type)) {
            envelope.put("serverEpoch", serverEpoch)
                    .put("sessionToken", sessionToken);
        }
        if (fields != null) {
            for (String key : fields.keySet()) {
                envelope.put(key, fields.get(key));
            }
        }
        return envelope;
    }

    private void sendNext() {
        if (inFlight != null || queue.isEmpty()) return;
        inFlight = queue.remove();
        sendInFlight();
    }

    private void sendInFlight() {
        if (inFlight == null) return;
        if (requestTimeout != null) requestTimeout.cancel(false);
        MiniClientConnection.PartyMessageSendResult result =
                connection.postPartyMessage(inFlight.serialized);
        if (result == MiniClientConnection.PartyMessageSendResult.DEFERRED) {
            requestTimeout = null;
            return;
        }
        if (result == MiniClientConnection.PartyMessageSendResult.FAILED) {
            requestTimeout = null;
            errorCode = "TRANSPORT_FAILED";
            requireResumeAfterDisconnect();
            adapter.reconnectControlTransport();
            return;
        }
        inFlight.sends++;
        requestTimeout = executor.schedule(new Runnable() {
            @Override public void run() {
                if (inFlight == null) return;
                if (inFlight.retryAllowed && inFlight.sends == 1) {
                    sendInFlight();
                } else {
                    errorCode = "REQUEST_TIMEOUT";
                    requireResumeAfterDisconnect();
                    adapter.reconnectControlTransport();
                }
            }
        }, REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void clearInFlight() {
        if (requestTimeout != null) requestTimeout.cancel(false);
        requestTimeout = null;
        inFlight = null;
    }

    private boolean hasPendingRequest(String type) {
        if (inFlight != null && type.equals(inFlight.type)) return true;
        for (PendingRequest pending : queue) {
            if (type.equals(pending.type)) return true;
        }
        return false;
    }

    private void cancelScheduledDirective() {
        directiveExecutionGeneration++;
        if (scheduledDirective != null) scheduledDirective.cancel(false);
        scheduledDirective = null;
        adapter.cancelScheduledAction();
    }

    private void resetClock() {
        clockSamples = 0;
        clockModel = null;
        acceptedClockReplies.clear();
    }

    private void setPlaybackHold(boolean hold) {
        playbackHeld = hold;
        adapter.setPartyHold(hold);
    }

    private boolean isHost() {
        return snapshot != null && participantId != null
                && participantId.equals(
                        snapshot.optString("hostParticipantId", null));
    }

    private boolean isJoinCodeExpired() {
        if (joinCode == null || joinCodeExpiresAtServerMonoMs <= 0
                || clockModel == null) {
            return false;
        }
        long serverNow = Math.round(adapter.elapsedRealtimeMs()
                + clockModel.getServerMinusClientMs());
        return serverNow >= joinCodeExpiresAtServerMonoMs;
    }

    private boolean partyIdEquals(JSONObject envelope) {
        return partyId != null
                && partyId.equals(envelope.optString("partyId", null));
    }

    private boolean partyIdEqualsOrUnset(JSONObject envelope) {
        String value = envelope.optString("partyId", null);
        return partyId == null || value == null || partyId.equals(value);
    }

    private String actionFor(SageCommand command) {
        PartyPlaybackAdapter.Observation observation = adapter.observe();
        return sharedActionFor(command,
                observation == null ? null : observation.state);
    }

    static String sharedActionFor(
            SageCommand command, PartyPlaybackAdapter.PlaybackState state) {
        if (command == SageCommand.PLAY) return "PLAY";
        if (command == SageCommand.PAUSE) return "PAUSE";
        if (command == SageCommand.PLAY_PAUSE) {
            return state == PartyPlaybackAdapter.PlaybackState.PLAYING
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
        if (state == null) return "ERROR";
        if (state == PartyPlaybackAdapter.PlaybackState.UNAVAILABLE) return "ERROR";
        return state.name();
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    private void publishState() {
        State state = new State(this);
        publishedState.set(state);
        log.logInfo("Party state availability=" + state.availability
                + " status=" + state.status
                + " joined=" + state.joined
                + (state.errorCode == null ? "" : " error=" + state.errorCode));
        client.eventbus().post(new PartyStateChanged(state));
    }

    static boolean isActivationReady(PartyPlaybackAdapter.Observation observation) {
        return observation != null && observation.prepared
                && observation.activated;
    }
}
