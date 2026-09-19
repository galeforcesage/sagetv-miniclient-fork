package sagex.miniclient.android.display;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Path B (surface move) host: a full-bleed {@link Presentation} shown on an
 * external/extended display whose single {@link SurfaceView} becomes the video
 * decoder's output surface. The SageTV UI activity, its
 * {@link sagex.miniclient.MiniClientConnection} and the running player all stay
 * put on the phone; only the decoded video is re-targeted here via
 * {@code BaseMediaPlayerImpl.reattachVideoSurface(SurfaceHolder)}. No activity
 * relaunch, no session teardown, no reconnect &mdash; so playback position and
 * seek state survive the move.
 *
 * <p>The surface is only valid between {@code surfaceCreated} and
 * {@code surfaceDestroyed}; the owner ({@code ExternalVideoSurfaceController})
 * re-targets the player only after {@link Callback#onExternalSurfaceReady} and
 * moves output back to the phone before this presentation is dismissed.</p>
 */
public final class ExternalVideoPresentation extends Presentation
{
    private static final Logger log = LoggerFactory.getLogger(ExternalVideoPresentation.class);

    /** Lifecycle signals for the hosted video surface. */
    public interface Callback
    {
        void onExternalSurfaceReady(SurfaceHolder holder);

        void onExternalSurfaceLost(SurfaceHolder holder);
    }

    private final Callback callback;
    private SurfaceView surfaceView;
    private FrameLayout subtitleContainer;

    public ExternalVideoPresentation(Context outerContext, Display display, Callback callback)
    {
        super(outerContext, display);
        this.callback = callback;
    }

    /** @return the hosted surface holder, or {@code null} before onCreate. */
    public SurfaceHolder getSurfaceHolder()
    {
        return (surfaceView != null) ? surfaceView.getHolder() : null;
    }

    /**
     * @return a full-bleed overlay container above the video surface into which
     * the player can attach a caption/subtitle view so CC follows the video onto
     * the TV. {@code null} before onCreate.
     */
    public FrameLayout getSubtitleContainer()
    {
        return subtitleContainer;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        try
        {
            FrameLayout root = new FrameLayout(getContext());
            root.setBackgroundColor(Color.BLACK);

            surfaceView = new SurfaceView(getContext());
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER);
            root.addView(surfaceView, lp);

            // Caption overlay above the video so CC can be rendered on the TV.
            subtitleContainer = new FrameLayout(getContext());
            root.addView(subtitleContainer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            setContentView(root);

            surfaceView.getHolder().addCallback(new SurfaceHolder.Callback()
            {
                @Override
                public void surfaceCreated(SurfaceHolder holder)
                {
                    log.info("External video presentation surface created");
                    if (callback != null) callback.onExternalSurfaceReady(holder);
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height)
                {
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder)
                {
                    log.info("External video presentation surface destroyed");
                    if (callback != null) callback.onExternalSurfaceLost(holder);
                }
            });
        }
        catch (Throwable t)
        {
            log.warn("ExternalVideoPresentation onCreate failed", t);
        }
    }
}
