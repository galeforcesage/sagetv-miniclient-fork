package sagex.miniclient.uibridge;

public class ImageHolder<T extends Texture> extends Holder<T> {
    private int handle=-1;
    private int width;
    private int height;
    private volatile boolean decodePending;

    public ImageHolder() {
    }

    public ImageHolder(T img, int width, int height) {
        super(img);
        this.width = width;
        this.height = height;
        this.handle = -1;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getHandle() {
        return handle;
    }

    /**
     * Returns true if this image is still being decoded on a background thread.
     * Draw commands should skip this image until decode completes.
     */
    public boolean isDecodePending() {
        return decodePending;
    }

    public void setDecodePending(boolean pending) {
        this.decodePending = pending;
    }

    // release resources for this image
    public void dispose() {
        if (get() instanceof  Disposable) {
            try {
                ((Disposable) get()).dispose();
            } catch (Throwable t) {
            }
        }
        set(null);
        this.handle=-1;
        this.width=0;
        this.height=0;
        this.decodePending = false;
    }

    public void setHandle(int handle) {
        this.handle = handle;
    }
}
