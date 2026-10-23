package com.glaciersecurity.glaciermessenger.services;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.EndPointConfig;
import com.glaciersecurity.glaciermessenger.Endpoints;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.ui.ExternalCallListener;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.google.gson.internal.LinkedTreeMap;
import com.twilio.voice.Call;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class ExternalCallManager {

    protected XmppConnectionService mXmppConnectionService;
    protected ExternalCallListener externalCallListener;
    private Call activeCall;
    private String remoteContact;
    private String proxyNumber;
    private boolean isSpeakerPhone = false;

    public Call getActiveCall() {
        return activeCall;
    }

    public void setActiveCall(Call activeCall) {
        this.activeCall = activeCall;
        if (activeCall == null){
            remoteContact = null;
            proxyNumber = null;
            if (externalCallListener != null) {
                externalCallListener.onCallStatusChange(false);
            }
            isSpeakerPhone = false;

        } else {
            getCallDetails(activeCall.getSid());
            if (externalCallListener != null) {
                externalCallListener.onCallStatusChange(true);
            }        }
    }
    public void setActiveCallwithAccount(Call activeCall, String remoteContact, Account account) {
        this.activeCall = activeCall;
        if (activeCall == null){
            this.remoteContact = null;
            proxyNumber = null;
            if (externalCallListener != null) {
                externalCallListener.onCallStatusChange(false);
            }
            isSpeakerPhone = false;

        } else {
            this.remoteContact = remoteContact;
            getCallDetails(activeCall.getSid(), account);
            if (externalCallListener != null) {
                externalCallListener.onCallStatusChange(true);
            }
        }
    }

    public void setActiveCall(Call activeCall, String remoteContact, String localProxy) {
        this.activeCall = activeCall;
        if (activeCall == null){
            this.remoteContact = null;
            this.proxyNumber = null;
            if (externalCallListener != null) {
                externalCallListener.onCallStatusChange(false);
            }
            this.isSpeakerPhone = false;

        } else {
            this.remoteContact = remoteContact;
            this.proxyNumber = localProxy;
            if (externalCallListener != null) {
                externalCallListener.onCallStatusChange(true);
            }
        }
    }


    public boolean isSpeakerPhone() {
        return isSpeakerPhone;
    }

    public void setSpeakerPhone(boolean speakerPhone) {
        isSpeakerPhone = speakerPhone;
    }

    public void disconnectPhoneCall(){
        if (activeCall != null){
            activeCall.disconnect();
            setActiveCallwithAccount(null, null, null);
        }
    }

    public String getRemoteContact() {
        return remoteContact;
    }

    public void setRemoteContact(String remoteContact) {
        this.remoteContact = remoteContact;
    }

    public static class CallDetailsResponse {
        @SerializedName("message")
        public String message;
        @SerializedName("call")
        public Object call;
    }

    private void getCallDetails(String callSid, Account account) {
        if (callSid == null) {
            return;
        }
        new Thread(() -> {
            String listVoicemailsUrl =
                    Endpoints.base() + EndPointConfig.GET_CALL_DETAILS_URL;
            OkHttpClient client = OkHttpClientProvider.getOkHttpClient();
            Map<String, String> formFields = new HashMap<>();
            formFields.put("callSid", callSid);
            formFields.put("username", account.getUsername());
            formFields.put("organization", account.getOrganization());
            Request request = null;
            try {
                request = OkHttpClientProvider.buildJwtFormRequestBuilder(listVoicemailsUrl, formFields)
                        .build();
            } catch (IOException e) {
                Log.e(Config.LOGTAG, e.getLocalizedMessage(), e);
                return;
            }
            try (Response response = client.newCall(request).execute()) {
                String responseBody = response.body().string();
                Gson gson = new Gson();
                ExternalCallManager.CallDetailsResponse callDetailsResponse = gson.fromJson(responseBody, ExternalCallManager.CallDetailsResponse.class);
                if (callDetailsResponse.message != null  && callDetailsResponse.message.equals("success")) {
                    CallInfo callDetail = getCallDetails(callDetailsResponse.call);
                    if (callDetail != null) {
                        proxyNumber = callDetail.getProxy();
                    }
                    if (remoteContact == null && callDetail != null) {
                        remoteContact = callDetail.getRemote();
                    }
                }
            } catch (Exception ex) {
                Log.e(Config.LOGTAG, ex.getLocalizedMessage(), ex);
            }
        }).start();

    }

    private CallInfo getCallDetails(Object callInfo) {
        try {
            if (callInfo instanceof LinkedTreeMap) {
                LinkedTreeMap<Object, Object> treeMap = (LinkedTreeMap<Object, Object>) callInfo;
                CallInfo vm = new CallInfo(treeMap);
                return vm;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;

    }


    public ExternalCallManager(XmppConnectionService service) {
        mXmppConnectionService = service;
    }

    public void setCallListener(ExternalCallListener listener) {
        externalCallListener = listener;
    }

    public boolean isOnCall() {
        if (activeCall != null){
            return true;
        }
        return false;
    }

    /**
     * Sends dial-pad input to the current Twilio call.
     *
     * @return {@code true} when there was an active call to receive the digits
     */
    public boolean sendDigits(String digits) {
        if (activeCall == null || digits == null || digits.isEmpty()) {
            return false;
        }
        activeCall.sendDigits(digits);
        return true;
    }


    public String getProxyNumber() {
        return proxyNumber;
    }


}
