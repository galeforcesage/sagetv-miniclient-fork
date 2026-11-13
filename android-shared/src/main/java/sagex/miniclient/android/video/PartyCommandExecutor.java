package sagex.miniclient.android.video;

import sagex.miniclient.party.PartyPlaybackAdapter;

public final class PartyCommandExecutor
{
    public interface Steps
    {
        boolean seek();
        void applyState();
        void applyHold();
    }

    public interface FailureHandler
    {
        void onFailure(Throwable failure);
    }

    private PartyCommandExecutor()
    {
    }

    public static boolean shouldPrimePausedFrame(boolean presentationPending,
                                                 boolean partyHold,
                                                 int amount)
    {
        return presentationPending && partyHold && amount > 0;
    }

    public static boolean shouldAcceptUnprimedFrame(
            boolean presentationPending, boolean partyHold)
    {
        return presentationPending && !partyHold;
    }

    public static boolean shouldReusePresentedTarget(
            PartyPlaybackAdapter.ScheduledCommand command,
            PartyPlaybackAdapter.Observation observation)
    {
        if (command == null || observation == null
                || !"CORRECTION".equals(command.action)
                || command.correctionKind
                != PartyPlaybackAdapter.CorrectionKind.HARD
                || command.barrier
                || command.targetState
                != PartyPlaybackAdapter.PlaybackState.PAUSED
                || observation.state
                != PartyPlaybackAdapter.PlaybackState.PAUSED
                || !observation.prepared
                || !observation.activated)
        {
            return false;
        }
        long toleranceMs = Math.max(250L,
                observation.precisionMs
                        + command.presentationUncertaintyMs);
        return Math.abs(observation.positionMs - command.targetPositionMs)
                <= toleranceMs;
    }

    public static void execute(Steps steps,
            PartyPlaybackAdapter.ExecutionCallback callback,
            FailureHandler failureHandler)
    {
        boolean success = false;
        try
        {
            if (steps != null && steps.seek())
            {
                steps.applyState();
                steps.applyHold();
                success = true;
            }
        }
        catch (Throwable failure)
        {
            if (failureHandler != null) failureHandler.onFailure(failure);
        }
        finally
        {
            if (callback != null) callback.onComplete(success);
        }
    }
}
