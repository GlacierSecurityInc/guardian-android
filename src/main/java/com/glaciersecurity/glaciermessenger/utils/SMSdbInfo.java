package com.glaciersecurity.glaciermessenger.utils;

import static com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider.getOkHttpClient;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import androidx.annotation.NonNull;


import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.EndPointConfig;
import com.glaciersecurity.glaciermessenger.Endpoints;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.HistoryItem;
import com.glaciersecurity.glaciermessenger.entities.SmsProfile;
import com.glaciersecurity.glaciermessenger.entities.TwilioConversationEntity;
import com.glaciersecurity.glaciermessenger.entities.TwilioMessageEntity;
import com.glaciersecurity.glaciermessenger.entities.VoicemailRecord;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import com.google.gson.internal.LinkedTreeMap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;


public class SMSdbInfo {

    final public Map<String, ArrayList<TwilioConversationEntity>> conv_list = new HashMap<>();
    final public Map<String, TwilioConversationEntity> conv_by_sid = new HashMap<>();
    final public Map<String, String> conv_last_msg = new HashMap<>();
    final public Map<String, Boolean> conv_last_msg_is_media = new HashMap<>();
    final public Map<String, String> conv_last_msg_sent = new HashMap<>();
    final public Map<String, Integer> conv_last_msg_count = new HashMap<>();
    final public Map<String, ArrayList<TwilioMessageEntity>> conv_messages = new HashMap<>();
    final public Map<String, Integer> unread_conv_count = new HashMap<>();
    final public Map<String, ArrayList<String>> unread_conv = new HashMap<>();
    private final XmppConnectionService xmppConnectionService;
    public int readVoicemails;
    public int newVoicemails;

    private OnVmCompleteCallbackListener onVmCompleteCallbackListener;

    HistoryCallback<Boolean> callback = (result, historyItems) -> {};
    private Account account;
    private ArrayList<SmsProfile> dbProfs = new ArrayList<>();
    private boolean dbPurchaseNum = false;
    private boolean isSMSEnabled = false;
    private SmsProfile selectedProfile;

    private static final int SELECTED_TWILIO_DB_POLL_ATTEMPTS = 5;
    private static final long SELECTED_TWILIO_DB_POLL_DELAY_MS = 1_000L;

    public enum PhoneSyncStatus {
        LOADING, NO_INTERNET, NO_NUMBERS, NO_CONV_CLIENT, PHONE_NOT_ENABLED, NA, SUCCESS, LOAD_EXCEPTION
    }

    public SMSdbInfo(XmppConnectionService xmppConn) {
        xmppConnectionService = xmppConn;
    }

    public void CallHistoryRequest(SmsProfile smsProfile, Account account, final HistoryCallback<Boolean> callback) {
        if (smsProfile == null || account == null){
            callback.onComplete(false, new ArrayList<>());
            return;
        }
        new Thread(() -> {
            String callHistory =
                    Endpoints.base() + EndPointConfig.CALL_HISTORY_URL;
            OkHttpClient client = getOkHttpClient();
            Map<String, String> formFields = new HashMap<>();
            formFields.put("number", smsProfile.getUnformattedNumber());
            formFields.put("limit", "500");
            formFields.put("username", account.getUsername());
            formFields.put("organization", account.getOrganization());
            Request request = null;
            try {
                request = OkHttpClientProvider.buildJwtFormRequestBuilder(callHistory, formFields)
                        .build();
            } catch (IOException e) {
                callback.onComplete(false, new ArrayList<>());
                return;
            }
            try (Response response = client.newCall(request).execute()) {
                String responseBody = response.body().string();
                Gson gson = new Gson();
                CallHistoryResponse callHistoryResponse;
                try {
                    callHistoryResponse = gson.fromJson(responseBody, CallHistoryResponse.class);
                } catch (JsonSyntaxException jsonSyntaxException) {
                    handleWebhookJsonSyntaxException("call history", responseBody, jsonSyntaxException);
                    callback.onComplete(false, new ArrayList<>());
                    return;
                }
                if (callHistoryResponse != null && callHistoryResponse.message.equals("success")) {
                    ArrayList<HistoryItem> historyItems1 = new ArrayList<>();
                    if (callHistoryResponse.incomingCalls != null) {
                        historyItems1.addAll(getHistoryLogListIncoming(smsProfile, callHistoryResponse.incomingCalls));
                    }
                    if (callHistoryResponse.outgoingCalls != null) {
                        historyItems1.addAll(getHistoryLogListOutgoing(smsProfile, callHistoryResponse.outgoingCalls));
                    }
                    xmppConnectionService.updateCallHistory(historyItems1);
                    callback.onComplete(true, historyItems1);

                } else {
                    callback.onComplete(false, new ArrayList<>());
                }

            } catch (Exception ex) {
                xmppConnectionService.checkConsoleStatus();
                callback.onComplete(false, new ArrayList<>());
            }
        }).start();

    }

