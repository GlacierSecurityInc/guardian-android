package com.glaciersecurity.glaciermessenger.ui;

import static com.glaciersecurity.glaciermessenger.entities.Presence.StatusMessage.meetingIcon;
import static com.glaciersecurity.glaciermessenger.entities.Presence.StatusMessage.sickIcon;
import static com.glaciersecurity.glaciermessenger.entities.Presence.StatusMessage.travelIcon;
import static com.glaciersecurity.glaciermessenger.entities.Presence.StatusMessage.vacationIcon;
import static com.glaciersecurity.glaciermessenger.entities.Presence.getEmojiByUnicode;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.view.MotionEvent;
import android.view.View;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.IdRes;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.databinding.DataBindingUtil;

import com.glaciersecurity.glaciermessenger.AppSettings;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.DialogPresenceBinding;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Presence;
import com.glaciersecurity.glaciermessenger.entities.PresenceTemplate;
import com.glaciersecurity.glaciermessenger.ui.interfaces.OnBackendConnected;


public class ChangeStatusActivity extends XmppActivity implements OnBackendConnected {
    private DialogPresenceBinding binding;
    private boolean suppressAvailabilitySync = false;
    private Account mAccount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.binding = DataBindingUtil.setContentView(this, R.layout.dialog_presence);

        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());

        // Edge-to-edge (enforced on targetSdk 35+): manually apply system bar and IME insets so
        // the sticky footer stays above the keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            int bottom = Math.max(systemBars.bottom, ime.bottom);
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, bottom);
            return insets;
        });

        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());
    }

    @Override
    protected void refreshUiReal() {

    }

    @Override
    public void onBackendConnected() {
        mAccount = xmppConnectionService.getFirstAccount();
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        String current = mAccount.getPresenceStatusMessage();
        if (current != null && !current.trim().isEmpty()) {
            binding.statusMessage.append(current);
        }

        // Select initial availability radio
        boolean manualMode = prefs.getBoolean(AppSettings.MANUALLY_CHANGE_PRESENCE,
                getResources().getBoolean(R.bool.manually_change_presence));
        if (!manualMode) {
            binding.deviceManaged.setChecked(true);
        } else {
            setAvailabilityRadioButton(mAccount.getPresenceStatus(), binding);
        }

        setStatusMessageRadioButton(mAccount.getPresenceStatusMessage(), binding);

        // Edit button navigates to Settings > Privacy, scrolled to the Availability section
        ViewCompat.setTooltipText(binding.editAvailabilityButton, getString(R.string.edit_availability_settings));
        binding.editAvailabilityButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(ChangeStatusActivity.this, SettingsActivity.class);
                intent.putExtra(SettingsActivity.SETTINGS_FRAG_INTENT, SettingsActivity.PRIVACY_FRAG);
                intent.putExtra(SettingsActivity.SCROLL_TO_PREFERENCE, AppSettings.MANUALLY_CHANGE_PRESENCE);
                startActivity(intent);
            }
        });

        // Manual group: when any manual option is selected, clear the managed group and save immediately
        binding.show.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, @IdRes int checkedId) {
                if (checkedId == -1) return;
                if (suppressAvailabilitySync) return;
                suppressAvailabilitySync = true;
                binding.managedShow.clearCheck();
                suppressAvailabilitySync = false;
                if (!prefs.getBoolean(AppSettings.MANUALLY_CHANGE_PRESENCE,
                        getResources().getBoolean(R.bool.manually_change_presence))) {
                    prefs.edit().putBoolean(AppSettings.MANUALLY_CHANGE_PRESENCE, true).apply();
                    xmppConnectionService.toggleScreenEventReceiver();
                }
                saveStatus();
            }
        });

        // Managed group: when Device Managed is selected, clear the manual group and save immediately
        binding.managedShow.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, @IdRes int checkedId) {
                if (checkedId == -1) return;
                if (checkedId == R.id.device_managed) {
                    if (suppressAvailabilitySync) return;
                    suppressAvailabilitySync = true;
                    binding.show.clearCheck();
                    suppressAvailabilitySync = false;
                    prefs.edit().putBoolean(AppSettings.MANUALLY_CHANGE_PRESENCE, false).apply();
                    xmppConnectionService.toggleScreenEventReceiver();
                    xmppConnectionService.refreshAllPresences();
                    saveStatus();
                }
            }
        });

        binding.clearPrefs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideKeyboard();
                binding.statuses.clearCheck();
                binding.statusMessage.setText("");
            }
        });

        binding.statuses.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener(){
            @Override
            public void onCheckedChanged(RadioGroup group, @IdRes int checkedId) {
                if (checkedId == R.id.in_meeting) {
                    binding.statusMessage.setText(Presence.StatusMessage.IN_MEETING.toShowString());
                    saveStatus();
                } else if (checkedId == R.id.on_travel) {
                    binding.statusMessage.setText(Presence.StatusMessage.ON_TRAVEL.toShowString());
                    saveStatus();
                } else if (checkedId == R.id.out_sick) {
                    binding.statusMessage.setText(Presence.StatusMessage.OUT_SICK.toShowString());
                    saveStatus();
                } else if (checkedId == R.id.vacation) {
                    binding.statusMessage.setText(Presence.StatusMessage.VACATION.toShowString());
                    saveStatus();
                } else if (checkedId == R.id.custom) {
                    // no-op
                } else {
                    // default: no-op
                }
            }
        });

        View.OnClickListener selectCustomStatusListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectCustomStatus();
            }
        };

        binding.statusMessageLayout.setOnClickListener(selectCustomStatusListener);
        binding.statusMessage.setOnClickListener(selectCustomStatusListener);
        binding.statusMessage.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus && !binding.custom.isChecked()) {
                    selectCustomStatus();
                }
            }
        });

        binding.confirmButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                hideKeyboard();
                saveStatus();
                finish();
            }
        });

        binding.statusMessage.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                final boolean isEnterKey =
                        event != null
                                && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                                && event.getAction() == KeyEvent.ACTION_DOWN;
                if (actionId == EditorInfo.IME_ACTION_DONE || isEnterKey) {
                    binding.confirmButton.performClick();
                    return true;
                }
                return false;
            }
        });

    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) {
            focus.clearFocus();
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            View root = binding.getRoot();
            imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
        }
    }

    private void saveStatus() {
        Presence.Status statusToSave = binding.deviceManaged.isChecked()
                ? mAccount.getPresenceStatus()
                : getAvailabilityRadioButton(binding);
        PresenceTemplate template = new PresenceTemplate(
                statusToSave,
                binding.statusMessage.getText().toString().trim());
        xmppConnectionService.changeStatus(mAccount, template, null);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            View focused = getCurrentFocus();
            if (focused == binding.statusMessage) {
                Rect rect = new Rect();
                focused.getGlobalVisibleRect(rect);
                if (!rect.contains((int) event.getRawX(), (int) event.getRawY())) {
                    focused.clearFocus();
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
                    }
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private static void setAvailabilityRadioButton(
            Presence.Status status, DialogPresenceBinding binding) {
        if (status == null) {
            binding.online.setChecked(true);
            return;
        }
        switch (status) {
            case DND:
                binding.dnd.setChecked(true);
                break;
            case XA:
            case OFFLINE:
                binding.xa.setChecked(true);
                break;
            case AWAY:
                binding.away.setChecked(true);
                break;
            default:
                binding.online.setChecked(true);
        }
    }

    private static Presence.Status getAvailabilityRadioButton(DialogPresenceBinding binding) {
        if (binding.dnd.isChecked()) {
            return Presence.Status.DND;
        } else if (binding.xa.isChecked()) {
            return Presence.Status.XA;
        } else if (binding.away.isChecked()) {
            return Presence.Status.AWAY;
        } else {
            return Presence.Status.ONLINE;
        }
    }

    private void selectCustomStatus() {
        binding.custom.setChecked(true);
        final boolean hasFocus = binding.statusMessage.hasFocus();
        binding.statusMessage.post(new Runnable() {
            @Override
            public void run() {
                if (!hasFocus) {
                    binding.statusMessage.requestFocus();
                }
                Editable text = binding.statusMessage.getText();
                if (text != null) {
                    binding.statusMessage.setSelection(text.length());
                }
            }
        });
    }

    public static void setStatusMessageRadioButton(String statusMessage, DialogPresenceBinding binding) {
        if (statusMessage == null) {
            binding.statuses.clearCheck();
            return;
        }
        binding.statuses.clearCheck();
        if (statusMessage.equals(getEmojiByUnicode(meetingIcon)+"\tIn a meeting")) {
            binding.inMeeting.setChecked(true);
        } else if (statusMessage.equals(getEmojiByUnicode(travelIcon)+"\tOn travel")) {
            binding.onTravel.setChecked(true);
        } else if (statusMessage.equals(getEmojiByUnicode(sickIcon)+"\tOut sick")) {
            binding.outSick.setChecked(true);
        } else if (statusMessage.equals(getEmojiByUnicode(vacationIcon)+"\tVacation")) {
            binding.vacation.setChecked(true);
        } else if (!statusMessage.isEmpty()) {
            binding.custom.setChecked(true);
        } else {
            binding.statuses.clearCheck();
        }

    }

}
