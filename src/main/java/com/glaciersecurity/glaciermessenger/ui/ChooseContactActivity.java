package com.glaciersecurity.glaciermessenger.ui;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.StringRes;
import androidx.appcompat.app.ActionBar;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Contact;
import com.glaciersecurity.glaciermessenger.entities.Conversation;
import com.glaciersecurity.glaciermessenger.entities.ListItem;
import com.glaciersecurity.glaciermessenger.entities.MucOptions;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;

public class ChooseContactActivity extends AbstractSearchableListItemActivity implements AdapterView.OnItemClickListener {
    public static final String EXTRA_TITLE_RES_ID = "extra_title_res_id";
    public static final String EXTRA_GROUP_CHAT_NAME = "extra_group_chat_name";
    public static final String EXTRA_GROUP_CHAT_ADDRESS = "extra_group_chat_address";
    public static final String EXTRA_SELECT_MULTIPLE = "extra_select_multiple";
    public static final String EXTRA_CONVERSATION = "extra_conversation";
    public static final String EXTRA_ALLOW_EMPTY_SELECTION = "extra_allow_empty_selection";
    public static final String EXTRA_CREATE_PUBLIC_CHANNEL = "extra_create_public_channel";
    private static final String EXTRA_FILTERED_CONTACTS = "extra_filtered_contacts";
    private final ArrayList<String> mActivatedAccounts = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private Set<String> filterContacts;

    private boolean startSearching = false;
    private boolean multiple = false;
    private boolean allowEmptySelection = false;
    private boolean createPublicChannelMode = false;
    private boolean creatingChannel = false;


    public static Intent create(Activity activity, Conversation conversation) {
        final Intent intent = new Intent(activity, ChooseContactActivity.class);
        List<String> contacts = new ArrayList<>();
        if (conversation.getMode() == Conversation.MODE_MULTI) {
            for (MucOptions.User user : conversation.getMucOptions().getUsers(false)) {
                Jid jid = user.getRealJid();
                if (jid != null) {
                    contacts.add(jid.asBareJid().toString());
                }
            }
        } else {
            contacts.add(conversation.getJid().asBareJid().toString());
        }
        intent.putExtra(EXTRA_FILTERED_CONTACTS, contacts.toArray(new String[contacts.size()]));
        intent.putExtra(EXTRA_CONVERSATION, conversation.getUuid());
        intent.putExtra(EXTRA_SELECT_MULTIPLE, true);
        intent.putExtra(EXTRA_ACCOUNT, conversation.getAccount().getJid().asBareJid().toEscapedString());
        return intent;
    }

    public static List<Jid> extractJabberIds(Intent result) {
        List<Jid> jabberIds = new ArrayList<>();
        try {
            if (result.getBooleanExtra(EXTRA_SELECT_MULTIPLE, false)) {
                String[] toAdd = result.getStringArrayExtra("contacts");
                for (String item : toAdd) {
                    jabberIds.add(Jid.of(item));
                }
            } else {
                jabberIds.add(Jid.of(result.getStringExtra("contact")));
            }
            return jabberIds;
        } catch (IllegalArgumentException e) {
            return jabberIds;
        }
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        filterContacts = new HashSet<>();
        if (savedInstanceState != null) {
            String[] selectedContacts = savedInstanceState.getStringArray("selected_contacts");
            if (selectedContacts != null) {
                selected.clear();
                selected.addAll(Arrays.asList(selectedContacts));
            }
        }

        String[] contacts = getIntent().getStringArrayExtra(EXTRA_FILTERED_CONTACTS);
        if (contacts != null) {
            Collections.addAll(filterContacts, contacts);
        }

        Intent intent = getIntent();

        multiple = intent.getBooleanExtra(EXTRA_SELECT_MULTIPLE, false);
        allowEmptySelection = intent.getBooleanExtra(EXTRA_ALLOW_EMPTY_SELECTION, false);
        createPublicChannelMode = intent.getBooleanExtra(EXTRA_CREATE_PUBLIC_CHANNEL, false);
        if (multiple) {
            // Use the non-modal multi-choice mode so tapping a contact simply
            // highlights the row (via the activated state) instead of launching
            // a contextual action bar, which appeared as a separate "second"
            // screen over the list.
            getListView().setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        }

        getListView().setOnItemClickListener(this);
        this.binding.fab.setOnClickListener(this::onFabClicked);
        binding.fab.setImageResource(R.drawable.ic_navigate_next_24dp);

        final SharedPreferences preferences = getPreferences();
        this.startSearching = intent.getBooleanExtra("direct_search", false) && preferences.getBoolean("start_searching", getResources().getBoolean(R.bool.start_searching));

        if (multiple) {
            updateFab();
        }
    }