    private ArrayList<HistoryItem> getHistoryLogListIncoming(SmsProfile sp, Object[] histInfoList) {
        ArrayList<HistoryItem> historyList = new ArrayList<>();
        for (Object o : histInfoList) {
            try {
                if (o instanceof LinkedTreeMap) {
                    LinkedTreeMap<String, String> treeMap = (LinkedTreeMap<String, String>) o;
                    HistoryItem prof = new HistoryItem(sp.getId(), treeMap);
                    if (!PhoneUtils.areSameNumber(prof.getRemote(),(sp.getUnformattedNumber())) && !prof.getFrom().contains("client") && !prof.getFrom().contains("sip") && !prof.getTo().contains("client") && !prof.getTo().contains("sip")) {
                        historyList.add(prof);
                    }
                }
            } catch (Exception e) {
            }
        }
        return historyList;

    }

    private ArrayList<HistoryItem> getHistoryLogListOutgoing(SmsProfile sp, Object[] histInfoList) {
        ArrayList<HistoryItem> historyList = new ArrayList<>();
        for (Object o : histInfoList) {
            try {
                if (o instanceof LinkedTreeMap) {
                    LinkedTreeMap<String, String> treeMap = (LinkedTreeMap<String, String>) o;
                    HistoryItem prof = new HistoryItem(sp.getId(), treeMap);
                    if (!PhoneUtils.areSameNumber(prof.getRemote(),(sp.getUnformattedNumber())) && !prof.getTo().contains("client") && !prof.getTo().contains("sip") && !prof.getFrom().contains("client") && !prof.getFrom().contains("sip")) {
                        historyList.add(prof);
                    }
                }
            } catch (Exception ignore) {
            }
        }
        return historyList;

    }

