package com.glaciersecurity.glaciermessenger.ui.interfaces;

import android.app.Dialog;

import com.glaciersecurity.glaciermessenger.entities.SmsProfile;

public interface OnSMSRemoveClickListener extends Dialog.OnClickListener {
    void OnSMSRemoveClick(SmsProfile selectedSMSforRelease);

}
