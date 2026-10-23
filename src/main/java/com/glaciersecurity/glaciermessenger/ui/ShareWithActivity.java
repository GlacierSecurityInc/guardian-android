package com.glaciersecurity.glaciermessenger.ui;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ActivityShareWithBinding;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Conversation;
import com.glaciersecurity.glaciermessenger.services.ConnectivityReceiver;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.ui.adapter.ConnectionToolbarAdapter;
import com.glaciersecurity.glaciermessenger.ui.adapter.ConversationAdapter;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;

import java.util.ArrayList;
import java.util.List;

public class ShareWithActivity extends XmppActivity
        implements XmppConnectionService.OnConversationUpdate, ConnectivityReceiver.ConnectivityReceiverListener {

    private static final int REQUEST_STORAGE_PERMISSION = 0x733f32;
    private static final int REQUEST_START_NEW_CONVERSATION = 0x7f3f;

    private Conversation mPendingConversation = null;
    private ConversationAdapter mAdapter;
    private final List<Conversation> mConversations = new ArrayList<>();
    private ActivityShareWithBinding binding;
    private ConnectionToolbarAdapter connectionToolbarAdapter;
    private ConnectivityReceiver connectivityReceiver;

    @Override
    public void onConversationUpdate() {
        refreshUi();
    }

    private static class Share {
        public String type;
        ArrayList<Uri> uris = new ArrayList<>();
        public String account;
        public String contact;
        public String text;
        public boolean asQuote = false;
    }

    private Share share;

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (requestCode == REQUEST_STORAGE_PERMISSION && mPendingConversation != null) {
                share(mPendingConversation);
            }
        } else {
            Toast.makeText(
                    this,
                    getString(R.string.no_storage_permission, getString(R.string.app_name)),
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = DataBindingUtil.setContentView(this, R.layout.activity_share_with);
        setSupportActionBar(binding.toolbar);
        final var actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(false);
            actionBar.setHomeButtonEnabled(false);
        }
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setTitle(R.string.title_activity_share_with);
        connectivityReceiver = new ConnectivityReceiver(this);
        connectionToolbarAdapter = new ConnectionToolbarAdapter(this, binding.offlineLayout, xmppConnectionService);
        binding.offlineLayout.setOnClickListener(mRefreshNetworkClickListener);
        connectionToolbarAdapter.updateOfflineStatusBar();

        mAdapter = new ConversationAdapter(this, this.mConversations);
        binding.chooseConversationList.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        binding.chooseConversationList.setAdapter(mAdapter);
        mAdapter.setConversationClickListener(conversation -> share(conversation));
        this.share = new Share();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.share_with, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == R.id.action_add) {
            final Intent intent = new Intent(getApplicationContext(), ChooseContactActivity.class);
            intent.putExtra("direct_search", true);
            startActivityForResult(intent, REQUEST_START_NEW_CONVERSATION);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, connectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED);
        final Intent intent = getIntent();
        if (intent == null) {
            return;
        }

        final String type = intent.getType();
        final String action = intent.getAction();
        final Uri data = intent.getData();

        // External apps sharing files into this activity is the intended use-case
        // (the manifest registers */* intent filters for ACTION_SEND / ACTION_SEND_MULTIPLE).
        // Android automatically grants the receiving activity temporary read permission
        // via FLAG_GRANT_READ_URI_PERMISSION when the sender includes it. Any actual
        // permission failure is caught as a SecurityException in share(Conversation)
        // below and surfaced to the user with the appropriate error toast.

        if (Intent.ACTION_SEND.equals(action)) {
            final String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            final Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            final boolean asQuote =
                    intent.getBooleanExtra(ConversationsActivity.EXTRA_AS_QUOTE, false);

            if (data != null && "geo".equals(data.getScheme())) {
                this.share.uris.clear();
                this.share.uris.add(data);
            } else if (type != null && uri != null) {
                this.share.uris.clear();
                this.share.uris.add(uri);
                this.share.type = type;
            } else {
                this.share.text = text;
                this.share.asQuote = asQuote;
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            final ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            this.share.uris = uris == null ? new ArrayList<>() : uris;
        }

        if (xmppConnectionServiceBound) {
            xmppConnectionService.populateWithOrderedConversations(
                    mConversations, this.share.uris.isEmpty(), false);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        try {
            unregisterReceiver(connectivityReceiver);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onBackendConnected() {
        if (connectionToolbarAdapter != null) {
            connectionToolbarAdapter.updateOfflineStatusBar();
        }
        if (xmppConnectionServiceBound
                && share != null
                && share.contact != null
                && share.account != null) {
            share();
            return;
        }
        refreshUiReal();
    }

    private void share() {
        final Conversation conversation;
        Account account;
        try {
            account = xmppConnectionService.findAccountByJid(Jid.ofEscaped(share.account));
        } catch (final IllegalArgumentException e) {
            account = null;
        }
        if (account == null) {
            return;
        }
        try {
            conversation = xmppConnectionService.findOrCreateConversation(
                    account, Jid.of(share.contact), false, true);
        } catch (final IllegalArgumentException e) {
            return;
        }
        share(conversation);
    }

    private void share(final Conversation conversation) {
        if (!share.uris.isEmpty() && !hasStoragePermission()) {
            mPendingConversation = conversation;
            return;
        }
        final Intent intent = new Intent(this, ConversationsActivity.class);
        intent.putExtra(ConversationsActivity.EXTRA_CONVERSATION, conversation.getUuid());
        if (!share.uris.isEmpty()) {
            intent.setAction(Intent.ACTION_SEND_MULTIPLE);
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, share.uris);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (share.type != null) {
                intent.putExtra(ConversationsActivity.EXTRA_TYPE, share.type);
            }
        } else if (share.text != null) {
            intent.setAction(ConversationsActivity.ACTION_VIEW_CONVERSATION);
            intent.putExtra(Intent.EXTRA_TEXT, share.text);
            intent.putExtra(ConversationsActivity.EXTRA_AS_QUOTE, share.asQuote);
        }
        try {
            startActivity(intent);
        } catch (SecurityException e) {
            Toast.makeText(
                    this,
                    R.string.sharing_application_not_grant_permission,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        finish();
    }

    @Override
    public void refreshUiReal() {
        xmppConnectionService.populateWithOrderedConversations(
                mConversations, this.share != null && this.share.uris.isEmpty(), false);
        mAdapter.notifyDataSetChanged();
    }

    @Override
    public void onNetworkConnectionChanged() {
        if (connectionToolbarAdapter != null) {
            connectionToolbarAdapter.checkNetworkConnection();
        }
    }

    private final android.view.View.OnClickListener mRefreshNetworkClickListener = v -> connectionToolbarAdapter.onClick();
}