    public void getSelectedTwilioNumber(Account account, boolean forced) {
        if (account == null) {
            Log.w(Config.LOGTAG, "Unable to fetch selected Twilio numbers without an account");
            xmppConnectionService.markPhoneSyncComplete(PhoneSyncStatus.NA);
            return;
        }
        new Thread(() -> {
            ArrayList<SmsProfile> oldSp = new ArrayList<>(getExistingProfs());
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(xmppConnectionService);
            boolean smsEnabledPref = !prefs.contains("user_sms_enabled")
                    || prefs.getBoolean("user_sms_enabled", false);
            if (smsEnabledPref && !oldSp.isEmpty()) {
                dbProfs = new ArrayList<>(oldSp);
                setSelectedSmsProfile(null);
                OnSMSCompleteCallbackListener callbackListener = xmppConnectionService.getOnSMSCompleteCallbackListener();
                if (callbackListener != null) {
                    callbackListener.onSelectedTwilioNumbersLoaded(true, new ArrayList<>(oldSp), forced);
                }
            }
            String getUserTwilioNumListUrl =
                    Endpoints.base() + EndPointConfig.SELECTED_TWILIO_NUMBERS_URL;
            OkHttpClient client = getOkHttpClient();
            Map<String, String> formFields = new HashMap<>();
            formFields.put("organization", account.getOrganization());
            formFields.put("username", account.getUsername());
            Request request = null;
            try {
                request = OkHttpClientProvider.buildJwtFormRequestBuilder(getUserTwilioNumListUrl, formFields)
                        .build();
            } catch (IOException e) {
                {
                    try {
                        ArrayList<SmsProfile> fallbackProfiles = pollSmsProfilesFromDatabase();
                        if (fallbackProfiles.isEmpty() && !oldSp.isEmpty()) {
                            fallbackProfiles = oldSp;
                        }
                        dbPurchaseNum = prefs.getBoolean("purchase_num_enabled", dbPurchaseNum);
                        isSMSEnabled = prefs.getBoolean("user_sms_enabled", isSMSEnabled);
                        ArrayList<SmsProfile> fallbackCopy = new ArrayList<>(fallbackProfiles);
                        this.dbProfs = fallbackCopy;
                        setSelectedSmsProfile(null);
                        if (!fallbackCopy.isEmpty()) {
                            ArrayList<SmsProfile> profilesForRequests = new ArrayList<>(fallbackCopy);
                            new Thread(() -> {
                                for (SmsProfile sp : profilesForRequests) {
                                    listVoicemailRequest(account, sp);
                                    CallHistoryRequest(sp, account, callback);
                                }
                            }).start();
                        }
                        OnSMSCompleteCallbackListener callbackListener = xmppConnectionService.getOnSMSCompleteCallbackListener();
                        if (callbackListener != null) {
                            callbackListener.onSelectedTwilioNumbersLoaded(!fallbackCopy.isEmpty(), fallbackCopy, forced);
                        } else {
                            xmppConnectionService.getConversationsManager().init(account, forced);
                        }
                    } catch (Exception e2) {
                        xmppConnectionService.markPhoneSyncComplete(PhoneSyncStatus.NA);

                    }

                }
                return;
            }
            try (Response response = client.newCall(request).execute()) {
                String responseBody = response.body().string();
                Gson gson = new Gson();
                SMSdbInfo.SmsResponse twilio_info;
                try {
                    twilio_info = gson.fromJson(responseBody, SMSdbInfo.SmsResponse.class);
                } catch (JsonSyntaxException jsonSyntaxException) {
                    handleWebhookJsonSyntaxException("selected Twilio numbers", responseBody, jsonSyntaxException);
                    Log.e(Config.LOGTAG, "selected Twilio numbers response parse failure (responseBodyLength=" + (responseBody != null ? responseBody.length() : 0) + ")", jsonSyntaxException);

                    throw jsonSyntaxException;
                }
                if (twilio_info != null && twilio_info.data != null) {
                    if (twilio_info.data.allow_user_to_purchase_numbers != null) {
                        dbPurchaseNum = twilio_info.data.allow_user_to_purchase_numbers;
                        PreferenceManager.getDefaultSharedPreferences(xmppConnectionService).edit().putBoolean("purchase_num_enabled", dbPurchaseNum).apply();
                    } else {
                        PreferenceManager.getDefaultSharedPreferences(xmppConnectionService).edit().putBoolean("purchase_num_enabled", false).apply();
                    }
                    if (twilio_info.data.isSMSEnabled != null) {
                        isSMSEnabled = twilio_info.data.isSMSEnabled;
                        PreferenceManager.getDefaultSharedPreferences(xmppConnectionService).edit().putBoolean("user_sms_enabled", isSMSEnabled).apply();
                    } else {
                        PreferenceManager.getDefaultSharedPreferences(xmppConnectionService).edit().putBoolean("user_sms_enabled", false).apply();
                    }
                    dbProfs = getSmsProfileList(twilio_info.data.selected_twilionumber);
                    xmppConnectionService.replaceSmsProfileList(dbProfs, () -> {
                        new Thread(() -> {
                            for (SmsProfile sp : dbProfs) {
                                listVoicemailRequest(account, sp);
                                CallHistoryRequest(sp, account, callback);
                            }
                        }).start();
                        setSelectedSmsProfile(null);
                        if (!isSMSEnabled) {
                            xmppConnectionService.markPhoneSyncComplete(PhoneSyncStatus.PHONE_NOT_ENABLED);
                            return;
                        }
                        if (dbProfs.isEmpty()) {
                            xmppConnectionService.markPhoneSyncComplete(PhoneSyncStatus.NO_NUMBERS);
                            return;
                        }
                        OnSMSCompleteCallbackListener callbackListener = xmppConnectionService.getOnSMSCompleteCallbackListener();
                        if (callbackListener != null) {
                            callbackListener.onSelectedTwilioNumbersLoaded(true, dbProfs, forced);
                        } else {
                            xmppConnectionService.getConversationsManager().init(account, forced);
                        }
                    });

                } else {
                    OnSMSCompleteCallbackListener callbackListener = xmppConnectionService.getOnSMSCompleteCallbackListener();
                    if (callbackListener != null) {
                        callbackListener.onSelectedTwilioNumbersLoaded(false, oldSp, forced);
                    } else {
                        xmppConnectionService.getConversationsManager().init(account, forced);
                    }
                }
            } catch (Exception ex) {
                try {
                    ArrayList<SmsProfile> fallbackProfiles = pollSmsProfilesFromDatabase();
                    if (fallbackProfiles.isEmpty() && !oldSp.isEmpty()) {
                        fallbackProfiles = oldSp;
                    }
                    dbPurchaseNum = prefs.getBoolean("purchase_num_enabled", dbPurchaseNum);
                    isSMSEnabled = prefs.getBoolean("user_sms_enabled", isSMSEnabled);
                    ArrayList<SmsProfile> fallbackCopy = new ArrayList<>(fallbackProfiles);
                    this.dbProfs = fallbackCopy;
                    setSelectedSmsProfile(null);
                    if (!fallbackCopy.isEmpty()) {
                        ArrayList<SmsProfile> profilesForRequests = new ArrayList<>(fallbackCopy);
                        new Thread(() -> {
                            for (SmsProfile sp : profilesForRequests) {
                                listVoicemailRequest(account, sp);
                                CallHistoryRequest(sp, account, callback);
                            }
                        }).start();
                    }
                    OnSMSCompleteCallbackListener callbackListener = xmppConnectionService.getOnSMSCompleteCallbackListener();
                    if (callbackListener != null) {
                        callbackListener.onSelectedTwilioNumbersLoaded(!fallbackCopy.isEmpty(), fallbackCopy, forced);
                    } else {
                        xmppConnectionService.getConversationsManager().init(account, forced);
                    }
                } catch (Exception e) {
                    xmppConnectionService.markPhoneSyncComplete(PhoneSyncStatus.NA);

                }

            }
        }).start();
    }

