package com.glaciersecurity.glaciermessenger.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.view.View;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ExternalContactDetailsBinding;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.HistoryItem;
import com.glaciersecurity.glaciermessenger.entities.SmsProfile;
import com.glaciersecurity.glaciermessenger.entities.TwilioConversationEntity;
import com.glaciersecurity.glaciermessenger.ui.adapter.ExternalContactHistoryAdapter;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.utils.NativeContactsUtils;
import com.glaciersecurity.glaciermessenger.utils.PhoneUtils;
import com.glaciersecurity.glaciermessenger.utils.SMSdbInfo;


import java.util.ArrayList;


public class ExternalContactDetailsActivity extends XmppActivity{

    private ExternalContactDetailsBinding binding;
    private ExternalContactHistoryAdapter adapter;
    private String contactNumber;
    private String normalizedContactNumber;
    private SmsProfile smsProfile;
    private Account account;
    private ConversationsManager conversationsManager;
    private boolean isHistoryRequestRunning = false;

    @Override
    protected void refreshUiReal() {

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.external_contact_details);
        this.binding = DataBindingUtil.setContentView(this, R.layout.external_contact_details);

        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());

        if (getIntent().hasExtra("toNumber")) {
            contactNumber = getIntent().getStringExtra("toNumber");
            normalizedContactNumber = PhoneUtils.getStripPhoneNumberWithCountryCode(contactNumber);
        }

        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        layoutManager.setReverseLayout(true);
        binding.callList.setLayoutManager(layoutManager);
        adapter = new ExternalContactHistoryAdapter(new ArrayList<>());
        binding.callList.setAdapter(adapter);
        String contactName = NativeContactsUtils.getContactNameIfExists(this,contactNumber);
        binding.contactDisplayName.setText(contactName);
        binding.phoneNumber.setText(PhoneUtils.reformatNumber(contactNumber));
        if(PhoneUtils.areSameNumber(contactName, contactNumber)){
            binding.phoneNumber.setVisibility(View.GONE);
        } else {
            binding.phoneNumber.setVisibility(View.VISIBLE);
        }
        binding.addToContacts.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (ContextCompat.checkSelfPermission(ExternalContactDetailsActivity.this, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions( new String[]{Manifest.permission.WRITE_CONTACTS}, 1);
                } else {
                    addContact();
                }
            }
        });

        binding.startCallButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (xmppConnectionService == null || smsProfile == null) {
                    return;
                }
                if (contactNumber != null && !contactNumber.isEmpty()) {
                    String toPhoneNumber = PhoneUtils.getStripPhoneNumber(contactNumber);
                    if (!xmppConnectionService.getSmsInfo().containsProxy(toPhoneNumber)) {
                        Intent callActivity = new Intent(ExternalContactDetailsActivity.this, ExternalCallActivity.class);
                        callActivity.setAction(Config.ACTION_OUTGOING_CALL);
                        callActivity.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                        callActivity.putExtra("toNumber", toPhoneNumber);
                        callActivity.putExtra(Config.TO_PROXY, smsProfile.getUnformattedNumber());

                        startActivity(callActivity);
                    } else {
                        runOnUiThread(() -> Toast.makeText(ExternalContactDetailsActivity.this, R.string.no_self_calls, Toast.LENGTH_SHORT).show());
                    }
                }
            }
        });

        binding.startSmsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TwilioConversationEntity conv = xmppConnectionService.getConversationsBetweenContactAndProxy(contactNumber, smsProfile);
                Intent intent = new Intent(ExternalContactDetailsActivity.this, SmsConvActivity.class);
                if (conv != null) {
                    startActivity(intent.putExtra("conv_sid", conv.getConv_sid()).putExtra("friendly_name", conv.getFriendlyName()));
                } else {
                    startActivity(intent.putExtra("friendly_name", PhoneUtils.getStripPhoneNumberWithCountryCode(contactNumber)));
                }
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Permission granted, proceed with adding contact
            addContact();
        } else {
            // Permission denied
            Toast.makeText(this, R.string.auth_permission_denied, Toast.LENGTH_SHORT).show();
        }
    }

    public void addContact() {
            Intent intent = new Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI);
            intent.putExtra(ContactsContract.Intents.Insert.NAME, "");
            intent.putExtra(ContactsContract.Intents.Insert.PHONE, contactNumber);

            try {
                startActivity(intent);
            } catch (Exception e) {
                Log.e(Config.LOGTAG, "Error adding contact" + e.getMessage(), e);
            }
    }

    @Override
    public void onBackendConnected() {
        smsProfile = xmppConnectionService.getSmsInfo().getSelectedProfile();
        account = xmppConnectionService.getFirstAccount();
        setToolbarForProxy();


        conversationsManager = xmppConnectionService.getConversationsManager();
        if (smsProfile != null && contactNumber != null && smsProfile.getUnformattedNumber() != null) {
            loadHistoryFromDatabase();
            ContactCallHistoryRequest();
        }
    }

    private void setToolbarForProxy() {
        if (smsProfile != null) {
            if (smsProfile.getNickname() != null && !smsProfile.getNickname().isEmpty()) {
                binding.toolbar.setTitle(smsProfile.getNickname());
            } else {
                binding.toolbar.setTitle(smsProfile.getFormattedNumber());
            }
            binding.toolbar.setBackgroundColor(smsProfile.getColor());
        }
    }

    private void ContactCallHistoryRequest() {
        if (xmppConnectionService == null || smsProfile == null || account == null || contactNumber == null || isHistoryRequestRunning) {
            return;
        }
        isHistoryRequestRunning = true;
        runOnUiThread(() -> showLoading(true));
        xmppConnectionService.getSmsInfo().CallHistoryRequest(smsProfile, account, new SMSdbInfo.HistoryCallback<Boolean>() {
            @Override
            public void onComplete(boolean result, ArrayList<HistoryItem> arrayList) {
                isHistoryRequestRunning = false;
                runOnUiThread(() -> {
                    showLoading(false);
                    if (result) {
                        ArrayList<HistoryItem> historyItems = filterHistoryItems(arrayList);
                        adapter.setHistoryLogList(historyItems);
                    }
                    checkEmpty();
                });
            }
        });

    }

    private void loadHistoryFromDatabase() {
        if (xmppConnectionService == null || smsProfile == null || contactNumber == null) {
            return;
        }
        ArrayList<HistoryItem> historyItems = new ArrayList<>();
        if (normalizedContactNumber != null) {
            ArrayList<HistoryItem> byRemote = xmppConnectionService.getHistoryLogListforProxyAndRemote(smsProfile, normalizedContactNumber);
            historyItems.addAll(filterHistoryItems(byRemote));
        }
        if (historyItems.isEmpty()) {
            ArrayList<HistoryItem> proxyItems = xmppConnectionService.getHistoryLogListforProxy(smsProfile);
            historyItems.addAll(filterHistoryItems(proxyItems));
        }
        adapter.setHistoryLogList(historyItems);
        checkEmpty();
    }

    private ArrayList<HistoryItem> filterHistoryItems(ArrayList<HistoryItem> items) {
        ArrayList<HistoryItem> filteredItems = new ArrayList<>();
        if (items == null) {
            return filteredItems;
        }
        for (HistoryItem item : items) {
            if (matchesCurrentContact(item)) {
                filteredItems.add(item);
            }
        }
        return filteredItems;
    }

    private boolean matchesCurrentContact(HistoryItem item) {
        if (item == null) {
            return false;
        }
        if (smsProfile != null && smsProfile.getId() != null) {
            if (item.getProxySid() == null || !smsProfile.getId().equals(item.getProxySid())) {
                return false;
            }
        }
        return PhoneUtils.areSameNumber(item.getRemote(), contactNumber)
                || PhoneUtils.areSameNumber(item.getRemote(), normalizedContactNumber)
                || PhoneUtils.areSameNumber(item.getFrom(), contactNumber)
                || PhoneUtils.areSameNumber(item.getFrom(), normalizedContactNumber)
                || PhoneUtils.areSameNumber(item.getTo(), contactNumber)
                || PhoneUtils.areSameNumber(item.getTo(), normalizedContactNumber);
    }

    private void showLoading(boolean show) {
        if (binding.loadingBar == null) {
            return;
        }
        binding.loadingBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void checkEmpty() {
        if (!adapter.historyLogList.isEmpty()) {
            binding.callHistoryTitle.setVisibility(View.VISIBLE);
        } else {
            binding.callHistoryTitle.setVisibility(View.GONE);
        }
    }

}