    private void onFabClicked(View v) {
        if (creatingChannel) {
            return;
        }
        // FloatingActionButton.hide() animates out, so a tap that lands between
        // deselecting the last contact and the animation finishing still arrives
        // here. Mirror updateFab()'s hide condition rather than returning RESULT_OK
        // with an empty contact list.
        if (selected.isEmpty() && !allowEmptySelection) {
            return;
        }
        if (createPublicChannelMode) {
            createPublicChannel();
        } else {
            submitSelection();
        }
    }

    private void createPublicChannel() {
        if (xmppConnectionService == null) {
            return;
        }
        final Account account = extractAccount(getIntent());
        final String name = getIntent().getStringExtra(EXTRA_GROUP_CHAT_NAME);
        final String addressExtra = getIntent().getStringExtra(EXTRA_GROUP_CHAT_ADDRESS);
        if (account == null || addressExtra == null) {
            return;
        }
        final Jid address;
        try {
            address = Jid.ofEscaped(addressExtra);
        } catch (final IllegalArgumentException e) {
            return;
        }
        final List<Jid> invitees = new ArrayList<>();
        for (final String jid : selected) {
            try {
                invitees.add(Jid.of(jid));
            } catch (final IllegalArgumentException ignored) {
            }
        }
        creatingChannel = true;
        binding.fab.hide();
        binding.creatingChannelProgress.setVisibility(View.VISIBLE);
        mToast = Toast.makeText(this, R.string.creating_channel, Toast.LENGTH_LONG);
        mToast.show();
        xmppConnectionService.createPublicChannel(
                account,
                name,
                address,
                invitees,
                new UiCallback<Conversation>() {
                    @Override
                    public void success(final Conversation conversation) {
                        runOnUiThread(
                                () -> {
                                    hideToast();
                                    switchToConversation(conversation);
                                    finish();
                                });
                    }

                    @Override
                    public void error(final int errorCode) {
                        runOnUiThread(
                                () -> {
                                    replaceToast(getString(errorCode));
                                    creatingChannel = false;
                                    binding.creatingChannelProgress.setVisibility(View.GONE);
                                    updateFab();
                                });
                    }

                    @Override
                    public void userInputRequired(
                            final PendingIntent pi) {}
                });
    }

    private void submitSelection() {
        final Intent request = getIntent();
        final Intent data = new Intent();
        data.putExtra("contacts", getSelectedContactJids());
        data.putExtra(EXTRA_SELECT_MULTIPLE, true);
        data.putExtra(EXTRA_ACCOUNT, request.getStringExtra(EXTRA_ACCOUNT));
        copy(request, data);
        setResult(RESULT_OK, data);
        finish();
    }

    private static void copy(Intent from, Intent to) {
        to.putExtra(EXTRA_CONVERSATION, from.getStringExtra(EXTRA_CONVERSATION));
        to.putExtra(EXTRA_GROUP_CHAT_NAME, from.getStringExtra(EXTRA_GROUP_CHAT_NAME));
        to.putExtra(EXTRA_GROUP_CHAT_ADDRESS, from.getStringExtra(EXTRA_GROUP_CHAT_ADDRESS));
    }

