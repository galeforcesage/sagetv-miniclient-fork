package sagex.miniclient.android.opengl;

import android.content.Context;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.text.InputType;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import sagex.miniclient.MiniClient;
import sagex.miniclient.MiniClientConnection;
import sagex.miniclient.android.MiniclientApplication;

public class OpenGLSurfaceView extends GLSurfaceView {
    public OpenGLSurfaceView(Context context, OpenGLRenderer renderer) {
        super(context);

        renderer.setView(this);

        setEGLContextClientVersion(2);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        setRenderer(renderer);
        setZOrderOnTop(true);
        setZOrderMediaOverlay(true);
        setPreserveEGLContextOnPause(true);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        return new SageInputConnection(this);
    }

    private static class SageInputConnection extends BaseInputConnection {
        private int composingSentCount = 0;

        public SageInputConnection(OpenGLSurfaceView view) {
            super(view, false);
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            if (text == null) return true;
            MiniClient client = MiniclientApplication.get().getClient();
            if (client == null || client.getCurrentConnection() == null) return true;
            MiniClientConnection conn = client.getCurrentConnection();
            // Only send characters we haven't sent yet
            for (int i = composingSentCount; i < text.length(); i++) {
                char c = text.charAt(i);
                conn.postKeyEvent(c, 0, c);
            }
            // If composing text got shorter, send backspaces for removed chars
            for (int i = 0; i < composingSentCount - text.length(); i++) {
                conn.postKeyEvent(8, 0, (char) 8);
            }
            composingSentCount = text.length();
            return true;
        }

        @Override
        public boolean finishComposingText() {
            composingSentCount = 0;
            return true;
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            if (text == null || text.length() == 0) {
                composingSentCount = 0;
                return true;
            }
            MiniClient client = MiniclientApplication.get().getClient();
            if (client == null || client.getCurrentConnection() == null) return true;
            MiniClientConnection conn = client.getCurrentConnection();
            // Send only chars not already sent via composing
            for (int i = composingSentCount; i < text.length(); i++) {
                char c = text.charAt(i);
                conn.postKeyEvent(c, 0, c);
            }
            composingSentCount = 0;
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            MiniClient client = MiniclientApplication.get().getClient();
            if (client == null || client.getCurrentConnection() == null) return true;
            MiniClientConnection conn = client.getCurrentConnection();
            for (int i = 0; i < beforeLength; i++) {
                conn.postKeyEvent(8, 0, (char) 8);
            }
            return true;
        }
    }
}
