package com.glaciersecurity.glaciermessenger.ui.fragment.phone;


import com.glaciersecurity.glaciermessenger.entities.SmsProfile;
import com.glaciersecurity.glaciermessenger.ui.XmppFragment;
import com.glaciersecurity.glaciermessenger.ui.interfaces.OnBackendConnected;

public abstract class PhoneFragment extends XmppFragment implements OnBackendConnected {

    public void OnSMSProfileClick(SmsProfile sp) {}
}
