package com.glaciersecurity.glaciermessenger.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ActivityChannelDiscoveryBinding;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Bookmark;
import com.glaciersecurity.glaciermessenger.entities.Conversation;
import com.glaciersecurity.glaciermessenger.entities.Room;
import com.glaciersecurity.glaciermessenger.services.ChannelDiscoveryService;
import com.glaciersecurity.glaciermessenger.services.QuickConversationsService;import com.glaciersecurity.glaciermessenger.ui.adapter.ChannelSearchResultAdapter;
import com.glaciersecurity.glaciermessenger.ui.util.PendingItem;
import com.glaciersecurity.glaciermessenger.ui.util.SoftKeyboardUtils;
import com.glaciersecurity.glaciermessenger.utils.AccountUtils;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;

public class ChannelDiscoveryActivity extends XmppActivity implements MenuItem.OnActionExpandListener, TextView.OnEditorActionListener, ChannelDiscoveryService.OnChannelSearchResultsFound, ChannelSearchResultAdapter.OnChannelSearchResultSelected {

    private final ChannelSearchResultAdapter adapter = new ChannelSearchResultAdapter();
    private final PendingItem<String> mInitialSearchValue = new PendingItem<>();
    private ActivityChannelDiscoveryBinding binding;
    private MenuItem mMenuSearchView;
    private EditText mSearchEditText;

    private ChannelDiscoveryService.Method method = ChannelDiscoveryService.Method.LOCAL_SERVER;

    @Override
    protected void refreshUiReal() {

    }

    @Override
    protected void onBackendConnected() {
            final String query;
            if (mMenuSearchView != null && mMenuSearchView.isActionViewExpanded()) {
                query = mSearchEditText.getText().toString();
            } else {
                query = mInitialSearchValue.peek();
            }
            toggleLoadingScreen();
            xmppConnectionService.discoverChannels(query, this.method, this);

    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_channel_discovery);
        setSupportActionBar(binding.toolbar);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        configureActionBar(getSupportActionBar(), true);
        binding.list.setAdapter(this.adapter);
        this.adapter.setOnChannelSearchResultSelectedListener(this);

        final String search = savedInstanceState == null ? null : savedInstanceState.getString("search");
        if (search != null) {
            mInitialSearchValue.push(search);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.channel_discovery_activity, menu);
        AccountUtils.showHideMenuItems(menu);
        mMenuSearchView = menu.findItem(R.id.action_search);
        final View mSearchView = mMenuSearchView.getActionView();
        mSearchEditText = mSearchView.findViewById(R.id.search_field);
        mSearchEditText.setHint(R.string.search_channels);
        final String initialSearchValue = mInitialSearchValue.pop();
        if (initialSearchValue != null) {
            mMenuSearchView.expandActionView();
            mSearchEditText.append(initialSearchValue);
            mSearchEditText.requestFocus();
            if (xmppConnectionService != null) {
                xmppConnectionService.discoverChannels(initialSearchValue, this.method, this);
            }
        }
        mSearchEditText.setOnEditorActionListener(this);
        mMenuSearchView.setOnActionExpandListener(this);
        return true;
    }

    @Override
    public boolean onMenuItemActionExpand(@NonNull MenuItem item) {
        mSearchEditText.post(() -> {
            mSearchEditText.requestFocus();
            final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(mSearchEditText, InputMethodManager.SHOW_IMPLICIT);
        });
        return true;
    }

    @Override
    public boolean onMenuItemActionCollapse(@NonNull MenuItem item) {
        final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(mSearchEditText.getWindowToken(), InputMethodManager.HIDE_IMPLICIT_ONLY);
        mSearchEditText.setText("");
        toggleLoadingScreen();
        xmppConnectionService.discoverChannels(null, this.method, this);
        return true;
    }

    private void toggleLoadingScreen() {
        adapter.submitList(Collections.emptyList());
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.list.setBackgroundColor(MaterialColors.getColor(binding.list, com.google.android.material.R.attr.colorSurface));
    }

    @Override
    public void onStart() {
        super.onStart();
        this.method = ChannelDiscoveryService.Method.LOCAL_SERVER;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        if (mMenuSearchView != null && mMenuSearchView.isActionViewExpanded()) {
            savedInstanceState.putString("search", mSearchEditText != null ? mSearchEditText.getText().toString() : null);
        }
        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
            toggleLoadingScreen();
            SoftKeyboardUtils.hideSoftKeyboard(this);
            xmppConnectionService.discoverChannels(v.getText().toString(), this.method, this);
        return true;
    }

    @Override
    public void onChannelSearchResultsFound(final List<Room> results) {
        runOnUiThread(() -> {
            adapter.submitList(results);
            binding.progressBar.setVisibility(View.GONE);
            if (results.isEmpty()) {
                binding.list.setBackground(ContextCompat.getDrawable(this,R.drawable.background_no_results));
            } else {
                binding.list.setBackgroundColor(MaterialColors.getColor(binding.list, com.google.android.material.R.attr.colorSurface));
            }
        });

    }

    @Override
    public void onChannelSearchResult(final Room result) {
        final List<String> accounts = AccountUtils.getEnabledAccounts(xmppConnectionService);
        if (accounts.size() == 1) {
            joinChannelSearchResult(accounts.get(0), result);
        } else if (accounts.isEmpty()) {
            Toast.makeText(this, R.string.please_enable_an_account, Toast.LENGTH_LONG).show();
        } else {
            final AtomicReference<String> account = new AtomicReference<>(accounts.get(0));
            final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
            builder.setTitle(R.string.choose_account);
            builder.setSingleChoiceItems(accounts.toArray(new CharSequence[0]), 0, (dialog, which) -> account.set(accounts.get(which)));
            builder.setPositiveButton(R.string.join, (dialog, which) -> joinChannelSearchResult(account.get(), result));
            builder.setNegativeButton(R.string.cancel, null);
            builder.create().show();
        }

    }

    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        final Room room = adapter.getCurrent();
        if (room == null) {
            return false;
        }
        final int itemId = item.getItemId();
        if (itemId == R.id.share_with) {
            StartConversationActivity.shareAsChannel(this, room.address);
            return true;
        } else if (itemId == R.id.open_join_dialog) {
            final Intent intent = new Intent(this, StartConversationActivity.class);
            intent.setAction(Intent.ACTION_VIEW);
            intent.putExtra("force_dialog", true);
            intent.setData(Uri.parse(String.format("xmpp:%s?join", room.address)));
            startActivity(intent);
            return true;
        } else {
            return false;
        }
    }

    public void joinChannelSearchResult(final String selectedAccount, final Room result) {
        final Jid jid = Jid.ofEscaped(selectedAccount);
        final Account account = xmppConnectionService.findAccountByJid(jid);
        final Conversation conversation =
                xmppConnectionService.findOrCreateConversation(
                        account, result.getRoom(), true, true, true);
        final var existingBookmark = conversation.getBookmark();
        if (existingBookmark == null) {
            final var bookmark = new Bookmark(account, conversation.getJid().asBareJid());
            bookmark.setAutojoin(true);
            xmppConnectionService.createBookmark(account, bookmark);
        } else {
            if (!existingBookmark.autojoin()) {
                existingBookmark.setAutojoin(true);
                xmppConnectionService.createBookmark(account, existingBookmark);
            }
        }
        switchToConversation(conversation);
    }
}
