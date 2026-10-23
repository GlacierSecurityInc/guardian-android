/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.glaciersecurity.glaciermessenger.ui;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.chip.Chip;
import com.google.android.material.color.MaterialColors;
import com.google.common.base.Strings;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ActivitySearchBinding;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Contact;
import com.glaciersecurity.glaciermessenger.entities.Conversation;
import com.glaciersecurity.glaciermessenger.entities.Conversational;
import com.glaciersecurity.glaciermessenger.entities.Message;
import com.glaciersecurity.glaciermessenger.services.ConnectivityReceiver;
import com.glaciersecurity.glaciermessenger.services.MessageSearchTask;
import com.glaciersecurity.glaciermessenger.ui.adapter.ConnectionToolbarAdapter;
import com.glaciersecurity.glaciermessenger.ui.adapter.MessageAdapter;
import com.glaciersecurity.glaciermessenger.ui.adapter.SearchContactAdapter;
import com.glaciersecurity.glaciermessenger.ui.interfaces.OnSearchResultsAvailable;
import com.glaciersecurity.glaciermessenger.ui.util.ChangeWatcher;
import com.glaciersecurity.glaciermessenger.ui.util.DateSeparator;
import com.glaciersecurity.glaciermessenger.ui.util.PendingItem;
import com.glaciersecurity.glaciermessenger.ui.util.ShareUtil;
import com.glaciersecurity.glaciermessenger.utils.FtsUtils;
import com.glaciersecurity.glaciermessenger.utils.MessageUtils;

import static com.glaciersecurity.glaciermessenger.ui.util.SoftKeyboardUtils.hideSoftKeyboard;
import static com.glaciersecurity.glaciermessenger.ui.util.SoftKeyboardUtils.showKeyboard;

public class SearchActivity extends XmppActivity implements TextWatcher, OnSearchResultsAvailable, MessageAdapter.OnContactPictureClicked, ConnectivityReceiver.ConnectivityReceiverListener, SearchContactAdapter.OnContactClicked {

	private static final String EXTRA_SEARCH_TERM = "search-term";
	public static final String EXTRA_CONVERSATION_UUID = "uuid";

	private static final int SCOPE_ALL = 0;
	private static final int SCOPE_MESSAGES = 1;
	private static final int SCOPE_CONTACTS = 2;

	private static final int ALL_SCOPE_CONTACT_CAP = 3;
	private static final int MAX_RECENTS = 8;

	private static final String SEARCH_PREFS = "search_prefs";
	private static final String PREF_RECENT_TERMS = "recent_terms";

	private ActivitySearchBinding binding;
	private MessageAdapter messageListAdapter;
	private final List<Message> messages = new ArrayList<>();
	private final List<Contact> matchedContacts = new ArrayList<>();
	private SearchContactAdapter contactAdapter;
	private WeakReference<Message> selectedMessageReference = new WeakReference<>(null);
	private String uuid;
	private final ChangeWatcher<List<String>> currentSearch = new ChangeWatcher<>();
	private final PendingItem<String> pendingSearchTerm = new PendingItem<>();
	private final PendingItem<List<String>> pendingSearch = new PendingItem<>();
	private ConnectionToolbarAdapter connectionToolbarAdapter;
	private ConnectivityReceiver connectivityReceiver;

	private EditText searchField;
	private ImageButton clearButton;
	private final List<String> recentTerms = new ArrayList<>();
	private int scope = SCOPE_ALL;
	private String query = "";