    private void updateFab() {
        if (creatingChannel) {
            return;
        }
        if (selected.isEmpty() && !allowEmptySelection) {
            binding.fab.hide();
        } else {
            binding.fab.setImageResource(R.drawable.ic_navigate_next_24dp);
            binding.fab.show();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        ActionBar bar = getSupportActionBar();
        if (bar != null) {
            try {
                bar.setTitle(getTitleFromIntent());
            } catch (Exception e) {
                bar.setTitle(R.string.title_activity_choose_contact);
            }
        }
    }

    public @StringRes
    int getTitleFromIntent() {
        final Intent intent = getIntent();
        boolean multiple = intent != null && intent.getBooleanExtra(EXTRA_SELECT_MULTIPLE, false);
        @StringRes int fallback = multiple ? R.string.title_activity_choose_contacts : R.string.title_activity_choose_contact;
        return intent != null ? intent.getIntExtra(EXTRA_TITLE_RES_ID, fallback) : fallback;
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        super.onCreateOptionsMenu(menu);
        MenuItem mMenuSearchView = menu.findItem(R.id.action_search);
        if (startSearching) {
            mMenuSearchView.expandActionView();
        }
        return true;
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        savedInstanceState.putStringArray("selected_contacts", getSelectedContactJids());
        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        if (multiple) {
            return false;
        } else {
            List<ListItem> items = getListItems();
            if (items.size() == 1) {
                onListItemClicked(items.get(0));
                return true;
            }
            return false;
        }
    }

    @Override
    protected void filterContacts(final String needle) {
        if (xmppConnectionService == null) {
            replaceListItems(Collections.emptyList());
            getListItemAdapter().notifyDataSetChanged();
            return;
        }
        final List<ListItem> items = new ArrayList<>();
        for (final Account account : xmppConnectionService.getAccounts()) {
            if (account.isEnabled()) {
                for (final Contact contact : account.getRoster().getContacts()) {
                    if (contact.showInContactList() &&
                            !filterContacts.contains(contact.getJid().asBareJid().toString())
                            && contact.match(this, needle)) {
                        items.add(contact);
                    }
                }
            }
        }
        replaceListItems(items);
        getListItemAdapter().notifyDataSetChanged();
        reapplyChecks();
    }

    // The ListView tracks checked state by row position, so it goes stale when
    // the filtered list is rebuilt. Clear it and re-check rows by matching the
    // selected JIDs to the current positions.
    private void reapplyChecks() {
        if (!multiple) {
            return;
        }
        final ListView listView = getListView();
        listView.clearChoices();
        final List<ListItem> items = getListItems();
        for (int i = 0; i < items.size(); i++) {
            final Jid jid = items.get(i).getJid();
            if (jid != null && selected.contains(jid.toString())) {
                listView.setItemChecked(i, true);
            }
        }
        getListItemAdapter().notifyDataSetChanged();
    }

    private String[] getSelectedContactJids() {
        return selected.toArray(new String[0]);
    }

    @Override
    public void refreshUiReal() {
        //nothing to do. This Activity doesn't implement any listeners
    }

    @Override
    protected void onBackendConnected() {
        filterContacts();
        this.mActivatedAccounts.clear();
        for (final Account account : xmppConnectionService.getAccounts()) {
            if (account.isEnabled()) {
                this.mActivatedAccounts.add(account.getJid().asBareJid().toEscapedString());
            }
        }
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (creatingChannel) {
            return;
        }
        if (multiple) {
            // Derive the new state from the selected set (keyed by JID) rather
            // than the ListView's position-based check state, which can be
            // stale after the filtered list is repopulated. Then force the row
            // check to match so the highlight is correct.
            final ListItem item = getListItems().get(position);
            final Jid jid = item.getJid();
            if (jid == null) {
                return;
            }
            final String jidString = jid.toString();
            final boolean nowSelected = !selected.contains(jidString);
            if (nowSelected) {
                selected.add(jidString);
            } else {
                selected.remove(jidString);
            }
            getListView().setItemChecked(position, nowSelected);
            getListView().playSoundEffect(SoundEffectConstants.CLICK);
            updateFab();
            return;
        }
        final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(getSearchEditText().getWindowToken(), InputMethodManager.HIDE_IMPLICIT_ONLY);
        final ListItem mListItem = getListItems().get(position);
        onListItemClicked(mListItem);
    }

    private void onListItemClicked(ListItem item) {
        final Intent request = getIntent();
        final Intent data = new Intent();
        data.putExtra("contact", item.getJid().toString());
        String account = request.getStringExtra(EXTRA_ACCOUNT);
        if (account == null && item instanceof Contact) {
            account = ((Contact) item).getAccount().getJid().asBareJid().toEscapedString();
        }
        data.putExtra(EXTRA_ACCOUNT, account);
        data.putExtra(EXTRA_SELECT_MULTIPLE, false);
        copy(request, data);
        setResult(RESULT_OK, data);
        finish();
    }
}
