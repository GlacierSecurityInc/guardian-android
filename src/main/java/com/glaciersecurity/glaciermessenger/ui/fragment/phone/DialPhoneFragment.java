package com.glaciersecurity.glaciermessenger.ui.fragment.phone;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.Toast;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.services.ExternalCallManager;
import com.glaciersecurity.glaciermessenger.ui.ExternalCallActivity;
import com.glaciersecurity.glaciermessenger.ui.PhoneModuleActivity;
import com.glaciersecurity.glaciermessenger.ui.util.AddressText;
import com.glaciersecurity.glaciermessenger.ui.util.Digit;
import com.glaciersecurity.glaciermessenger.ui.util.EraseButton;
import com.glaciersecurity.glaciermessenger.utils.PhoneUtils;

import java.util.ArrayList;
import java.util.Collection;

public class DialPhoneFragment extends PhoneFragment implements AddressText.AddressChangedListener {
    private PhoneModuleActivity activity;
    private AddressText mAddress;
    private ImageButton mStartCall;
    private ImageButton mEndCall;
    private EraseButton erase;
    private ToneGenerator toneGenerator;
    private AudioManager audioManager;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (context instanceof PhoneModuleActivity) {
            this.activity = (PhoneModuleActivity) context;
        } else {
            throw new IllegalStateException("Trying to attach fragment to activity that is not the PHoneModuleActivity");
        }
    }


    @Override
    public View onCreateView(final LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.dialer, container, false);
        if (activity != null) {
            activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        mAddress = v.findViewById(R.id.address);
        mAddress.setShowSoftInputOnFocus(false);
        mAddress.setAddressListener(this);
        erase = v.findViewById(R.id.erase);
        erase.setAddressWidget(mAddress);
        mEndCall = v.findViewById(R.id.end_call);
        mEndCall.setOnClickListener(x -> {

        });
        mStartCall = v.findViewById(R.id.start_call);
        mStartCall.setOnClickListener(x -> {
            // A service that has not bound yet is "not ready", the same as one still syncing —
            // not "ready to dial". Falling through on null would clear mAddress below and then
            // find no ExternalCallManager, discarding the number the user just typed.
            if (activity.xmppConnectionService == null
                    || activity.xmppConnectionService.isPhoneSyncInProgress()) {
                Toast.makeText(getContext(), R.string.loading_phone_number, Toast.LENGTH_SHORT).show();
                return;
            }
            if (mAddress != null && !mAddress.getText().toString().isEmpty()) {
                String toPhoneNumber = PhoneUtils.getPhoneNumber(mAddress.getText().toString());
                mAddress.setText("");
                if (activity.xmppConnectionService.getSmsInfo().containsProxy(toPhoneNumber)) {
                    Toast.makeText(getContext(), R.string.no_self_calls, Toast.LENGTH_SHORT).show();
                    return;
                }
                ExternalCallManager mExternalCallManager = activity.xmppConnectionService.getExternalCallManager();
                if (mExternalCallManager != null) {
                    if (mExternalCallManager.isOnCall()) {
                        Intent externalCallActivity = new Intent(getContext(), ExternalCallActivity.class);
                        externalCallActivity.putExtra("returning", "true");
                        startActivity(externalCallActivity);
                    } else {
                        String number = PhoneUtils.getStripPhoneNumber(toPhoneNumber);
                        Intent callActivity = new Intent(getContext(), ExternalCallActivity.class);
                        callActivity.setAction(Config.ACTION_OUTGOING_CALL);
                        callActivity.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                        callActivity.putExtra("toNumber", PhoneUtils.getStripPhoneNumber(number));
                        if (activity.selectedProfile != null) {
                            callActivity.putExtra(Config.TO_PROXY, activity.selectedProfile.getUnformattedNumber());
                        }

                        startActivity(callActivity);
                    }
                }

            }
        });
        if (mAddress.getText().toString().isEmpty()) {
            erase.setVisibility(View.GONE);
        } else {
            erase.setVisibility(View.VISIBLE);
        }
        setUpNumpad(v.findViewById(R.id.numpad));
        return v;
    }


    @Override
    protected void refresh() {
    }

    @Override
    public void onBackendConnected() {
        if (activity.mExternalCallManager != null) {
            activity.mExternalCallManager.setCallListener(activity);
            if (activity.mExternalCallManager.isOnCall()) {
                mStartCall.setVisibility(View.GONE);
                mEndCall.setVisibility(View.VISIBLE);
            } else {
                mStartCall.setVisibility(View.VISIBLE);
                mEndCall.setVisibility(View.GONE);
            }
        }
    }

    private void setUpNumpad(View view) {
        if (view == null) return;
        for (Digit v : retrieveChildren((ViewGroup) view, Digit.class)) {
            v.setAddressWidget(mAddress);
            v.setOnLongClickListener(view1 -> {
                final char mKeyCode = v.getText().subSequence(0, 1).charAt(0);
                switch (mKeyCode) {
                    case '1':
                        return true;
                    case '0':
                        // Long-press 0 inserts the international dialing prefix "+".
                        if (mAddress != null) {
                            int begin = mAddress.getSelectionStart();
                            if (begin == -1) {
                                begin = mAddress.length();
                            }
                            if (begin >= 0) {
                                mAddress.getEditableText().insert(begin, "+");
                            }
                        }
                        return true;
                    default:return false;
            }});
            v.setOnClickListener(view1 -> {
                {
                    ensureToneGenerator();
                    if (toneGenerator != null) {
                        toneGenerator.stopTone();
                    }
                    final char mKeyCode = v.getText().subSequence(0, 1).charAt(0);

                    int toneGen;
                    switch (mKeyCode) {
                        case '1':
                            toneGen = ToneGenerator.TONE_DTMF_1;
                            break;
                        case '2':
                            toneGen = ToneGenerator.TONE_DTMF_2;
                            break;
                        case '3':
                            toneGen = ToneGenerator.TONE_DTMF_3;
                            break;
                        case '4':
                            toneGen = ToneGenerator.TONE_DTMF_4;
                            break;
                        case '5':
                            toneGen = ToneGenerator.TONE_DTMF_5;
                            break;
                        case '6':
                            toneGen = ToneGenerator.TONE_DTMF_6;
                            break;
                        case '7':
                            toneGen = ToneGenerator.TONE_DTMF_7;
                            break;
                        case '8':
                            toneGen = ToneGenerator.TONE_DTMF_8;
                            break;
                        case '9':
                            toneGen = ToneGenerator.TONE_DTMF_9;
                            break;
                        default:
                            toneGen = ToneGenerator.TONE_DTMF_0;
                            break;

                    }
                    if (toneGenerator != null) {
                        toneGenerator.startTone(toneGen, 100);
                    }
                    if (mAddress != null) {
                        int begin = mAddress.getSelectionStart();
                        if (begin == -1) {
                            begin = mAddress.length();
                        }
                        if (begin >= 0) {
                            mAddress.getEditableText().insert(begin, String.valueOf(mKeyCode));
                        }
                    }
                    sendDigitToActiveCall(mKeyCode);
                }

            });
        }
    }

    private void sendDigitToActiveCall(char digit) {
        if (activity == null || activity.xmppConnectionService == null) {
            return;
        }
        ExternalCallManager externalCallManager =
                activity.xmppConnectionService.getExternalCallManager();
        if (externalCallManager != null) {
            externalCallManager.sendDigits(String.valueOf(digit));
        }
    }

    private <T> Collection<T> retrieveChildren(ViewGroup viewGroup, Class<T> clazz) {
        final Collection<T> views = new ArrayList<>();
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View v = viewGroup.getChildAt(i);
            if (v instanceof ViewGroup) {
                views.addAll(retrieveChildren((ViewGroup) v, clazz));
            } else {
                if (clazz.isInstance(v)) views.add(clazz.cast(v));
            }
        }
        return views;
    }

    private void ensureToneGenerator() {
        if (toneGenerator != null) {
            return;
        }
        if (audioManager == null) {
            audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        }
        if (audioManager == null) {
            return;
        }
        int volumeLevel = audioManager.getStreamVolume(AudioManager.STREAM_RING);
        toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, volumeLevel * 14);
    }
    @Override
    public void onAddressChanged() {
        if (mAddress.getText().toString().isEmpty()) {
            erase.setVisibility(View.GONE);
        } else {
            erase.setVisibility(View.VISIBLE);
        }
    }
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }
    }
}
