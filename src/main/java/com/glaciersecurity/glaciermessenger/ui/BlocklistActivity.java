package com.glaciersecurity.glaciermessenger.ui;

import android.os.Bundle;
import android.text.Editable;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import java.util.ArrayList;
import java.util.List;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Blockable;
import com.glaciersecurity.glaciermessenger.entities.ListItem;
import com.glaciersecurity.glaciermessenger.entities.RawBlockable;
import com.glaciersecurity.glaciermessenger.ui.interfaces.OnBackendConnected;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.glaciersecurity.glaciermessenger.xmpp.OnUpdateBlocklist;

public class BlocklistActivity extends AbstractSearchableListItemActivity implements OnUpdateBlocklist {

	private Account account = null;

	@Override
	public void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getListView().setOnItemLongClickListener((parent, view, position, id) -> {
			BlockContactDialog.show(BlocklistActivity.this, (Blockable) getListItems().get(position));
			return true;
		});
		this.binding.fab.show();
		this.binding.fab.setOnClickListener((v)->showEnterJidDialog());
	}

	@Override
	public void onBackendConnected() {
		for (final Account account : xmppConnectionService.getAccounts()) {
			if (account.getJid().toEscapedString().equals(getIntent().getStringExtra(EXTRA_ACCOUNT))) {
				this.account = account;
				break;
			}
		}
		filterContacts();
		Fragment fragment = getSupportFragmentManager().findFragmentByTag(FRAGMENT_TAG_DIALOG);
		if (fragment instanceof OnBackendConnected) {
			((OnBackendConnected) fragment).onBackendConnected();
		}
	}

	@Override
	protected void filterContacts(final String needle) {
		final List<ListItem> items = new ArrayList<>();
		if (account != null) {
			for (final Jid jid : account.getBlocklist()) {
				ListItem item;
				if (jid.isFullJid()) {
					item = new RawBlockable(account, jid);
				} else {
					item = account.getRoster().getContact(jid);
				}
				if (item.match(this, needle)) {
					items.add(item);
				}
			}
		}
		replaceListItems(items);
		getListItemAdapter().notifyDataSetChanged();
	}

	protected void showEnterJidDialog() {
		FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
		Fragment prev = getSupportFragmentManager().findFragmentByTag("dialog");
		if (prev != null) {
			ft.remove(prev);
		}
		ft.addToBackStack(null);
		EnterJidDialog dialog = EnterJidDialog.newInstance(
				null,
				getString(R.string.block_jabber_id),
				getString(R.string.block),
				null,
				account.getJid().asBareJid().toEscapedString(),
				true,
				false
		);

		dialog.setOnEnterJidDialogPositiveListener((accountJid, contactJid) -> {
			Blockable blockable = new RawBlockable(account, contactJid);
			if (xmppConnectionService.sendBlockRequest(blockable, false, null)) {
				Toast.makeText(BlocklistActivity.this, R.string.corresponding_chats_closed, Toast.LENGTH_SHORT).show();
			}
			return true;
		});

		dialog.show(ft, "dialog");
	}

	@Override
	protected void refreshUiReal() {
		final Editable editable = getSearchEditText().getText();
		if (editable != null) {
			filterContacts(editable.toString());
		} else {
			filterContacts();
		}
	}

	@Override
	public void OnUpdateBlocklist() {
		refreshUi();
	}

}