	@Override
	public void onCreate(final Bundle bundle) {
		final Intent intent = getIntent();
		this.uuid = intent == null ? null : Strings.emptyToNull(intent.getStringExtra(EXTRA_CONVERSATION_UUID));
		final String searchTerm = bundle == null ? null : bundle.getString(EXTRA_SEARCH_TERM);
		if (searchTerm != null) {
			pendingSearchTerm.push(searchTerm);
		}
		super.onCreate(bundle);
		this.binding = DataBindingUtil.setContentView(this, R.layout.activity_search);
		Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
		setSupportActionBar(this.binding.toolbar);
		configureActionBar(getSupportActionBar());
		connectivityReceiver = new ConnectivityReceiver(this);
		connectionToolbarAdapter = new ConnectionToolbarAdapter(this, binding.offlineLayout, xmppConnectionService);
		binding.offlineLayout.setOnClickListener(mRefreshNetworkClickListener);
		connectionToolbarAdapter.updateOfflineStatusBar();

		this.messageListAdapter = new MessageAdapter(this, this.messages, uuid == null);
		this.messageListAdapter.setOnContactPictureClicked(this);
		this.binding.searchResults.setAdapter(messageListAdapter);
		registerForContextMenu(this.binding.searchResults);

		this.contactAdapter = new SearchContactAdapter(this);
		this.binding.contactsResults.setLayoutManager(new LinearLayoutManager(this));
		this.binding.contactsResults.setAdapter(contactAdapter);

		this.binding.scopeChipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
			final int checkedId = group.getCheckedChipId();
			if (checkedId == R.id.scope_messages) {
				scope = SCOPE_MESSAGES;
			} else if (checkedId == R.id.scope_contacts) {
				scope = SCOPE_CONTACTS;
			} else {
				scope = SCOPE_ALL;
			}
			updateResultViews();
		});

		this.binding.showAllContacts.setOnClickListener(v -> {
			this.binding.scopeContacts.setChecked(true);
		});

		this.binding.clearAllRecents.setOnClickListener(v -> {
			recentTerms.clear();
			persistRecents();
			renderRecents();
		});

		setupSearchField();
		loadRecents();
		renderRecents();
		updateResultViews();
	}

	private void setupSearchField() {
		searchField = binding.searchBar.findViewById(R.id.search_field);
		clearButton = binding.searchBar.findViewById(R.id.search_clear);
		searchField.setHint(R.string.search_messages_contacts);
		searchField.setContentDescription(getString(R.string.search_messages_contacts));
		searchField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE);
		searchField.addTextChangedListener(this);
		searchField.setOnEditorActionListener((v, actionId, event) -> {
			addRecent(searchField.getText().toString());
			hideSoftKeyboard(this);
			return true;
		});
		clearButton.setOnClickListener(v -> {
			searchField.setText("");
			searchField.requestFocus();
			showKeyboard(searchField);
		});

		final String term = pendingSearchTerm.pop();
		if (term != null) {
			searchField.setText(term);
			searchField.setSelection(searchField.getText().length());
		} else {
			searchField.requestFocus();
			showKeyboard(searchField);
		}
	}

	@Override
	public void onCreateContextMenu(final ContextMenu menu, final View v, ContextMenu.ContextMenuInfo menuInfo) {
		v.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0));
		AdapterView.AdapterContextMenuInfo acmi = (AdapterView.AdapterContextMenuInfo) menuInfo;
		final Message message = this.messages.get(acmi.position);
		this.selectedMessageReference = new WeakReference<>(message);
		getMenuInflater().inflate(R.menu.search_result_context, menu);
		MenuItem copy = menu.findItem(R.id.copy_message);
		MenuItem quote = menu.findItem(R.id.quote_message);
		MenuItem copyUrl = menu.findItem(R.id.copy_url);
		if (message.isGeoUri()) {
			copy.setVisible(false);
			quote.setVisible(false);
		} else {
			copyUrl.setVisible(false);
		}
		super.onCreateContextMenu(menu, v, menuInfo);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			hideSoftKeyboard(this);
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public boolean onContextItemSelected(MenuItem item) {
		final Message message = selectedMessageReference.get();
		if (message != null) {
			final int itemId = item.getItemId();
			if (itemId == R.id.open_conversation) {
				addRecent(query);
				switchToConversation(wrap(message.getConversation()));
			} else if (itemId == R.id.share_with) {
				ShareUtil.share(this, message);
			} else if (itemId == R.id.copy_message) {
				ShareUtil.copyToClipboard(this, message);
			} else if (itemId == R.id.copy_url) {
				ShareUtil.copyUrlToClipboard(this, message);
			} else if (itemId == R.id.quote_message) {
				quote(message);
			}
		}
		return super.onContextItemSelected(item);
	}

	@Override
	public void onSaveInstanceState(Bundle bundle) {
		List<String> term = currentSearch.get();
		if (term != null && term.size() > 0) {
			bundle.putString(EXTRA_SEARCH_TERM,FtsUtils.toUserEnteredString(term));
		}
		super.onSaveInstanceState(bundle);
	}

	private void quote(Message message) {
		switchToConversationAndQuote(wrap(message.getConversation()), MessageUtils.prepareQuote(message));
	}

	private Conversation wrap(Conversational conversational) {
		if (conversational instanceof Conversation) {
			return (Conversation) conversational;
		} else {
			return xmppConnectionService.findOrCreateConversation(conversational.getAccount(),
					conversational.getJid(),
					conversational.getMode() == Conversational.MODE_MULTI,
					true,
					true);
		}
	}

	@Override
	protected void refreshUiReal() {

	}

	@Override
	protected void onBackendConnected() {
		if (connectionToolbarAdapter != null) {
			connectionToolbarAdapter.updateOfflineStatusBar();
		}
		final List<String> searchTerm = pendingSearch.pop();
		if (searchTerm != null && currentSearch.watch(searchTerm)) {
			xmppConnectionService.search(searchTerm, uuid,this);
		}
		// roster is now available — refresh any in-progress contact filter
		if (!query.isEmpty()) {
			filterContacts(query);
			updateResultViews();
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		ContextCompat.registerReceiver(this, connectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED);
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
	public void beforeTextChanged(CharSequence s, int start, int count, int after) {

	}

	@Override
	public void onTextChanged(CharSequence s, int start, int before, int count) {

	}

	@Override
	public void afterTextChanged(Editable s) {
		this.query = s.toString().trim();
		if (clearButton != null) {
			clearButton.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
		}
		final List<String> term = FtsUtils.parse(query);
		if (query.isEmpty()) {
			MessageSearchTask.cancelRunningTasks();
			currentSearch.watch(term);
			this.messages.clear();
			this.matchedContacts.clear();
			messageListAdapter.setHighlightedTerm(null);
			messageListAdapter.notifyDataSetChanged();
			updateResultViews();
			return;
		}
		filterContacts(query);
		if (term.isEmpty()) {
			// Query has no full-text tokens (e.g. punctuation only) — drop any
			// message search but still surface matching contacts.
			MessageSearchTask.cancelRunningTasks();
			currentSearch.watch(term);
			this.messages.clear();
			messageListAdapter.setHighlightedTerm(null);
			messageListAdapter.notifyDataSetChanged();
		} else if (currentSearch.watch(term)) {
			if (xmppConnectionService != null) {
				xmppConnectionService.search(term, uuid, this);
			} else {
				pendingSearch.push(term);
			}
		}
		updateResultViews();
	}

	private void filterContacts(final String needle) {
		matchedContacts.clear();
		if (xmppConnectionService == null) {
			return;
		}
		for (final Account account : xmppConnectionService.getAccounts()) {
			if (account.isEnabled()) {
				for (final Contact contact : account.getRoster().getContacts()) {
					if (contact.showInContactList() && contact.match(this, needle)) {
						matchedContacts.add(contact);
					}
				}
			}
		}
		Collections.sort(matchedContacts);
	}

	@Override
	public void onSearchResultsAvailable(List<String> term, List<Message> messages) {
		runOnUiThread(() -> {
			this.messages.clear();
			messageListAdapter.setHighlightedTerm(term);
			DateSeparator.addAll(messages);
			this.messages.addAll(messages);
			messageListAdapter.notifyDataSetChanged();
			updateResultViews();
			this.binding.searchResults.setSelection(0);
		});
	}

	/**
	 * Single source of truth for what the body shows: resting state (empty
	 * query), grouped results, or the no-results card — honoring the active
	 * scope chip.
	 */
	private void updateResultViews() {
		final boolean hasQuery = !query.isEmpty();
		binding.scopeBar.setVisibility(View.VISIBLE);

		if (!hasQuery) {
			binding.restingState.setVisibility(View.VISIBLE);
			binding.resultsContainer.setVisibility(View.GONE);
			binding.noResults.setVisibility(View.GONE);
			return;
		}

		final boolean showContacts = (scope == SCOPE_ALL || scope == SCOPE_CONTACTS) && !matchedContacts.isEmpty();
		final boolean showMessages = (scope == SCOPE_ALL || scope == SCOPE_MESSAGES) && !messages.isEmpty();

		binding.restingState.setVisibility(View.GONE);

		if (!showContacts && !showMessages) {
			binding.resultsContainer.setVisibility(View.GONE);
			binding.noResults.setVisibility(View.VISIBLE);
			binding.noResultsTitle.setText(getString(R.string.search_no_results_title, query));
			return;
		}

		binding.noResults.setVisibility(View.GONE);
		binding.resultsContainer.setVisibility(View.VISIBLE);

		// Contacts section
		if (showContacts) {
			final boolean contactsOnly = scope == SCOPE_CONTACTS;
			final int limit = contactsOnly ? Integer.MAX_VALUE : ALL_SCOPE_CONTACT_CAP;
			contactAdapter.update(matchedContacts, query, limit);
			applyContactsScroll(contactsOnly);
			binding.contactsResults.setVisibility(View.VISIBLE);
			binding.contactsHeader.setVisibility(View.VISIBLE);
			binding.contactsHeader.setText(String.format("%s   %d",
					getString(R.string.search_section_contacts), matchedContacts.size()));
			final boolean overflow = !contactsOnly && matchedContacts.size() > ALL_SCOPE_CONTACT_CAP;
			binding.showAllContacts.setVisibility(overflow ? View.VISIBLE : View.GONE);
			if (overflow) {
				binding.showAllContacts.setText(getString(R.string.search_show_all_contacts, matchedContacts.size()));
			}
		} else {
			binding.contactsResults.setVisibility(View.GONE);
			binding.contactsHeader.setVisibility(View.GONE);
			binding.showAllContacts.setVisibility(View.GONE);
		}

		// Messages section
		if (showMessages) {
			binding.searchResults.setVisibility(View.VISIBLE);
			binding.messagesHeader.setVisibility(View.VISIBLE);
			binding.messagesHeader.setText(String.format("%s   %d",
					getString(R.string.search_section_messages), messages.size()));
		} else {
			binding.searchResults.setVisibility(View.GONE);
			binding.messagesHeader.setVisibility(View.GONE);
		}
	}

	/**
	 * In the "Contacts" scope the list owns the whole body and scrolls; in the
	 * "All" scope it sits as a fixed, capped block above the message list.
	 */
	private void applyContactsScroll(final boolean scroll) {
		final LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) binding.contactsResults.getLayoutParams();
		if (scroll) {
			lp.height = 0;
			lp.weight = 1;
			binding.contactsResults.setNestedScrollingEnabled(true);
		} else {
			lp.height = LinearLayout.LayoutParams.WRAP_CONTENT;
			lp.weight = 0;
			binding.contactsResults.setNestedScrollingEnabled(false);
		}
		binding.contactsResults.setLayoutParams(lp);
	}

	@Override
	public void onContactClicked(final Contact contact) {
		addRecent(query);
		final Conversation conversation = xmppConnectionService.findOrCreateConversation(
				contact.getAccount(), contact.getJid(), false, true);
		switchToConversation(conversation);
	}

	// ── Recent searches ─────────────────────────────────────────────────────

	private void loadRecents() {
		final SharedPreferences prefs = getSharedPreferences(SEARCH_PREFS, Context.MODE_PRIVATE);
		final String stored = prefs.getString(PREF_RECENT_TERMS, "");
		recentTerms.clear();
		if (!TextUtils.isEmpty(stored)) {
			for (final String term : stored.split("\n")) {
				if (!term.trim().isEmpty()) {
					recentTerms.add(term);
				}
			}
		}
	}

	private void persistRecents() {
		final SharedPreferences prefs = getSharedPreferences(SEARCH_PREFS, Context.MODE_PRIVATE);
		prefs.edit().putString(PREF_RECENT_TERMS, TextUtils.join("\n", recentTerms)).apply();
	}

	private void addRecent(final String rawTerm) {
		final String term = rawTerm == null ? "" : rawTerm.trim();
		if (term.isEmpty()) {
			return;
		}
		recentTerms.removeIf(t -> t.equalsIgnoreCase(term));
		recentTerms.add(0, term);
		while (recentTerms.size() > MAX_RECENTS) {
			recentTerms.remove(recentTerms.size() - 1);
		}
		persistRecents();
		renderRecents();
	}

	private void renderRecents() {
		binding.recentChipGroup.removeAllViews();
		final boolean hasRecents = !recentTerms.isEmpty();
		binding.recentHeader.setVisibility(hasRecents ? View.VISIBLE : View.GONE);
		binding.recentChipGroup.setVisibility(hasRecents ? View.VISIBLE : View.GONE);

		final int onSurface = MaterialColors.getColor(binding.recentChipGroup, com.google.android.material.R.attr.colorOnSurface);
		final int muted = MaterialColors.getColor(binding.recentChipGroup, com.google.android.material.R.attr.colorOnSurfaceVariant);
		final int fill = MaterialColors.getColor(binding.recentChipGroup, com.google.android.material.R.attr.colorSurfaceContainerHigh);
		final int stroke = MaterialColors.getColor(binding.recentChipGroup, com.google.android.material.R.attr.colorOutlineVariant);

		for (final String term : recentTerms) {
			final Chip chip = new Chip(this);
			chip.setText(term);
			chip.setTextColor(onSurface);
			chip.setChipBackgroundColor(ColorStateList.valueOf(fill));
			chip.setChipStrokeColor(ColorStateList.valueOf(stroke));
			chip.setChipStrokeWidth(dp(1));
			chip.setChipIconResource(R.drawable.ic_cc_clock);
			chip.setChipIconTint(ColorStateList.valueOf(muted));
			chip.setChipIconVisible(true);
			chip.setCloseIconResource(R.drawable.ic_clear_24dp);
			chip.setCloseIconTint(ColorStateList.valueOf(muted));
			chip.setCloseIconVisible(true);
			chip.setCheckable(false);
			chip.setEnsureMinTouchTargetSize(false);
			chip.setContentDescription(term);
			chip.setOnClickListener(v -> {
				if (searchField != null) {
					searchField.setText(term);
					searchField.setSelection(searchField.getText().length());
				}
			});
			chip.setOnCloseIconClickListener(v -> {
				recentTerms.remove(term);
				persistRecents();
				renderRecents();
			});
			binding.recentChipGroup.addView(chip);
		}
		updateRestingHint();
	}

	private void updateRestingHint() {
		final boolean empty = recentTerms.isEmpty();
		binding.restingHint.setVisibility(empty ? View.VISIBLE : View.GONE);
	}

	private int dp(final float value) {
		return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
	}

	@Override
	public void onContactPictureClicked(Message message) {
		String fingerprint;
		if (message.getEncryption() == Message.ENCRYPTION_PGP || message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
			fingerprint = "pgp";
		} else {
			fingerprint = message.getFingerprint();
		}
		if (message.getStatus() == Message.STATUS_RECEIVED) {
			final Contact contact = message.getContact();
			if (contact != null) {
				if (contact.isSelf()) {
					switchToAccount(message.getConversation().getAccount(), fingerprint);
				} else {
					switchToContactDetails(contact, fingerprint);
				}
			}
		} else {
			switchToAccount(message.getConversation().getAccount(), fingerprint);
		}
	}

	@Override
	public void onNetworkConnectionChanged() {
		if (connectionToolbarAdapter != null) {
			connectionToolbarAdapter.checkNetworkConnection();
		}
	}

	private final View.OnClickListener mRefreshNetworkClickListener = new View.OnClickListener() {
		@Override
		public void onClick(View v) {
			connectionToolbarAdapter.onClick();
		}
	};
}
