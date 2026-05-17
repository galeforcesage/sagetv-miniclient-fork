package sagex.miniclient.android.video;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import androidx.preference.PreferenceManager;

import sagex.miniclient.uibridge.Rectangle;
import sagex.miniclient.util.VideoInfo;

/**
 * Client-side Fit/Fill/Zoom aspect handling for non-Leanback (phone/tablet/foldable)
 * devices. On Leanback (Android TV) this class is a no-op so the existing server-driven
 * AR pipeline (AR_TOGGLE -> Source/Stretch/Zoom) keeps full control.
 *
 * Why this exists: on a non-16:9 panel (e.g. Galaxy Fold outer screen ~21.6:9) the
 * server tells the client to render video into the full landscape rectangle. The
 * client surface is a plain SurfaceView, so the video stretches to fill -> faces
 * look fat. We intercept the rectangle the server gave us, look at the real stream
 * aspect from onVideoSizeChanged(), and resize the video FrameLayout to a
 * letterboxed/pillarboxed/zoomed sub-rect.
 *
 * Has no effect on menus: this only transforms the video FrameLayout in
 * BaseMediaPlayerImpl#updatePlayerView. The SageTV UI is rendered on a separate
 * GL/GDX canvas.
 */
public final class NonLeanbackAspect
{
    public static final String PREF_KEY = "non_leanback_aspect_mode";

    public enum Mode
    {
        FIT,   // letterbox/pillarbox to preserve source aspect (default; people look correct)
        FILL,  // stretch to fill the whole rectangle (legacy behavior; can distort)
        ZOOM;  // crop to fill the whole rectangle preserving aspect (loses edges)

        public Mode next()
        {
            switch (this)
            {
                case FIT:  return FILL;
                case FILL: return ZOOM;
                case ZOOM: return FIT;
                default:   return FIT;
            }
        }

        public String label()
        {
            switch (this)
            {
                case FIT:  return "Fit";
                case FILL: return "Fill";
                case ZOOM: return "Zoom";
                default:   return "Fit";
            }
        }
    }

    private static Boolean cachedIsLeanback = null;

    private NonLeanbackAspect() {}

    public static boolean isLeanback(Context ctx)
    {
        if (cachedIsLeanback != null) return cachedIsLeanback;
        if (ctx == null) return false;
        cachedIsLeanback = ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
        return cachedIsLeanback;
    }

    public static Mode getMode(Context ctx)
    {
        if (ctx == null) return Mode.FIT;
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(ctx);
        String v = sp.getString(PREF_KEY, Mode.FIT.name());
        try { return Mode.valueOf(v); } catch (Exception e) { return Mode.FIT; }
    }

    public static void setMode(Context ctx, Mode mode)
    {
        if (ctx == null || mode == null) return;
        PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit().putString(PREF_KEY, mode.name()).apply();
    }

    public static Mode cycle(Context ctx)
    {
        Mode next = getMode(ctx).next();
        setMode(ctx, next);
        return next;
    }

    /**
     * If running on a non-Leanback device and we know the stream aspect, transform the
     * destination rectangle the server gave us so the video frame lands at the right
     * size for the user's chosen Mode. Returns the original rect unchanged on Leanback
     * or when we don't yet have a valid stream aspect.
     */
    public static Rectangle apply(Context ctx, Rectangle serverRect, VideoInfo videoInfo)
    {
        if (serverRect == null || serverRect.width <= 0 || serverRect.height <= 0) return serverRect;
        if (videoInfo == null) return serverRect;
        if (isLeanback(ctx)) return serverRect;

        float vidAR = videoInfo.aspectRatio;
        if (vidAR <= 0f && videoInfo.size != null && videoInfo.size.height > 0)
        {
            vidAR = videoInfo.size.width / videoInfo.size.height;
        }
        if (vidAR <= 0f) return serverRect; // no stream aspect known yet

        Mode mode = getMode(ctx);
        if (mode == Mode.FILL) return serverRect; // legacy behavior

        int rw = serverRect.width;
        int rh = serverRect.height;
        float rectAR = (float) rw / (float) rh;

        int newW, newH;
        if (mode == Mode.FIT)
        {
            // Letterbox or pillarbox so video AR == stream AR and fully contained in rect.
            if (vidAR > rectAR)
            {
                newW = rw;
                newH = Math.round(rw / vidAR);
            }
            else
            {
                newH = rh;
                newW = Math.round(rh * vidAR);
            }
        }
        else // ZOOM
        {
            // Crop: video covers the entire rect, may exceed it on one axis.
            if (vidAR > rectAR)
            {
                newH = rh;
                newW = Math.round(rh * vidAR);
            }
            else
            {
                newW = rw;
                newH = Math.round(rw / vidAR);
            }
        }

        int newX = serverRect.x + (rw - newW) / 2;
        int newY = serverRect.y + (rh - newH) / 2;
        return new Rectangle(newX, newY, newW, newH);
    }
}
