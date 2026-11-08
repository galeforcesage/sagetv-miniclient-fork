package sagex.miniclient.android.video;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import sagex.miniclient.android.ui.AndroidUIController;
import sagex.miniclient.party.PartyPlaybackAdapter;

final class PartyFrameCover
{
    private final AndroidUIController context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ImageView coverView;
    private Bitmap coverBitmap;

    PartyFrameCover(AndroidUIController context)
    {
        this.context = context;
    }

    static boolean isSupported()
    {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
    }

    void capture(final PartyPlaybackAdapter.ExecutionCallback callback)
    {
        context.runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                final View videoView = context.getVideoView();
                if (!isSupported() || videoView == null
                        || videoView.getWidth() <= 0
                        || videoView.getHeight() <= 0
                        || !(videoView.getParent() instanceof FrameLayout))
                {
                    complete(callback, false);
                    return;
                }
                final Bitmap bitmap = Bitmap.createBitmap(
                        videoView.getWidth(), videoView.getHeight(),
                        Bitmap.Config.ARGB_8888);
                if (videoView instanceof SurfaceView)
                {
                    PixelCopy.request((SurfaceView) videoView, bitmap,
                            new PixelCopy.OnPixelCopyFinishedListener()
                            {
                                @Override
                                public void onPixelCopyFinished(int result)
                                {
                                    if (result != PixelCopy.SUCCESS)
                                    {
                                        bitmap.recycle();
                                        complete(callback, false);
                                        return;
                                    }
                                    installCover(videoView, bitmap, callback);
                                }
                            }, mainHandler);
                }
                else
                {
                    videoView.draw(new Canvas(bitmap));
                    installCover(videoView, bitmap, callback);
                }
            }
        });
    }

    void reveal(final PartyPlaybackAdapter.ExecutionCallback callback)
    {
        context.runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                clearCover();
                complete(callback, true);
            }
        });
    }

    void cancel()
    {
        context.runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                clearCover();
            }
        });
    }

    private void installCover(View videoView, Bitmap bitmap,
                              PartyPlaybackAdapter.ExecutionCallback callback)
    {
        clearCover();
        FrameLayout parent = (FrameLayout) videoView.getParent();
        ImageView image = new ImageView(context.getContext());
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        image.setImageBitmap(bitmap);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                videoView.getWidth(), videoView.getHeight());
        params.leftMargin = videoView.getLeft();
        params.topMargin = videoView.getTop();
        parent.addView(image, params);
        image.bringToFront();
        coverBitmap = bitmap;
        coverView = image;
        complete(callback, true);
    }

    private void clearCover()
    {
        if (coverView != null)
        {
            ViewParentCompat.remove(coverView);
            coverView.setImageDrawable(null);
            coverView = null;
        }
        if (coverBitmap != null)
        {
            coverBitmap.recycle();
            coverBitmap = null;
        }
    }

    private static void complete(
            PartyPlaybackAdapter.ExecutionCallback callback, boolean success)
    {
        if (callback != null) callback.onComplete(success);
    }

    private static final class ViewParentCompat
    {
        static void remove(View view)
        {
            if (view.getParent() instanceof ViewGroup)
            {
                ((ViewGroup) view.getParent()).removeView(view);
            }
        }
    }
}
