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
    private enum DirectiveStage {
        NONE, PREPARING, ARMED, REVEALING, PRESENTING
    }

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
        public final Integer invitedMediaFileId;
        public final JSONObject participantStates;
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
            invitedMediaFileId = source.invitedMediaFileId;
            participantStates = new JSONObject(
                    source.participantStates.toString());
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

    private static final class PendingAction {
        final String action;
        final Long targetPositionMs;
        final long generation;
        final boolean clockBarrierRetry;

        PendingAction(String action, Long targetPositionMs, long generation,
                      boolean clockBarrierRetry) {
            this.action = action;
            this.targetPositionMs = targetPositionMs;
            this.generation = generation;
            this.clockBarrierRetry = clockBarrierRetry;
        }
    }

    private static final long REQUEST_TIMEOUT_MS = 15_000;
    static final long HELLO_REFRESH_INTERVAL_MS = 300_000;
    static final long HEARTBEAT_ACTIVE_INTERVAL_MS = 5_000;
    static final long HEARTBEAT_PAUSED_INTERVAL_MS = 15_000;
    static final long CLOCK_REFRESH_INTERVAL_MS = 20_000;
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
    private final JSONObject participantStates = new JSONObject();
    private final AtomicReference<State> publishedState;

    private Availability availability = Availability.UNKNOWN;
    private PendingRequest inFlight;
    private ScheduledFuture<?> requestTimeout;
    private ScheduledFuture<?> scheduledDirective;
    private long directiveExecutionGeneration;
    private boolean directiveExecutionInProgress;
    private PartyPlaybackAdapter.ScheduledCommand pendingPresentedDirective;
    private long pendingPresentedDirectiveGeneration = -1;
    private boolean pendingDirectiveCorrection;
    private boolean directiveExecutionCompleted;
    private boolean presentedAckPending;
    private boolean armedAckPending;
    private DirectiveStage directiveStage = DirectiveStage.NONE;
    private ScheduledFuture<?> periodic;
    private long requestSeq;
    private String serverEpoch;
    private String sessionHandle;
    private String sessionToken;
    private long sessionTokenExpiresAtServerMonoMs;
    private long reconnectGraceMs = PartyResumeState.DEFAULT_GRACE_MS;
    private long lastHelloAt;
    private long lastHeartbeatAt;
    private String lastHeartbeatState;
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
    private Integer invitedMediaFileId;
    private boolean prepareSupported;
    private boolean preparationLoaded;
    private boolean preparationPending;
    private String preparationId;
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
    private long lastClockModelAtMs = -1;
    private JSONObject pendingClockDirective;
    private boolean pendingClockCorrection;
    private PendingAction pendingAction;
    private long pendingActionGeneration;
    private String clockBarrierRetryRequestId;
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
                preparationId = null;
                converging = false;
                resetClock();
                lastClockModelAtMs = -1;
                pendingAction = null;
                clockBarrierRetryRequestId = null;
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
                    submitAction(action, null);
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
        if ((expectedPartyId == null) != (mediaFileId == null)) {
            throw new PartyProtocolException(
                    "Invited JOIN requires partyId and mediaFileId together");
        }
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
                    submitAction("JUMP", Math.max(0, targetPositionMs));
                }
            }
        });
        return true;
    }

    public void comskip() {
        executor.execute(new Runnable() {
            @Override public void run() {
                if (isHost() && ready && !locallyStopped && !converging) {
                    submitAction("COMSKIP", null);
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
                if (!canPublishStablePlaybackState(
                        converging, pendingPresentedDirective != null)) {
                    publishState();
                    return;
                }
                PartyPlaybackAdapter.Observation observation = adapter.observe();
                if (observation != null) {
                    String observedState = heartbeatState(
                            observation.state, expectedPlaybackState,
                            playbackHeld);
                    if (!"LATE".equals(status)) status = observedState;
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
                    if (pendingPresentedDirective != null
                            && pendingPresentedDirectiveGeneration
                            == directiveExecutionGeneration) {
                        PartyPlaybackAdapter.Observation observation =
                                adapter.observe();
                        if (shouldCompleteExecutionFromPresentation(
                                directiveExecutionInProgress,
                                directiveExecutionCompleted,
                                pendingPresentedDirective.targetState)) {
                            completeDirective(
                                    pendingPresentedDirective,
                                    pendingDirectiveCorrection,
                                    true,
                                    pendingPresentedDirectiveGeneration);
                        } else if (directiveStage
                                == DirectiveStage.PREPARING) {
                            sendArmedAck(
                                    pendingPresentedDirective, observation);
                        } else if (directiveStage
                                == DirectiveStage.PRESENTING) {
                            sendPresentedAck(
                                    pendingPresentedDirective, observation);
                        }
                    } else if (!ready) {
                        evaluateReadiness();
                    }
                } else {
                    if (shouldPreserveSettledReadiness(
                            ready, heartbeatStarted, converging,
                            pendingPresentedDirective != null)) {
                        publishState();
                        return;
                    }
                    ready = false;
                    readyRequestPending = false;
                    readyTransitionSent = false;
                    if (shouldCancelOnReadinessLoss(
                            converging, directiveExecutionInProgress)) {
                        cancelScheduledDirective();
                        setPlaybackHold(true);
                    }
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
        stateVersion = membershipScopedCounter(
                partyId, newPartyId, stateVersion);
        commandSeq = membershipScopedCounter(
                partyId, newPartyId, commandSeq);
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
        preparationId = null;
        preparationPlaybackGeneration = -1;
        preparationWatchCode = -1;
        readyRequestPending = false;
        readyTransitionSent = false;
        heartbeatStarted = false;
        expectedPlaybackState = PartyPlaybackAdapter.PlaybackState.UNAVAILABLE;
        currentDelivery = null;
        executedDirectives.clear();
        cancelScheduledDirective();
        resetClock();
        setPlaybackHold(true);
        status = "CLOCKING";
        errorCode = null;
        publishState();
        requestClock();
    }

    private void enqueuePrepare() {
        if (!prepareSupported || partyId == null || preparationPending
                || preparationLoaded || preparationId != null
                || hasPendingRequest("PREPARE")
                || !hasConfidentClock(adapter.elapsedRealtimeMs())) {
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
        preparationId = null;
        setPlaybackHold(true);
        status = "PREPARING";
        enqueueRequest("PREPARE",
                new JSONObject().put("partyId", partyId), true, null);
        publishState();
    }

    private void handlePrepareAccepted(JSONObject accepted) {
        if (!partyIdEquals(accepted)) return;
        String acceptedPreparationId =
                accepted.optString("preparationId", null);
        if (acceptedPreparationId == null
                || acceptedPreparationId.isEmpty()) {
            preparationPending = false;
            errorCode = "INVALID_SERVER_MESSAGE";
            status = "ERROR";
            publishState();
            return;
        }
        preparationId = acceptedPreparationId;
        preparationPending = true;
        status = "PREPARING";
        publishState();
    }

    private void handlePreparationResult(JSONObject result) {
        if (!partyIdEquals(result)) return;
        String resultPreparationId = result.optString("preparationId", null);
        if (preparationId == null || resultPreparationId == null
                || !preparationId.equals(resultPreparationId)) {
            log.logWarning("Ignoring stale Party preparation result"
                    + " expected=" + preparationId
                    + " actual=" + resultPreparationId);
            return;
        }
        preparationPending = false;
        preparationWatchCode = result.optInt("watchCode", -1);
        if (!"LOADED".equals(result.optString("status"))
                || preparationWatchCode != 0) {
            preparationLoaded = false;
            preparationId = null;
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
            preparationId = null;
            setPlaybackHold(false);
            errorCode = "INVALID_PREPARATION_GENERATION";
            status = "PREPARATION_FAILED";
            publishState();
            return;
        }
        preparationWatchCode = -1;
        preparationLoaded = true;
        preparationId = null;
        currentDelivery = result.optJSONObject("delivery");
        adapter.setPartyHold(true);
        status = "PREPARED";
        evaluateReadiness();
        publishState();
    }

    private void requestClock() {
        boolean calibrated = clockSamples >= CLOCK_CALIBRATION_SAMPLE_COUNT
                && clockModel != null
                && clockModel.isConfident(adapter.elapsedRealtimeMs());
        if (partyId == null || calibrated
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
        lastClockModelAtMs = receivedMonoMs;
        if (clockSamples < CLOCK_CALIBRATION_SAMPLE_COUNT
                || !clockModel.isConfident(adapter.elapsedRealtimeMs())) {
            requestClock();
        } else if (clockModel.isConfident(adapter.elapsedRealtimeMs())
                && pendingClockDirective != null) {
            JSONObject directive = pendingClockDirective;
            boolean correction = pendingClockCorrection;
            pendingClockDirective = null;
            pendingClockCorrection = false;
            handleDirective(directive, correction);
        } else if (clockModel.isConfident(adapter.elapsedRealtimeMs())
                && pendingAction != null) {
            requestSnapshotForPendingAction();
        } else if (clockModel.isConfident(adapter.elapsedRealtimeMs())
                && !preparationLoaded && !preparationPending
                && preparationId == null) {
            enqueuePrepare();
        } else if (ready) {
            status = restoredClockStatus(converging, adapter.observe());
            errorCode = null;
            publishState();
        } else {
            evaluateReadiness();
        }
    }

    private void evaluateReadiness() {
        if (!readinessTransitionOpen(preparationLoaded, ready,
                readyRequestPending, readyTransitionSent,
                pendingPresentedDirective != null, converging,
                heartbeatStarted)
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

    private void handleLegacyReadyAck(JSONObject ack) {
        if (!partyIdEquals(ack)) return;
        readyRequestPending = false;
        readyTransitionSent = true;
        if (!converging && pendingPresentedDirective == null
                && !heartbeatStarted) {
            status = "ACTIVATING";
        }
        log.logWarning("Ignoring legacy READY_ACK without changing Party state");
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
        if (!partyIdEquals(directive)) {
            return;
        }
        if (!hasRequiredDirectiveFields(directive)) {
            log.logWarning("Rejected incomplete Party directive fields="
                    + directive.toString());
            errorCode = "INVALID_SERVER_MESSAGE";
            status = "ERROR";
            publishState();
            return;
        }
        if (isStaleStateVersion(
                stateVersion, directive.optLong("stateVersion", -1))) {
            return;
        }
        long incomingStateVersion =
                directive.optLong("stateVersion", stateVersion);
        long incomingCommandSeq =
                directive.optLong("commandSeq", commandSeq);
        if (incomingCommandSeq < commandSeq) return;
        if (!correction && incomingStateVersion == stateVersion
                && incomingCommandSeq <= commandSeq) {
            return;
        }
        if (clockModel == null
                || !clockModel.isConfident(adapter.elapsedRealtimeMs())) {
            pendingClockDirective = new JSONObject(directive.toString());
            pendingClockCorrection = correction;
            beginClockCalibration();
            return;
        }
        pendingClockDirective = null;
        pendingClockCorrection = false;
        final PartyPlaybackAdapter.PlaybackState targetState =
                playbackState(directive.optString("targetState", ""));
        final String directiveId =
                directive.optString("directiveId", null);
        final PartyPlaybackAdapter.CorrectionKind correctionKind =
                correctionKind(directive, correction);
        final boolean barrier = directive.optBoolean("barrier", false);
        final String phase = directive.optString("phase", "");
        if ((targetState != PartyPlaybackAdapter.PlaybackState.PLAYING
                && targetState != PartyPlaybackAdapter.PlaybackState.PAUSED)
                || directiveId == null
                || correctionKind == null
                || directive.optDouble("targetRate", 1.0) != 1.0
                || (barrier && !"PREPARE".equals(phase))) {
            log.logWarning("Rejected Party directive"
                    + " type=" + directive.optString("type", "")
                    + " directiveId=" + directiveId
                    + " targetState=" + directive.optString("targetState", "")
                    + " kind=" + directive.optString("kind", "")
                    + " targetRate=" + directive.opt("targetRate")
                    + " fields=" + directive.toString());
            errorCode = "INVALID_SERVER_MESSAGE";
            status = "ERROR";
            publishState();
            return;
        }
        final String key = directiveId;
        if (!executedDirectives.add(key)) return;
        boolean activationReady = isActivationReady(adapter.observe());
        log.logInfo("Party directive type="
                + (correction ? "CORRECTION" : "COMMAND")
                + " stateVersion=" + incomingStateVersion
                + " commandSeq=" + incomingCommandSeq
                + " targetState=" + targetState
                + " directiveId=" + directiveId
                + " correctionKind=" + correctionKind
                + " barrier=" + barrier
                + " phase=" + phase
                + " targetPositionMs="
                + directive.optLong("targetPositionMs", 0)
                + " executeAtServerMonoMs="
                + directive.optLong("executeAtServerMonoMs", -1)
                + " admitted=" + heartbeatStarted
                + " activated=" + activationReady);
        if (!canAcceptDirective(converging)) {
            sendExecutionFailureAck(new PartyPlaybackAdapter.ScheduledCommand(
                    directiveId, incomingCommandSeq, incomingStateVersion,
                    correction ? "CORRECTION"
                            : directive.optString("cause", "COMMAND"),
                    correctionKind,
                    mediaWindow.clampTarget(
                            directive.optLong("targetPositionMs", 0)),
                    targetState, 1.0, adapter.elapsedRealtimeMs()));
            return;
        }
        if (!canExecuteDirective(
                correction, targetState, preparationLoaded,
                readyTransitionSent, heartbeatStarted, activationReady)) {
            ready = false;
            converging = false;
            status = "ACTIVATING";
            errorCode = "NOT_READY";
            sendExecutionFailureAck(new PartyPlaybackAdapter.ScheduledCommand(
                        directiveId,
                        incomingCommandSeq, incomingStateVersion,
                        directive.optString("cause", "COMMAND"),
                        correctionKind,
                        mediaWindow.clampTarget(
                                directive.optLong("targetPositionMs", 0)),
                        targetState,
                        directive.optDouble("targetRate", 1.0),
                        adapter.elapsedRealtimeMs()));
            publishState();
            return;
        }
        expectedPlaybackState = targetState;
        stateVersion = incomingStateVersion;
        commandSeq = Math.max(commandSeq, incomingCommandSeq);
        readyRequestPending = false;
        ready = true;
        converging = true;
        status = "CONVERGING";
        cancelScheduledDirective();
        final long executionGeneration = directiveExecutionGeneration;
        long clientDeadline = clockModel.clientDeadline(
                directive.optLong("executeAtServerMonoMs"));
        long visibleClientDeadline = barrier
                ? clockModel.clientDeadline(
                        directive.optLong("visibleAtServerMonoMs"))
                : -1;
        final PartyPlaybackAdapter.ScheduledCommand command =
                new PartyPlaybackAdapter.ScheduledCommand(
                        directiveId,
                        incomingCommandSeq,
                        incomingStateVersion,
                        correction ? "CORRECTION"
                                : directive.optString("cause", "COMMAND"),
                        correctionKind,
                        mediaWindow.clampTarget(
                                directive.optLong("targetPositionMs", 0)),
                        targetState,
                        directive.optDouble("targetRate", 1.0),
                        clientDeadline,
                        barrier,
                        phase,
                        visibleClientDeadline,
                        directive.optLong(
                                "predictedPresentationLatencyMs", 0),
                        directive.optLong(
                                "presentationUncertaintyMs", 0));
        pendingPresentedDirective = command;
        pendingPresentedDirectiveGeneration = executionGeneration;
        pendingDirectiveCorrection = correction;
        directiveExecutionCompleted = false;
        presentedAckPending = false;
        armedAckPending = false;
        if (barrier) {
            directiveStage = DirectiveStage.PREPARING;
            adapter.coverCurrentFrame(
                    new PartyPlaybackAdapter.ExecutionCallback() {
                @Override public void onComplete(final boolean success) {
                    executor.execute(new Runnable() {
                        @Override public void run() {
                            if (!isCurrentDirectiveExecution(
                                    executionGeneration,
                                    directiveExecutionGeneration,
                                    locallyStopped)) {
                                return;
                            }
                            if (!success) {
                                completeDirective(command, correction, false,
                                        executionGeneration);
                                return;
                            }
                            scheduleDirectiveExecution(command, correction,
                                    executionGeneration);
                        }
                    });
                }
            });
        } else {
            directiveStage = DirectiveStage.PRESENTING;
            scheduleDirectiveExecution(
                    command, correction, executionGeneration);
        }
        publishState();
    }

    private void scheduleDirectiveExecution(
            final PartyPlaybackAdapter.ScheduledCommand command,
            final boolean correction, final long executionGeneration) {
        long delay = Math.max(0,
                command.executeAtClientMonoMs - adapter.elapsedRealtimeMs());
        scheduledDirective = executor.schedule(new Runnable() {
            @Override public void run() {
                if (!isCurrentDirectiveExecution(
                        executionGeneration, directiveExecutionGeneration,
                        locallyStopped)) {
                    return;
                }
                scheduledDirective = null;
                directiveExecutionInProgress = true;
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
    }

    private void completeDirective(PartyPlaybackAdapter.ScheduledCommand command,
                                   boolean correction, boolean success,
                                   long executionGeneration) {
        if (!isCurrentDirectiveExecution(
                executionGeneration, directiveExecutionGeneration,
                locallyStopped)
                || command != pendingPresentedDirective
                || directiveExecutionCompleted) {
            return;
        }
        directiveExecutionCompleted = true;
        directiveExecutionInProgress = false;
        PartyPlaybackAdapter.Observation observation = adapter.observe();
        if (success) {
            playbackHeld = command.barrier || command.targetState
                    == PartyPlaybackAdapter.PlaybackState.PAUSED;
            readyRequestPending = false;
            converging = true;
            status = "ACTIVATING";
            if (command.barrier && isActivationReady(observation)) {
                sendArmedAck(command, observation);
            } else if (isPresentedObservationFor(command, observation)) {
                sendPresentedAck(command, observation);
            }
        } else {
            if (command.barrier) {
                directiveStage = DirectiveStage.PREPARING;
                sendArmedAck(command, observation, false);
            } else {
                sendExecutionFailureAck(command);
            }
            converging = false;
            status = "ERROR";
            errorCode = "COMMAND_FAILED";
        }
        publishState();
    }

    static boolean isCurrentDirectiveExecution(long executionGeneration,
                                               long currentGeneration,
                                               boolean locallyStopped) {
        return executionGeneration == currentGeneration && !locallyStopped;
    }

    static boolean shouldCancelOnReadinessLoss(boolean converging,
                                               boolean executionInProgress) {
        return !converging && !executionInProgress;
    }

    static boolean shouldCompleteExecutionFromPresentation(
            boolean executionInProgress, boolean executionCompleted,
            PartyPlaybackAdapter.PlaybackState targetState) {
        return executionInProgress && !executionCompleted
                && targetState == PartyPlaybackAdapter.PlaybackState.PAUSED;
    }

    static boolean shouldPreserveSettledReadiness(
            boolean ready, boolean heartbeatStarted, boolean converging,
            boolean presentedDirectivePending) {
        return ready && heartbeatStarted && !converging
                && !presentedDirectivePending;
    }

    private void sendExecutionFailureAck(
            PartyPlaybackAdapter.ScheduledCommand command) {
        enqueueRequest("ACK", ackFields(
                partyId, command, "EXECUTED", false, null),
                true, new ReplyHandler() {
                    @Override public void onReply(JSONObject reply) {
                        if ("ERROR".equals(reply.optString("type", ""))) {
                            failPendingDirective();
                        }
                    }
                });
    }

    private void sendArmedAck(PartyPlaybackAdapter.ScheduledCommand command,
                              PartyPlaybackAdapter.Observation observation) {
        sendArmedAck(command, observation, true);
    }

    private void sendArmedAck(
            PartyPlaybackAdapter.ScheduledCommand command,
            PartyPlaybackAdapter.Observation observation, boolean success) {
        if (armedAckPending
                || command != pendingPresentedDirective
                || pendingPresentedDirectiveGeneration
                != directiveExecutionGeneration
                || directiveStage != DirectiveStage.PREPARING) {
            return;
        }
        if (observation == null) observation = adapter.observe();
        if (observation == null) return;
        final boolean ackSuccess = success;
        final PartyPlaybackAdapter.ScheduledCommand pendingCommand = command;
        final long pendingGeneration = pendingPresentedDirectiveGeneration;
        PendingRequest request = enqueueRequest("ACK", ackFields(
                partyId, command, "ARMED", success, observation),
                true, new ReplyHandler() {
                    @Override public void onReply(JSONObject reply) {
                        armedAckPending = false;
                        if (pendingCommand != pendingPresentedDirective
                                || pendingGeneration
                                != directiveExecutionGeneration) {
                            return;
                        }
                        if (!"ACK".equals(reply.optString("type", ""))) {
                            failPendingDirective();
                            return;
                        }
                        if (!ackSuccess) {
                            failPendingDirective();
                            status = "ERROR";
                            errorCode = "COMMAND_FAILED";
                            publishState();
                            return;
                        }
                        directiveStage = DirectiveStage.ARMED;
                        status = "CONVERGING";
                        errorCode = null;
                        publishState();
                    }
                });
        if (request != null) {
            armedAckPending = true;
        } else {
            failPendingDirective();
        }
    }

    private void handleReveal(final JSONObject reveal) {
        if (!hasRequiredRevealFields(reveal)
                || !partyIdEquals(reveal)
                || pendingPresentedDirective == null
                || directiveStage != DirectiveStage.ARMED
                || !matchesDirective(reveal, pendingPresentedDirective)
                || clockModel == null
                || !clockModel.isConfident(adapter.elapsedRealtimeMs())) {
            return;
        }
        final PartyPlaybackAdapter.ScheduledCommand command =
                pendingPresentedDirective;
        final long executionGeneration =
                pendingPresentedDirectiveGeneration;
        final long revealDeadline = clockModel.clientDeadline(
                reveal.optLong("executeAtServerMonoMs", -1));
        if (revealDeadline < 0) return;
        directiveStage = DirectiveStage.REVEALING;
        if (scheduledDirective != null) scheduledDirective.cancel(false);
        long delay = Math.max(
                0, revealDeadline - adapter.elapsedRealtimeMs());
        scheduledDirective = executor.schedule(new Runnable() {
            @Override public void run() {
                if (!isCurrentDirectiveExecution(
                        executionGeneration, directiveExecutionGeneration,
                        locallyStopped)
                        || command != pendingPresentedDirective
                        || directiveStage != DirectiveStage.REVEALING) {
                    return;
                }
                scheduledDirective = null;
                directiveStage = DirectiveStage.PRESENTING;
                adapter.reveal(command,
                        new PartyPlaybackAdapter.ExecutionCallback() {
                    @Override public void onComplete(final boolean success) {
                        executor.execute(new Runnable() {
                            @Override public void run() {
                                if (!success) {
                                    failPendingDirective();
                                    status = "ERROR";
                                    errorCode = "REVEAL_FAILED";
                                    publishState();
                                    return;
                                }
                                PartyPlaybackAdapter.Observation observation =
                                        adapter.observe();
                                if (isPresentedObservationFor(
                                        command, observation)) {
                                    sendPresentedAck(command, observation);
                                }
                            }
                        });
                    }
                });
            }
        }, delay, TimeUnit.MILLISECONDS);
        publishState();
    }

    static boolean matchesDirective(
            JSONObject message,
            PartyPlaybackAdapter.ScheduledCommand command) {
        return message != null && command != null
                && command.directiveId.equals(
                        message.optString("directiveId", null))
                && command.stateVersion
                == message.optLong("stateVersion", -1)
                && command.commandSeq
                == message.optLong("commandSeq", -1);
    }

    static boolean hasRequiredRevealFields(JSONObject reveal) {
        return reveal != null
                && reveal.has("partyId")
                && reveal.has("directiveId")
                && reveal.has("stateVersion")
                && reveal.has("commandSeq")
                && reveal.has("executeAtServerMonoMs")
                && !reveal.optString("partyId", "").isEmpty()
                && !reveal.optString("directiveId", "").isEmpty();
    }

    private void sendPresentedAck(PartyPlaybackAdapter.ScheduledCommand command,
                                  PartyPlaybackAdapter.Observation observation) {
        if (presentedAckPending
                || command != pendingPresentedDirective
                || pendingPresentedDirectiveGeneration
                != directiveExecutionGeneration) {
            return;
        }
        if (observation == null) observation = adapter.observe();
        if (!isPresentedObservationFor(command, observation)) return;
        final PartyPlaybackAdapter.ScheduledCommand pendingCommand = command;
        final long pendingGeneration = pendingPresentedDirectiveGeneration;
        PendingRequest request = enqueueRequest("ACK", ackFields(
                partyId, command, "PRESENTED", true, observation),
                true, new ReplyHandler() {
                    @Override public void onReply(JSONObject reply) {
                        presentedAckPending = false;
                        if (pendingCommand != pendingPresentedDirective
                                || pendingGeneration
                                != directiveExecutionGeneration) {
                            return;
                        }
                        if (!"ACK".equals(reply.optString("type", ""))) {
                            failPendingDirective();
                            return;
                        }
                        PartyPlaybackAdapter.Observation accepted =
                                adapter.observe();
                        ready = true;
                        readyRequestPending = false;
                        readyTransitionSent = true;
                        converging = false;
                        heartbeatStarted = true;
                        errorCode = null;
                        directiveStage = DirectiveStage.NONE;
                        pendingPresentedDirective = null;
                        pendingPresentedDirectiveGeneration = -1;
                        if (accepted != null) {
                            status = wireState(accepted.state);
                            lastHeartbeatAt = adapter.elapsedRealtimeMs();
                            lastHeartbeatState = status;
                        } else {
                            status = pendingCommand.targetState.name();
                            lastHeartbeatAt = adapter.elapsedRealtimeMs();
                            lastHeartbeatState = status;
                        }
                        publishState();
                    }
                });
        if (request != null) {
            presentedAckPending = true;
        } else {
            ready = false;
            converging = false;
            heartbeatStarted = false;
            errorCode = "ACK_NOT_SENT";
            status = "ERROR";
        }
    }

    static JSONObject ackFields(
            String partyId, PartyPlaybackAdapter.ScheduledCommand command,
            String stage, boolean success,
            PartyPlaybackAdapter.Observation observation) {
        JSONObject fields = new JSONObject()
                .put("partyId", partyId)
                .put("directiveId", command.directiveId)
                .put("stateVersion", command.stateVersion)
                .put("commandSeq", command.commandSeq)
                .put("stage", stage)
                .put("success", success);
        if (("ARMED".equals(stage) || "PRESENTED".equals(stage))
                && observation != null) {
            fields.put("sampleMonoMs", observation.sampleMonoMs)
                    .put("positionMs", Math.max(0, observation.positionMs))
                    .put("state", wireState(observation.state));
        }
        return fields;
    }

    private void failPendingDirective() {
        boolean wasActive = heartbeatStarted;
        ready = wasActive;
        converging = false;
        heartbeatStarted = wasActive;
        pendingPresentedDirective = null;
        pendingPresentedDirectiveGeneration = -1;
        pendingDirectiveCorrection = false;
        directiveExecutionCompleted = false;
        presentedAckPending = false;
        armedAckPending = false;
        directiveStage = DirectiveStage.NONE;
        Iterator<PendingRequest> iterator = queue.iterator();
        while (iterator.hasNext()) {
            if ("ACK".equals(iterator.next().type)) iterator.remove();
        }
        if (wasActive) status = "LATE";
        publishState();
    }

    private void submitAction(String action, Long targetPositionMs) {
        PendingAction actionRequest = new PendingAction(
                action, targetPositionMs, ++pendingActionGeneration, false);
        if (pendingAction != null || hasPendingRequest("SNAPSHOT")) {
            pendingAction = actionRequest;
            if (hasConfidentClock(adapter.elapsedRealtimeMs())) {
                requestSnapshotForPendingAction();
            } else {
                beginClockCalibration();
            }
            return;
        }
        if (!hasConfidentClock(adapter.elapsedRealtimeMs())) {
            pendingAction = actionRequest;
            beginClockCalibration();
            return;
        }
        pendingAction = null;
        sendAction(actionRequest);
    }

    private void sendAction(PendingAction actionRequest) {
        JSONObject fields = new JSONObject()
                .put("partyId", partyId)
                .put("stateVersion", stateVersion)
                .put("action", actionRequest.action);
        if ("JUMP".equals(actionRequest.action)
                && actionRequest.targetPositionMs != null) {
            fields.put("targetPositionMs", actionRequest.targetPositionMs);
        }
        PendingRequest request = enqueueRequest("ACTION", fields, true, null);
        if (request != null && actionRequest.clockBarrierRetry) {
            clockBarrierRetryRequestId = request.requestId;
        }
    }

    private void requestSnapshotForPendingAction() {
        final PendingAction actionRequest = pendingAction;
        if (actionRequest == null || hasPendingRequest("SNAPSHOT")) return;
        enqueueRequest("SNAPSHOT",
                new JSONObject().put("partyId", partyId), true,
                new ReplyHandler() {
                    @Override public void onReply(JSONObject reply) {
                        if (!"SNAPSHOT".equals(
                                reply.optString("type", ""))) {
                            return;
                        }
                        if (pendingAction == null
                                || pendingAction.generation
                                != actionRequest.generation) {
                            return;
                        }
                        PendingAction current = pendingAction;
                        pendingAction = null;
                        sendAction(current);
                    }
                });
    }

    private void sendHeartbeat(PartyPlaybackAdapter.Observation observation,
                               String forcedState, boolean immediate) {
        if (partyId == null || !sessionAuthorized) return;
        if (!canSendMeasurementHeartbeat(
                heartbeatStarted, ready, converging, forcedState)) return;
        if (observation == null) observation = adapter.observe();
        if (observation == null) return;
        String heartbeatState = forcedState == null
                ? heartbeatState(observation.state, expectedPlaybackState,
                        playbackHeld)
                : forcedState;
        long now = adapter.elapsedRealtimeMs();
        long interval = heartbeatIntervalMs(
                heartbeatState, converging);
        boolean stateChanged = lastHeartbeatState == null
                || !lastHeartbeatState.equals(heartbeatState);
        if (!(immediate && stateChanged)
                && !heartbeatDue(lastHeartbeatAt, now, interval)) {
            return;
        }
        discardPendingHeartbeats();
        lastHeartbeatAt = now;
        lastHeartbeatState = heartbeatState;
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

    static boolean heartbeatDue(long lastSentMonoMs, long nowMonoMs,
                                long intervalMs) {
        return lastSentMonoMs <= 0
                || nowMonoMs - lastSentMonoMs >= intervalMs;
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
                                           boolean readyTransitionSent,
                                           boolean directivePending,
                                           boolean converging,
                                           boolean heartbeatStarted) {
        return preparationLoaded && !ready && !readyRequestPending
                && !readyTransitionSent && !directivePending
                && !converging && !heartbeatStarted;
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

    static boolean canSendMeasurementHeartbeat(boolean heartbeatStarted,
                                               boolean ready,
                                               boolean converging,
                                               String forcedState) {
        if (!canSendHeartbeat(heartbeatStarted, forcedState)) return false;
        if ("STOPPED".equals(forcedState)) return true;
        return ready && !converging;
    }

    static String restoredClockStatus(boolean converging,
                                      PartyPlaybackAdapter.Observation observation) {
        if (converging) return "CONVERGING";
        return observation == null ? "READY" : wireState(observation.state);
    }

    static boolean canPublishStablePlaybackState(boolean converging,
                                                 boolean directivePending) {
        return !converging && !directivePending;
    }

    static boolean canExecuteDirective(
            boolean correction,
            PartyPlaybackAdapter.PlaybackState targetState,
            boolean preparationLoaded,
            boolean readyTransitionSent,
            boolean heartbeatStarted,
            boolean activationReady) {
        if (!preparationLoaded) return false;
        if (correction) {
            return heartbeatStarted
                    || (readyTransitionSent && activationReady);
        }
        if (targetState == PartyPlaybackAdapter.PlaybackState.PAUSED) {
            return true;
        }
        return heartbeatStarted;
    }

    static boolean canAcceptDirective(boolean directiveSettling) {
        return !directiveSettling;
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
            handlePrepareAccepted(envelope);
        } else if ("PREPARATION_RESULT".equals(type)) {
            handlePreparationResult(envelope);
        } else if ("READY_ACK".equals(type)
                && inFlight != null
                && "READY".equals(inFlight.type)
                && inFlight.requestId.equals(requestId)) {
            handleLegacyReadyAck(envelope);
        } else if ("CORRECTION".equals(type)) {
            handleDirective(envelope, true);
        } else if ("COMMAND".equals(type)) {
            handleDirective(envelope, false);
        } else if ("REVEAL".equals(type)) {
            handleReveal(envelope);
        } else if ("PARTICIPANT_STATE".equals(type)) {
            handleParticipantState(envelope);
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
            invitedMediaFileId = envelope.has("mediaFileId")
                    ? Integer.valueOf(envelope.optInt("mediaFileId"))
                    : null;
            if (invitedPartyId == null || invitedMediaFileId == null) {
                invitedPartyId = null;
                invitedMediaFileId = null;
                errorCode = "INVALID_SERVER_MESSAGE";
                status = "ERROR";
            } else {
                status = "INVITED";
                errorCode = null;
            }
            publishState();
        } else if ("LEFT".equals(type) || "ENDED".equals(type)) {
            clearMembership(type);
        }

        if (inFlight != null && completesRequest(
                inFlight.type, inFlight.requestId, type, requestId)) {
            PendingRequest completed = inFlight;
            clearInFlight();
            if ("ACTION".equals(completed.type)
                    && completed.requestId.equals(clockBarrierRetryRequestId)
                    && !"ERROR".equals(type)) {
                clockBarrierRetryRequestId = null;
            }
            if (completed.handler != null) completed.handler.onReply(envelope);
            sendNext();
        }
        if ("CLOCK_MODEL".equals(type)) requestClock();
    }

    private void handleParticipantState(JSONObject message) {
        if (!partyIdEquals(message)) return;
        String changedParticipantId =
                message.optString("participantId", null);
        if (changedParticipantId == null
                || changedParticipantId.isEmpty()) {
            return;
        }
        participantStates.put(changedParticipantId,
                new JSONObject(message.toString()));
        publishState();
    }

    private void handleError(JSONObject envelope) {
        errorCode = envelope.optString("code", "UNKNOWN");
        if ("INVALID_REQUEST".equals(errorCode) && inFlight != null) {
            log.logWarning("Party INVALID_REQUEST for outbound type="
                    + inFlight.type + " fields="
                    + diagnosticFields(inFlight.serialized));
        }
        if (isTransientNotPrepared(errorCode, preparationPending, status)) {
            errorCode = null;
            status = "PREPARING";
            publishState();
            return;
        }
        if (isNonFatalTelemetryError(
                errorCode, inFlight == null ? null : inFlight.type)) {
            errorCode = null;
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
            if (inFlight != null && "ACTION".equals(inFlight.type)
                    && retainActionAfterClockBarrier()) {
                errorCode = null;
                beginClockCalibration();
            } else {
                status = "ERROR";
            }
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
        if (clockRefreshDue(
                lastClockModelAtMs, now, clockSamples,
                hasPendingRequest("CLOCK") || hasPendingRequest("CLOCK_REPLY"))) {
            beginClockCalibration();
        }
        if (preparationLoaded && !ready) {
            if (clockSamples < CLOCK_CALIBRATION_SAMPLE_COUNT) requestClock();
            else evaluateReadiness();
        }
        if (!heartbeatStarted || !ready || converging) return;
        if (clockModel == null || !clockModel.isConfident(now)) {
            beginClockCalibration();
            return;
        }
        PartyPlaybackAdapter.Observation observation = adapter.observe();
        if (observation == null) return;
        String observedState = heartbeatState(
                observation.state, expectedPlaybackState, playbackHeld);
        if (!"LATE".equals(status)) {
            if ("BUFFERING".equals(observedState)) status = "BUFFERING";
            else if (!converging && ready) status = observedState;
        }
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
        lastHeartbeatAt = 0;
        lastHeartbeatState = null;
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
        stateVersion = 0;
        commandSeq = 0;
        joinCode = null;
        joinCodeExpiresAtServerMonoMs = 0;
        invitedPartyId = null;
        invitedMediaFileId = null;
        connectedSessionHandles.clear();
        Iterator<String> participantIds = participantStates.keys();
        while (participantIds.hasNext()) {
            participantIds.next();
            participantIds.remove();
        }
        ready = false;
        locallyStopped = false;
        converging = false;
        lastHeartbeatAt = 0;
        lastHeartbeatState = null;
        preparationLoaded = false;
        preparationPending = false;
        preparationId = null;
        preparationPlaybackGeneration = -1;
        preparationWatchCode = -1;
        readyRequestPending = false;
        readyTransitionSent = false;
        heartbeatStarted = false;
        expectedPlaybackState = PartyPlaybackAdapter.PlaybackState.UNAVAILABLE;
        executedDirectives.clear();
        mediaWindow.clear();
        resetClock();
        lastClockModelAtMs = -1;
        pendingClockDirective = null;
        pendingClockCorrection = false;
        pendingAction = null;
        pendingActionGeneration = 0;
        clockBarrierRetryRequestId = null;
        status = terminalStatus;
        publishState();
    }

    static long membershipScopedCounter(String currentPartyId,
                                        String incomingPartyId,
                                        long currentValue) {
        return currentPartyId != null
                && currentPartyId.equals(incomingPartyId)
                ? currentValue : 0;
    }

    private PendingRequest enqueueRequest(String type, JSONObject fields,
                                          boolean retryAllowed,
                                          ReplyHandler handler) {
        if (!supportsRequiredGuarantees() || queue.size() >= 64) return null;
        if (!"HELLO".equals(type)
                && (serverEpoch == null || sessionToken == null)) return null;
        JSONObject envelope = requestEnvelope(type, requestId(), ++requestSeq,
                serverEpoch, sessionToken, fields);
        try {
            PartyProtocol.validateEnvelope(envelope);
        } catch (PartyProtocolException e) {
            errorCode = "INVALID_REQUEST";
            status = "ERROR";
            publishState();
            return null;
        }
        PendingRequest pending = new PendingRequest(
                type, envelope.getString("requestId"), envelope.toString(),
                retryAllowed, handler);
        queue.add(pending);
        sendNext();
        return pending;
    }

    static String diagnosticFields(String serializedEnvelope) {
        try {
            JSONObject envelope = new JSONObject(serializedEnvelope);
            envelope.remove("schemaVersion");
            envelope.remove("type");
            envelope.remove("requestId");
            envelope.remove("requestSeq");
            envelope.remove("serverEpoch");
            envelope.remove("sessionToken");
            envelope.remove("recoveryToken");
            return envelope.toString();
        } catch (RuntimeException e) {
            return "<unparseable>";
        }
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
        directiveExecutionInProgress = false;
        if (scheduledDirective != null) scheduledDirective.cancel(false);
        scheduledDirective = null;
        pendingPresentedDirective = null;
        pendingPresentedDirectiveGeneration = -1;
        pendingDirectiveCorrection = false;
        directiveExecutionCompleted = false;
        presentedAckPending = false;
        armedAckPending = false;
        directiveStage = DirectiveStage.NONE;
        adapter.cancelScheduledAction();
    }

    static PartyPlaybackAdapter.CorrectionKind correctionKind(
            JSONObject directive, boolean correction) {
        if (!correction) return PartyPlaybackAdapter.CorrectionKind.NONE;
        String kind = directive.optString("kind", "");
        if ("SOFT".equals(kind)) return PartyPlaybackAdapter.CorrectionKind.SOFT;
        if ("HARD".equals(kind)) return PartyPlaybackAdapter.CorrectionKind.HARD;
        return null;
    }

    static boolean hasRequiredDirectiveFields(JSONObject directive) {
        if (directive == null
                || !directive.has("stateVersion")
                || !directive.has("commandSeq")
                || !directive.has("directiveId")
                || !directive.has("targetPositionMs")
                || !directive.has("targetState")
                || !directive.has("targetRate")
                || !directive.has("executeAtServerMonoMs")
                || directive.optString("directiveId", "").isEmpty()) {
            return false;
        }
        boolean hasBarrierFields = directive.has("phase")
                || directive.has("barrier")
                || directive.has("visibleAtServerMonoMs")
                || directive.has("predictedPresentationLatencyMs")
                || directive.has("presentationUncertaintyMs");
        if (!hasBarrierFields) return true;
        return directive.optBoolean("barrier", false)
                && "PREPARE".equals(directive.optString("phase", ""))
                && directive.has("visibleAtServerMonoMs")
                && directive.has("predictedPresentationLatencyMs")
                && directive.has("presentationUncertaintyMs");
    }

    private void resetClock() {
        clockSamples = 0;
        clockModel = null;
        acceptedClockReplies.clear();
    }

    private void beginClockCalibration() {
        if (clockModel != null || clockSamples >= CLOCK_CALIBRATION_SAMPLE_COUNT) {
            resetClock();
        }
        if (!ready) status = "CLOCKING";
        requestClock();
        publishState();
    }

    private boolean hasConfidentClock(long now) {
        return clockModel != null && clockModel.isConfident(now);
    }

    static boolean clockRefreshDue(long lastClockModelAtMs, long now,
                                   int clockSamples,
                                   boolean clockRequestPending) {
        return lastClockModelAtMs >= 0
                && now - lastClockModelAtMs >= CLOCK_REFRESH_INTERVAL_MS
                && clockSamples >= CLOCK_CALIBRATION_SAMPLE_COUNT
                && !clockRequestPending;
    }

    private boolean retainActionAfterClockBarrier() {
        if (inFlight == null || !"ACTION".equals(inFlight.type)) return true;
        boolean alreadyRetried = inFlight.requestId.equals(
                clockBarrierRetryRequestId);
        if (alreadyRetried) {
            clockBarrierRetryRequestId = null;
            pendingAction = null;
            status = "CLOCK_UNCERTAIN";
            return false;
        }
        try {
            JSONObject actionEnvelope = new JSONObject(inFlight.serialized);
            String action = actionEnvelope.optString("action", null);
            if (action == null) return false;
            Long targetPositionMs = actionEnvelope.has("targetPositionMs")
                    ? actionEnvelope.optLong("targetPositionMs") : null;
            pendingAction = new PendingAction(
                    action, targetPositionMs, ++pendingActionGeneration, true);
            return true;
        } catch (RuntimeException e) {
            log.logWarning("Could not retain ACTION after CLOCK_UNCERTAIN");
            return false;
        }
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

    static boolean isPresentedObservationFor(
            PartyPlaybackAdapter.ScheduledCommand command,
            PartyPlaybackAdapter.Observation observation) {
        return command != null
                && isActivationReady(observation)
                && observation.state == command.targetState;
    }

    static boolean isNonFatalTelemetryError(
            String errorCode, String requestType) {
        return "STALE_SAMPLE".equals(errorCode)
                && "HEARTBEAT".equals(requestType);
    }
}
