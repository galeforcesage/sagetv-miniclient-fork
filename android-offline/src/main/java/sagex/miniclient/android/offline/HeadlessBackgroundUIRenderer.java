package sagex.miniclient.android.offline;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;

import sagex.miniclient.MenuHint;
import sagex.miniclient.MiniClientConnection;
import sagex.miniclient.MiniPlayerPlugin;
import sagex.miniclient.media.SubtitleTrack;
import sagex.miniclient.uibridge.Dimension;
import sagex.miniclient.uibridge.ImageHolder;
import sagex.miniclient.uibridge.Rectangle;
import sagex.miniclient.uibridge.Scale;
import sagex.miniclient.uibridge.Texture;
import sagex.miniclient.uibridge.UIRenderer;

/**
 * Minimal no-op renderer used only for background command-channel refresh.
 * It keeps MiniClientConnection construction valid without launching UI.
 */
public class HeadlessBackgroundUIRenderer implements UIRenderer<Texture> {
    private static final Dimension DEFAULT_SIZE = new Dimension(1920, 1080);
    private static final Scale DEFAULT_SCALE = new Scale(1f, 1f);
    private static final MiniPlayerPlugin NOOP_PLAYER = new NoopMiniPlayer();

    @Override public int getState() { return STATE_MENU; }
    @Override public void GFXCMD_INIT() { }
    @Override public void GFXCMD_DEINIT() { }
    @Override public void close() { }
    @Override public void refresh() { }
    @Override public void hideCursor() { }
    @Override public void showBusyCursor() { }
    @Override public void drawRect(int x, int y, int width, int height, int thickness, int argbTL, int argbTR, int argbBR, int argbBL) { }
    @Override public void fillRect(int x, int y, int width, int height, int argbTL, int argbTR, int argbBR, int argbBL) { }
    @Override public void clearRect(int x, int y, int width, int height, int argbTL, int argbTR, int argbBR, int argbBL) { }
    @Override public void drawOval(int x, int y, int width, int height, int thickness, int argbTL, int argbTR, int argbBR, int argbBL, int clipX, int clipY, int clipW, int clipH) { }
    @Override public void fillOval(int x, int y, int width, int height, int argbTL, int argbTR, int argbBR, int argbBL, int clipX, int clipY, int clipW, int clipH) { }
    @Override public void drawRoundRect(int x, int y, int width, int height, int thickness, int arcRadius, int argbTL, int argbTR, int argbBR, int argbBL, int clipX, int clipY, int clipW, int clipH) { }
    @Override public void fillRoundRect(int x, int y, int width, int height, int arcRadius, int argbTL, int argbTR, int argbBR, int argbBL, int clipX, int clipY, int clipW, int clipH) { }
    @Override public void drawTexture(int x, int y, int width, int height, int handle, ImageHolder<Texture> img, int srcx, int srcy, int srcwidth, int srcheight, int blend) { }
    @Override public void drawLine(int x1, int y1, int x2, int y2, int argb1, int argb2) { }
    @Override public ImageHolder<Texture> loadImage(int width, int height) { return new ImageHolder<>(null, width, height); }
    @Override public void unloadImage(int handle, ImageHolder<Texture> bi) { }
    @Override public ImageHolder<Texture> createSurface(int handle, int width, int height) { return new ImageHolder<>(null, width, height); }
    @Override public ImageHolder<Texture> readImage(File cachedFile) { return new ImageHolder<>(null, 1, 1); }
    @Override public ImageHolder<Texture> readImage(InputStream bais) { return new ImageHolder<>(null, 1, 1); }
    @Override public ImageHolder<Texture> newImage(int destWidth, int destHeight) { return new ImageHolder<>(null, destWidth, destHeight); }
    @Override public void registerTexture(ImageHolder<Texture> texture) { }
    @Override public void setTargetSurface(int handle, ImageHolder<Texture> image) { }
    @Override public void flipBuffer() { }
    @Override public void startFrame() { }
    @Override public void loadImageLine(int handle, ImageHolder<Texture> image, int line, int len2, byte[] cmddata) { }
    @Override public void xfmImage(int srcHandle, ImageHolder<Texture> srcImg, int destHandle, ImageHolder<Texture> destImg, int destWidth, int destHeight, int maskCornerArc) { }
    @Override public boolean hasGraphicsCanvas() { return false; }
    @Override public Dimension getMaxScreenSize() { return DEFAULT_SIZE; }
    @Override public Dimension getScreenSize() { return DEFAULT_SIZE; }
    @Override public Dimension getUISize() { return DEFAULT_SIZE; }
    @Override public void setFullScreen(boolean b) { }
    @Override public void setSize(int w, int h) { }
    @Override public void invokeLater(Runnable runnable) { if (runnable != null) runnable.run(); }
    @Override public Scale getScale() { return DEFAULT_SCALE; }
    @Override public boolean createVideo(int width, int height, int format) { return false; }
    @Override public boolean updateVideo(int frametype, ByteBuffer buf) { return false; }
    @Override public MiniPlayerPlugin newPlayerPlugin(MiniClientConnection connection, String urlString) { return NOOP_PLAYER; }
    @Override public void setVideoBounds(Rectangle o, Rectangle o1) { }
    @Override public void onMenuHint(MenuHint hint) { }
    @Override public boolean isFirstFrameRendered() { return false; }
    @Override public void setVideoAdvancedAspect(String value) { }
    @Override public void setUIAspectRatio(float value) { }
    @Override public float getUIAspectRatio() { return 1f; }

    private static final class NoopMiniPlayer implements MiniPlayerPlugin {
        @Override public void run() { }
        @Override public void free() { }
        @Override public void setPushMode(boolean b) { }
        @Override public void load(byte majorTypeHint, byte minorTypeHint, String encodingHint, String urlString, String hostname, boolean timeshifted, long bufferSize) { }
        @Override public long getMediaTimeMillis(long lastServerTime) { return 0; }
        @Override public int getState() { return NO_STATE; }
        @Override public void setMute(boolean b) { }
        @Override public void stop() { }
        @Override public void pause() { }
        @Override public void play() { }
        @Override public void seek(long timeMS) { }
        @Override public void setServerEOS() { }
        @Override public long getLastFileReadPos() { return 0; }
        @Override public int getVolume() { return 0; }
        @Override public int setVolume(float v) { return 0; }
        @Override public void setAudioTrack(int streamPos) { }
        @Override public void setSubtitleTrack(int streamPos) { }
        @Override public int getSelectedSubtitleTrack() { return DISABLE_TRACK; }
        @Override public int getSubtitleTrackCount() { return 0; }
        @Override public SubtitleTrack[] getSubtitleTracks() { return new SubtitleTrack[0]; }
        @Override public void setVideoRectangles(Rectangle srcRect, Rectangle destRect, boolean hideCursor) { }
        @Override public Dimension getVideoDimensions() { return DEFAULT_SIZE; }
        @Override public void pushData(byte[] cmddata, int bufDataOffset, int buffSize) { }
        @Override public void flush() { }
        @Override public int getBufferLeft() { return 0; }
        @Override public void setVideoAdvancedAspect(String aspectMode) { }
    }
}
