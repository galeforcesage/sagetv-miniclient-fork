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
import sagex.miniclient.SageCommand;
import sagex.miniclient.android.MiniclientApplication;
import sagex.miniclient.uibridge.EventRouter;

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
                sendChar(client, conn, text.charAt(i));
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
                sendChar(client, conn, text.charAt(i));
            }
            composingSentCount = 0;
            return true;
        }

        // Route digit characters as Num SageCommands (Command_Num_0..9) instead
        // of literal key chars. The server performs T9 multi-tap letter cycling
        // (e.g. "2" twice -> "B") only when it receives Num events; a literal
        // digit just inserts the number. This matches the hardware/remote key
        // path (DefaultKeyMap maps KEYCODE_0..9 -> Num) and legacy google/SageTV
        // behavior, so the soft-keyboard (OpenGL IME) path multi-taps too.
        private void sendChar(MiniClient client, MiniClientConnection conn, char c) {
            if (c >= '0' && c <= '9') {
                EventRouter.postCommand(client, SageCommand.valueOf("NUM" + c));
            } else {
                conn.postKeyEvent(c, 0, c);
            }
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
