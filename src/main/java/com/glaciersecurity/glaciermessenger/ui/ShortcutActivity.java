package com.glaciersecurity.glaciermessenger.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.inputmethod.InputMethodManager;

import androidx.appcompat.app.ActionBar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Contact;
import com.glaciersecurity.glaciermessenger.entities.ListItem;

public class ShortcutActivity extends AbstractSearchableListItemActivity {

    private static final List<String> BLACKLISTED_ACTIVITIES = Arrays.asList("com.teslacoilsw.launcher.ChooseActionIntentActivity");

    @Override
    protected void refreshUiReal() {

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getListView().setOnItemClickListener((parent, view, position, id) -> {

            final ComponentName callingActivity = getCallingActivity();

            final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(getSearchEditText().getWindowToken(), InputMethodManager.HIDE_IMPLICIT_ONLY);

            ListItem listItem = getListItems().get(position);
            final boolean legacy = BLACKLISTED_ACTIVITIES.contains(callingActivity == null ? null : callingActivity.getClassName());
            Intent shortcut = xmppConnectionService.getShortcutService().createShortcut(((Contact) listItem), legacy);
            setResult(RESULT_OK,shortcut);
            finish();
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        ActionBar bar = getSupportActionBar();
        if(bar != null){
            bar.setTitle(R.string.create_shortcut);
        }
    }

    @Override
    protected void filterContacts(String needle) {
        if (xmppConnectionService == null) {
            replaceListItems(Collections.emptyList());
            getListItemAdapter().notifyDataSetChanged();
            return;
        }
        final List<ListItem> items = new ArrayList<>();
        for (final Account account : xmppConnectionService.getAccounts()) {
            if (account.isEnabled()) {
                for (final Contact contact : account.getRoster().getContacts()) {
                    if (contact.showInContactList()
                            && contact.match(this, needle)) {
                        items.add(contact);
                    }
                }
            }
        }
        replaceListItems(items);
        getListItemAdapter().notifyDataSetChanged();
    }
}
