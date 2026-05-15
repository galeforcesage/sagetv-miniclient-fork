package sagex.miniclient.android.ui.settings;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.CheckBox;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import sagex.miniclient.android.R;
import sagex.miniclient.prefs.TriState;

/**
 * Three-state preference widget used by the Phase 2 "Automatic-First" settings
 * UX. A row shows a checkbox plus the title with a trailing superscript marker:
 *
 * <ul>
 *   <li>{@link TriState#AUTO}  — checkbox reflects {@link #setAutoValue(boolean)},
 *       title gets a superscript {@code ᵃ}. The visual is dimmed slightly so
 *       the user can see the value is auto-derived rather than pinned.</li>
 *   <li>{@link TriState#ON}    — checkbox checked, title gets a superscript
 *       {@code ᵒ}.</li>
 *   <li>{@link TriState#OFF}   — checkbox unchecked, title gets a superscript
 *       {@code ᵒ}.</li>
 * </ul>
 *
 * <p>Tapping the row cycles {@code AUTO → ON → OFF → AUTO}. Long-press jumps
 * straight back to {@link TriState#AUTO}. The persisted value is the string
 * form returned by {@link TriState#toPrefValue()}.</p>
 *
 * <p>{@link #setAutoValue(boolean)} should be called by the hosting fragment
 * after the auto-detected capability is known so the AUTO checkbox visual is
 * accurate. It does not change the persisted value.</p>
 */
public class TriStatePreference extends Preference
{
    private static final String SUPER_AUTO = "\u1d43\u1d58\u1d57\u1d52"; // ᵃᵘᵗᵒ
    private static final String SUPER_ON   = "\u1d52\u207f";             // ᵒⁿ

    @Nullable private CharSequence baseTitle;
    private TriState state = TriState.AUTO;
    private boolean autoValue = false;

    public TriStatePreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes)
    {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public TriStatePreference(Context context, AttributeSet attrs, int defStyleAttr)
    {
        super(context, attrs, defStyleAttr);
        init();
    }

    public TriStatePreference(Context context, AttributeSet attrs)
    {
        super(context, attrs);
        init();
    }

    public TriStatePreference(Context context)
    {
        super(context);
        init();
    }

    private void init()
    {
        // Re-use the simple checkbox row layout shipped with androidx.preference;
        // we drive the checked state ourselves in onBindViewHolder().
        setWidgetLayoutResource(R.layout.preference_widget_tristate);
    }

    @Override
    public void setTitle(CharSequence title)
    {
        this.baseTitle = title;
        super.setTitle(decorateTitle());
    }

    public TriState getState()
    {
        return state;
    }

    /**
     * Set the auto-detected capability for this row. Used to render the
     * {@link TriState#AUTO} checkbox visual; does not persist anything.
     */
    public void setAutoValue(boolean autoValue)
    {
        if (this.autoValue != autoValue)
        {
            this.autoValue = autoValue;
            notifyChanged();
        }
    }

    public boolean getAutoValue()
    {
        return autoValue;
    }

    /** Returns the resolved boolean (auto-derived or user-pinned). */
    public boolean isEffectivelyOn()
    {
        return state.resolve(autoValue);
    }

    @Override
    protected void onSetInitialValue(@Nullable Object defaultValue)
    {
        String stored = getPersistedString(
                defaultValue instanceof String ? (String) defaultValue : TriState.AUTO.toPrefValue());
        state = TriState.fromPrefValue(stored);
        super.setTitle(decorateTitle());
    }

    private void setStateInternal(TriState next, boolean fromUser)
    {
        if (next == null) next = TriState.AUTO;
        if (state == next) return;
        if (fromUser && !callChangeListener(next.toPrefValue())) return;
        state = next;
        persistString(state.toPrefValue());
        super.setTitle(decorateTitle());
        notifyChanged();
    }

    private CharSequence decorateTitle()
    {
        if (baseTitle == null) return null;
        switch (state)
        {
            case AUTO: return baseTitle + " " + SUPER_AUTO;
            case ON:   return baseTitle + " " + SUPER_ON;
            case OFF:
            default:   return baseTitle;
        }
    }

    @Override
    protected void onClick()
    {
        setStateInternal(state.next(), /* fromUser= */ true);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder)
    {
        super.onBindViewHolder(holder);
        View widget = holder.findViewById(R.id.tristate_checkbox);
        if (widget instanceof CheckBox)
        {
            CheckBox cb = (CheckBox) widget;
            cb.setChecked(isEffectivelyOn());
            // Dim slightly when the value comes from auto-detection so the
            // user can tell at a glance which rows are pinned.
            cb.setAlpha(state == TriState.AUTO ? 0.55f : 1.0f);
        }
        // Long-press anywhere on the row resets to AUTO.
        holder.itemView.setOnLongClickListener(new View.OnLongClickListener()
        {
            @Override
            public boolean onLongClick(View v)
            {
                if (state != TriState.AUTO)
                {
                    setStateInternal(TriState.AUTO, /* fromUser= */ true);
                    return true;
                }
                return false;
            }
        });
    }
}
