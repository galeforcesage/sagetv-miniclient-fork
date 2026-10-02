package sagex.miniclient.android.ui.keymaps;

import android.view.KeyEvent;

import sagex.miniclient.SageCommand;

public class GuideKeyMap extends KeyMap {
    public GuideKeyMap(KeyMap parent) {
        super(parent);
    }

    @Override
    public void initializeKeyMaps() {
        super.initializeKeyMaps();

        // Key Mapping for GUIDE
        // Long-press the D-pad to page through the guide: Left/Right scroll the
        // time window a full page, Up/Down jump a page of channels. This replaces
        // the previous REW_2/FF_2 (and inherited Channel +/-) long-press bindings,
        // which have no useful effect inside the guide.
        LONGPRESS_KEYMAP.put(KeyEvent.KEYCODE_DPAD_LEFT, SageCommand.PAGE_LEFT);
        LONGPRESS_KEYMAP.put(KeyEvent.KEYCODE_DPAD_RIGHT, SageCommand.PAGE_RIGHT);
        LONGPRESS_KEYMAP.put(KeyEvent.KEYCODE_DPAD_UP, SageCommand.PAGE_UP);
        LONGPRESS_KEYMAP.put(KeyEvent.KEYCODE_DPAD_DOWN, SageCommand.PAGE_DOWN);
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
