package com.glaciersecurity.glaciermessenger.utils;

import com.glaciersecurity.glaciermessenger.entities.SmsProfile;

import java.util.ArrayList;

public interface OnSMSCompleteCallbackListener {
    void onSelectedTwilioNumbersLoaded(boolean isSuccess, ArrayList<SmsProfile> smsProfiles, boolean forced);
    void onRefreshComplete(SMSdbInfo.PhoneSyncStatus phoneSyncStatus);
}
