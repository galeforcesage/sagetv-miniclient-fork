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