    private ArrayList<SmsProfile> pollSmsProfilesFromDatabase() {
        ArrayList<SmsProfile> latestProfiles = new ArrayList<>();
        for (int attempt = 1; attempt <= SELECTED_TWILIO_DB_POLL_ATTEMPTS; attempt++) {
            ArrayList<SmsProfile> polledProfiles = xmppConnectionService.databaseBackend.getAllSmsProfiles();
            int profileCount = polledProfiles != null ? polledProfiles.size() : 0;
            if (polledProfiles != null) {
                latestProfiles = polledProfiles;
            }
            if (profileCount > 0) {
                break;
            }
            if (attempt < SELECTED_TWILIO_DB_POLL_ATTEMPTS) {
                try {
                    Thread.sleep(SELECTED_TWILIO_DB_POLL_DELAY_MS);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return latestProfiles != null ? latestProfiles : new ArrayList<>();
    }

    public SmsProfile setSelectedSmsProfile(SmsProfile sp) {
        if (sp != null) {
            this.selectedProfile = sp;
        } else if (selectedProfile != null && dbProfs.contains(selectedProfile)){
            return selectedProfile;
        }
        else if (!dbProfs.isEmpty()) {
            this.selectedProfile = dbProfs.get(0);
        } else {
            this.selectedProfile = null;
        }
        return selectedProfile;
    }

    private ArrayList<SmsProfile> getSmsProfileList(TwilioNumberInfo[] smsInfoList) {
        ArrayList<SmsProfile> smsProfList = new ArrayList<>();

        if (smsInfoList == null || smsInfoList.length == 0) {
            return smsProfList;
        }

        for (TwilioNumberInfo deviceinfo : smsInfoList) {
            try {
                if (deviceinfo != null) {
                    LinkedTreeMap<String, String> treeMap = new LinkedTreeMap<>();
                    treeMap.put("text", deviceinfo.text);
                    treeMap.put("id", deviceinfo.id);
                    treeMap.put("nickname", deviceinfo.nickname);
                    SmsProfile prof = new SmsProfile(treeMap);
                    smsProfList.add(prof);
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return smsProfList;
    }

    public SmsProfile getSelectedProfile() {
        if (selectedProfile != null){
            return selectedProfile;
        }
        else if (!dbProfs.isEmpty()) {
            this.selectedProfile = dbProfs.get(0);
        }
        return selectedProfile;
    }

    public int getReadVoicemails() {
        return readVoicemails;
    }

    public void setReadVoicemails(int readVoicemails) {
        this.readVoicemails = readVoicemails;
    }

    public int getNewVoicemails() {
        return newVoicemails;
    }

    public void setNewVoicemails(int newVoicemails) {
        this.newVoicemails = newVoicemails;
    }

    public ArrayList<HistoryItem> getHistoryLogListforProxy(SmsProfile smsProfile) {
        return xmppConnectionService.getHistoryLogListforProxy(smsProfile);
    }

    public ArrayList<HistoryItem> getHistoryLogListforProxyAndRemote(SmsProfile smsProfile, String remoteNumber) {
        return xmppConnectionService.getHistoryLogListforProxyAndRemote(smsProfile, remoteNumber);
    }

    public ArrayList<TwilioConversationEntity> getConversationsSelectedProfile() {
        if (selectedProfile == null) {
            return null;
        }
        String proxy = selectedProfile.getUnformattedNumber();
        if (proxy == null) {
            return null;
        }
        if (conv_list.keySet().contains(proxy)) {
            return conv_list.get(proxy);
        }
        return new ArrayList<>();
    }

    public synchronized void markConversationUnread(String profileNumber, String conversationSid) {
        if (conversationSid == null || conversationSid.isEmpty()) {
            return;
        }
        String profileKey = normalizeProfileNumber(profileNumber);
        if (profileKey == null) {
            return;
        }
        ArrayList<String> unreadConversations = unread_conv.get(profileKey);
        if (unreadConversations == null) {
            unreadConversations = new ArrayList<>();
            unread_conv.put(profileKey, unreadConversations);
        }
        if (!unreadConversations.contains(conversationSid)) {
            unreadConversations.add(conversationSid);
        }
        int count = unread_conv_count.getOrDefault(conversationSid, 0);
        unread_conv_count.put(conversationSid, count + 1);
    }

    public synchronized void clearConversationUnread(String conversationSid) {
        if (conversationSid == null || conversationSid.isEmpty()) {
            return;
        }
        unread_conv_count.remove(conversationSid);
        for (ArrayList<String> conversations : unread_conv.values()) {
            conversations.remove(conversationSid);
        }
    }

    public synchronized void setConversationUnreadCount(String profileNumber, String conversationSid, int unreadCount) {
        if (conversationSid == null || conversationSid.isEmpty()) {
            return;
        }
        String profileKey = normalizeProfileNumber(profileNumber);
        if (profileKey == null) {
            return;
        }
        if (unreadCount <= 0) {
            unread_conv_count.remove(conversationSid);
            ArrayList<String> conversations = unread_conv.get(profileKey);
            if (conversations != null) {
                conversations.remove(conversationSid);
                if (conversations.isEmpty()) {
                    unread_conv.remove(profileKey);
                }
            }
            return;
        }
        unread_conv_count.put(conversationSid, unreadCount);
        ArrayList<String> unreadConversations = unread_conv.get(profileKey);
        if (unreadConversations == null) {
            unreadConversations = new ArrayList<>();
            unread_conv.put(profileKey, unreadConversations);
        }
        if (!unreadConversations.contains(conversationSid)) {
            unreadConversations.add(conversationSid);
        }
    }

    public synchronized boolean isConversationUnread(String conversationSid) {
        return getConversationUnreadCount(conversationSid) > 0;
    }

    public synchronized int getConversationUnreadCount(String conversationSid) {
        if (conversationSid == null || conversationSid.isEmpty()) {
            return 0;
        }
        return unread_conv_count.getOrDefault(conversationSid, 0);
    }

    public synchronized boolean hasUnreadForProfile(String profileNumber) {
        String profileKey = normalizeProfileNumber(profileNumber);
        if (profileKey == null) {
            return false;
        }
        ArrayList<String> conversations = unread_conv.get(profileKey);
        return conversations != null && !conversations.isEmpty();
    }

    public TwilioConversationEntity getConversationBySid(String sid) {
        return conv_by_sid.get(sid);
    }

    public boolean containsProxy(String toPhoneNumber) {
        for (SmsProfile smsProfile : dbProfs) {
            if (PhoneUtils.areSameNumber(smsProfile.getUnformattedNumber(), toPhoneNumber)) {
                return true;
            }
        }
        return false;
    }

    public ArrayList<VoicemailRecord> getVoicemails(SmsProfile smsProxy) {
        return new ArrayList<>(xmppConnectionService.getVoicemailsForProxy(smsProxy));
    }

    public void listVoicemailRequest(Account account, SmsProfile sp) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String listVoicemailsUrl =
                        Endpoints.base() + EndPointConfig.LIST_VOICEMAILS_URL;
                OkHttpClient client = getOkHttpClient();
                Map<String, String> formFields = new HashMap<>();
                formFields.put("organization", account.getOrganization());
                formFields.put("username", account.getUsername());
                formFields.put("number", sp.getUnformattedNumber());
                Request request = null;
                try {
                    request = OkHttpClientProvider.buildJwtFormRequestBuilder(listVoicemailsUrl, formFields)
                            .build();
                } catch (IOException e) {
                    if (onVmCompleteCallbackListener != null){
                        onVmCompleteCallbackListener.onSelectedVoiceMailLoaded();
                    }
                    return;
                }
                client.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        if (onVmCompleteCallbackListener != null){
                            onVmCompleteCallbackListener.onSelectedVoiceMailLoaded();
                        }
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        String responseBody = response.body().string();

                        Gson gson = new Gson();
                        ListVoicemailResponse listVoicemailResponse;
                        try {
                            listVoicemailResponse = gson.fromJson(responseBody, ListVoicemailResponse.class);
                        } catch (JsonSyntaxException jsonSyntaxException) {
                            handleWebhookJsonSyntaxException("list voicemail", responseBody, jsonSyntaxException);
                            if (onVmCompleteCallbackListener != null){
                                onVmCompleteCallbackListener.onSelectedVoiceMailLoaded();
                            }
                            return;
                        }
                        ArrayList<VoicemailRecord> voicemailRecordsForProxy = new ArrayList<>();
                        if (listVoicemailResponse.message != null && listVoicemailResponse.message.equals("success")) {
                            voicemailRecordsForProxy.addAll(getVoicemailRecordList(listVoicemailResponse.recordings));
                        }
                        for (VoicemailRecord vm : voicemailRecordsForProxy) {
                            VoicemailStorageUtils.ensureFileForVoicemail(xmppConnectionService, vm, account);
                            if (vm.getStatus().label.equals("new") && !xmppConnectionService.doesVoicemailExist(vm)) {
                                xmppConnectionService.getNotificationService().notifyVoicemail(vm);
                            }
                            xmppConnectionService.addVoicemailRecord(vm);
                        }

                        int vmCount = xmppConnectionService.getUnreadVoicemailsCount(sp);
                        xmppConnectionService.setUnreadVoicemailCount(vmCount);
                        xmppConnectionService.updatePhoneUi();
                        xmppConnectionService.updateVoicemailUi();

                        if (onVmCompleteCallbackListener != null){
                            onVmCompleteCallbackListener.onSelectedVoiceMailLoaded();
                        }

                    }
                });
            }
        }).start();

    }

    private ArrayList<VoicemailRecord> getVoicemailRecordList(Object[] vmRecordList) {
        ArrayList<VoicemailRecord> voicemailRecordList = new ArrayList<>();

        for (Object vmInfo : vmRecordList) {
            try {
                if (vmInfo instanceof LinkedTreeMap) {
                    LinkedTreeMap<String, String> treeMap = (LinkedTreeMap<String, String>) vmInfo;
                    VoicemailRecord vm = new VoicemailRecord(treeMap);
                    voicemailRecordList.add(vm);
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return voicemailRecordList;
    }

    public ArrayList<SmsProfile> getExistingProfs() {
        dbProfs = xmppConnectionService.databaseBackend.getAllSmsProfiles();
        return dbProfs;
    }

    public void setDbPurchaseNum(boolean dbPurchaseNum) {
        this.dbPurchaseNum = dbPurchaseNum;
    }

    public void setDbProfs(ArrayList<SmsProfile> dbProfs) {
        this.dbProfs = dbProfs;
    }

    public void removeDbProfs(SmsProfile dbProf) {
        dbProfs.remove(dbProf);
    }
    public void addDbProfs(SmsProfile dbProf) {
        if (!dbProfs.contains(dbProf)) {
            dbProfs.add(dbProf);
        }
    }

    public SmsProfile getSMSProfilefromNumber(String number) {
        for (SmsProfile sp : dbProfs) {
            if (PhoneUtils.areSameNumber(number, sp.getUnformattedNumber())) {
                return sp;
            }
        }
        return null;
    }

    private String normalizeProfileNumber(String profileNumber) {
        if (profileNumber == null || profileNumber.isEmpty()) {
            return null;
        }
        String normalized = PhoneUtils.getStripPhoneNumberWithCountryCode(profileNumber);
        return normalized == null || normalized.isEmpty() ? profileNumber : normalized;
    }


    public Boolean getUserPurchasePermission() {
        return dbPurchaseNum;
    }


    public Boolean isSMSEnabled() {
        return isSMSEnabled;
    }

    public void notifyRefreshComplete(PhoneSyncStatus status) {
        OnSMSCompleteCallbackListener callbackListener = xmppConnectionService.getOnSMSCompleteCallbackListener();
        if (callbackListener != null) {
            callbackListener.onRefreshComplete(status);
        }
    }

    public void setOnVMCompleteCallbackListener(OnVmCompleteCallbackListener onVmCompleteCallbackListener){
        this.onVmCompleteCallbackListener = onVmCompleteCallbackListener;
    }


    public interface HistoryCallback<BOOLEAN> {
        void onComplete(boolean result, ArrayList<HistoryItem> historyItems);
    }

    public static class SmsResponse {
        @SerializedName("message")
        public String message;
        @SerializedName("data")
        public SMS_Twilio_info data;
    }

    public static class SMS_Twilio_info {
        @SerializedName("selected_twilionumber")
        public TwilioNumberInfo[] selected_twilionumber;
        @SerializedName("isSMSEnabled")
        public Boolean isSMSEnabled;
        @SerializedName("allow_user_to_purchase_numbers")
        public Boolean allow_user_to_purchase_numbers;
    }

    public static class TwilioNumberInfo {
        @SerializedName("text")
        public String text;
        @SerializedName("id")
        public String id;
        @SerializedName("nickname")
        public String nickname;
    }

    public static class UpdateVoicemailResponse {
        @SerializedName("message")
        public String message;
    }

    public static class UpdateAllVoicemailsResponse {
        @SerializedName("message")
        public String message;
    }

    public static class IsLiveResponse {
        @SerializedName("is-live")
        public boolean isLive;
    }

    private static class ListVoicemailResponse {
        @SerializedName("message")
        String message;
        @SerializedName("recordings")
        Object[] recordings;
    }

    public static class CallHistoryResponse {
        @SerializedName("incomingCalls")
        public Object[] incomingCalls;
        @SerializedName("outgoingCalls")
        public Object[] outgoingCalls;
        @SerializedName("message")
        public String message;
    }

    public static class CallHistoryDeleteResponse {
        @SerializedName("message")
        public String message;
    }

    private void handleWebhookJsonSyntaxException(String context, String responseBody, JsonSyntaxException exception) {
        Log.e(Config.LOGTAG, "Failed to parse " + context + " webhook response", exception);
        xmppConnectionService.checkConsoleStatus();
    }

}
