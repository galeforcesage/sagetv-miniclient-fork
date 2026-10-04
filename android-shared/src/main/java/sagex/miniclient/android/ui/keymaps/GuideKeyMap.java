package sagex.miniclient.android.ui.keymaps;

import android.view.KeyEvent;

public class GuideKeyMap extends KeyMap {
    public GuideKeyMap(KeyMap parent) {
        super(parent);
    }

    @Override
    public void initializeKeyMaps() {
        super.initializeKeyMaps();

        // Key Mapping for GUIDE
        //
        // The guide intentionally does NOT define its own D-pad long-press
        // bindings. It inherits them from the parent (Default) key map, which is
        // driven by the user-configurable "Left/Right/Up/Down (Long Press)" rows
        // in Settings -> Mappings (defaults: REW / FF / CHANNEL_UP / CHANNEL_DOWN
        // -- the canonical SageTV commands that actually page the STV guide).
        //
        // A previous build hardcoded PAGE_LEFT/RIGHT/UP/DOWN here, which both
        // shadowed those configurable mappings while the guide was open and did
        // not page the guide at all (the generic Command_Page_* events are not
        // acted on by the STV guide). That override has been revoked; only the
        // guide-specific *feel* (slower long-press repeat + hold-to-page instead
        // of immediate nav-repeat) is kept below.
    }

    @Override
    public int getKeyRepeatRateMS(int keyCode) {
        // prevent paging forward/back using long press from happening too quickly
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
            return 750;

        if (parent != null) return parent.getKeyRepeatRateMS(keyCode);

        // should never get here
        return 0;
    }

    @Override
    public boolean isNavigationKey(int keyCode) {
        if (
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                        keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                        keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                        keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            return false;
        }
        return parent.isNavigationKey(keyCode);
    }
}
