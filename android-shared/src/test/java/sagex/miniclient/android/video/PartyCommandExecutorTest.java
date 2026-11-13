package sagex.miniclient.android.video;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import sagex.miniclient.party.PartyPlaybackAdapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PartyCommandExecutorTest
{
    @Test
    public void acknowledgementWaitsForOrderedUiExecution()
    {
        final ArrayDeque<Runnable> uiQueue = new ArrayDeque<Runnable>();
        final List<String> events = new ArrayList<String>();
        uiQueue.add(new Runnable()
        {
            @Override
            public void run()
            {
                PartyCommandExecutor.execute(new PartyCommandExecutor.Steps()
                {
                    @Override public boolean seek()
                    {
                        events.add("seek");
                        return true;
                    }

                    @Override public void applyState()
                    {
                        events.add("state");
                    }

                    @Override public void applyHold()
                    {
                        events.add("hold");
                    }
                }, new PartyPlaybackAdapter.ExecutionCallback()
                {
                    @Override public void onComplete(boolean success)
                    {
                        assertTrue(success);
                        events.add("ack");
                    }
                }, null);
            }
        });

        assertTrue(events.isEmpty());
        uiQueue.remove().run();
        assertEquals(Arrays.asList("seek", "state", "hold", "ack"), events);
    }

    @Test
    public void pushModeUsesServerOwnedPartySeek()
    {
        assertFalse(BaseMediaPlayerImpl.shouldSeekLocallyForParty(true));
        assertTrue(BaseMediaPlayerImpl.shouldSeekLocallyForParty(false));
    }

    @Test
    public void pausedFramePrimeRequiresPendingPartyPresentation()
    {
        assertTrue(PartyCommandExecutor.shouldPrimePausedFrame(true, true, 1));
        assertFalse(PartyCommandExecutor.shouldPrimePausedFrame(false, true, 1));
        assertFalse(PartyCommandExecutor.shouldPrimePausedFrame(true, false, 1));
        assertFalse(PartyCommandExecutor.shouldPrimePausedFrame(true, true, 0));
    }

    @Test
    public void heldFrameCannotSettleBeforePausedPrime()
    {
        assertFalse(PartyCommandExecutor.shouldAcceptUnprimedFrame(
                true, true));
        assertTrue(PartyCommandExecutor.shouldAcceptUnprimedFrame(
                true, false));
        assertFalse(PartyCommandExecutor.shouldAcceptUnprimedFrame(
                false, false));
    }

    @Test
    public void hardPausedCorrectionReusesExactPresentedTarget()
    {
        PartyPlaybackAdapter.ScheduledCommand command =
                new PartyPlaybackAdapter.ScheduledCommand(
                        "directive", 1, 1, "CORRECTION",
                        PartyPlaybackAdapter.CorrectionKind.HARD,
                        67_811,
                        PartyPlaybackAdapter.PlaybackState.PAUSED,
                        1.0, 1_000);
        PartyPlaybackAdapter.Observation observation =
                new PartyPlaybackAdapter.Observation(
                        900, 67_811, 0, 120_000,
                        PartyPlaybackAdapter.PlaybackState.PAUSED,
                        true, true, 50);

        assertTrue(PartyCommandExecutor.shouldReusePresentedTarget(
                command, observation));
    }

    @Test
    public void presentedTargetReuseDoesNotApplyToBarrierOrPlayingMedia()
    {
        PartyPlaybackAdapter.Observation paused =
                new PartyPlaybackAdapter.Observation(
                        900, 67_811, 0, 120_000,
                        PartyPlaybackAdapter.PlaybackState.PAUSED,
                        true, true, 50);
        PartyPlaybackAdapter.ScheduledCommand barrier =
                new PartyPlaybackAdapter.ScheduledCommand(
                        "directive", 1, 1, "CORRECTION",
                        PartyPlaybackAdapter.CorrectionKind.HARD,
                        67_811,
                        PartyPlaybackAdapter.PlaybackState.PAUSED,
                        1.0, 1_000, true, "PREPARE", 1_200, 0, 0);
        PartyPlaybackAdapter.ScheduledCommand playing =
                new PartyPlaybackAdapter.ScheduledCommand(
                        "directive", 1, 1, "CORRECTION",
                        PartyPlaybackAdapter.CorrectionKind.HARD,
                        67_811,
                        PartyPlaybackAdapter.PlaybackState.PLAYING,
                        1.0, 1_000);

        assertFalse(PartyCommandExecutor.shouldReusePresentedTarget(
                barrier, paused));
        assertFalse(PartyCommandExecutor.shouldReusePresentedTarget(
                playing, paused));
    }
}
