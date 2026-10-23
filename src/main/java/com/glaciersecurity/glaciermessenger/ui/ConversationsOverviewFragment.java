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

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.os.Bundle;

import com.glaciersecurity.glaciermessenger.utils.Log;

import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.view.MenuCompat;
import androidx.databinding.DataBindingUtil;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.FragmentConversationsOverviewBinding;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Conversation;
import com.glaciersecurity.glaciermessenger.entities.Conversational;
import com.glaciersecurity.glaciermessenger.services.CoreConnectionTileService;
import com.glaciersecurity.glaciermessenger.services.QuickConversationsService;
import com.glaciersecurity.glaciermessenger.ui.adapter.ConversationAdapter;
import com.glaciersecurity.glaciermessenger.ui.interfaces.OnConversationArchived;
import com.glaciersecurity.glaciermessenger.ui.interfaces.OnConversationsListItemUpdated;
import com.glaciersecurity.glaciermessenger.ui.interfaces.OnConversationSelected;
import com.glaciersecurity.glaciermessenger.ui.util.MenuDoubleTabUtil;
import com.glaciersecurity.glaciermessenger.ui.util.PendingItem;
import com.glaciersecurity.glaciermessenger.ui.util.ScrollState;
import com.glaciersecurity.glaciermessenger.utils.AccountUtils;
import com.glaciersecurity.glaciermessenger.wireguard.PersistentConnectionProperties;
import com.glaciersecurity.glaciermessenger.wireguard.WireGuardStateMonitor;

import static androidx.recyclerview.widget.ItemTouchHelper.LEFT;
import static androidx.recyclerview.widget.ItemTouchHelper.RIGHT;
import static com.glaciersecurity.glaciermessenger.utils.Compatibility.getPreferences;

public class ConversationsOverviewFragment extends XmppFragment{

	private static final String STATE_SCROLL_POSITION = ConversationsOverviewFragment.class.getName()+".scroll_state";

	private static final int FILTER_ALL = 0;
	private static final int FILTER_UNREAD = 1;
	private static final int FILTER_GROUPS = 2;

	// Displayed (filtered) list bound to the adapter, plus the unfiltered master list.
	private final List<Conversation> conversations = new ArrayList<>();
	private final List<Conversation> allConversations = new ArrayList<>();
	private int activeFilter = FILTER_ALL;
	private final PendingItem<ScrollState> pendingScrollState = new PendingItem<>();
	private boolean isInitialLoadInProgress = true;
	private FragmentConversationsOverviewBinding binding;
	private ConversationAdapter conversationsAdapter;
	private XmppActivity activity;

	// Timestamp of the latest message in the conversation currently sorted to the top of
	// allConversations. Used to detect a genuine new-message arrival (as opposed to a reorder
	// caused by pin/mute toggling) so we don't flash the "new conversations" indicator for those.
	private long topConversationMessageTime = -1;
	// Distinct conversation UUIDs that have surfaced new activity at the top of the list
	// since the indicator was last dismissed, so the badge count reflects "N conversations"
	// rather than "N refreshes" (a single busy conversation shouldn't inflate the count).
	private final java.util.LinkedHashSet<String> conversationsNewAtTop = new java.util.LinkedHashSet<>();

    private int swipedOpenPosition = Math.toIntExact(RecyclerView.NO_ID);
    private View swipedOpenFrame = null;
    private int swipedOpenDirection = 0;


        private final BroadcastReceiver coreTileStateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                        refreshQuickConnectMenu();
                }
        };
        private boolean coreTileStateReceiverRegistered;

	private static final float BUTTONS_WIDTH_DP = 216f;

	private final ItemTouchHelper.SimpleCallback callback = new ItemTouchHelper.SimpleCallback(0, LEFT | RIGHT) {
		@Override
		public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
			return false;
		}

		@Override
		public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder,
								float dX, float dY, int actionState, boolean isCurrentlyActive) {
			if (viewHolder instanceof ConversationAdapter.ConversationViewHolder conversationViewHolder) {
				final View frame = conversationViewHolder.binding.frame;
				if (dX != 0) {
					final int pos = viewHolder.getLayoutPosition();
					final Conversation conv =
							(pos >= 0 && pos < conversations.size()) ? conversations.get(pos) : null;
					if (frame.getTag(R.id.swipe_original_background) == null) {
						frame.setTag(R.id.swipe_original_background, frame.getBackground());
						frame.setBackgroundResource(R.drawable.bg_conversation_frame_swiped);
						frame.setElevation(getResources().getDisplayMetrics().density * 8f);
						// Apply correct button states at drag-start so nothing flashes
						// between the bind-reset defaults and onSwiped firing.
						conversationViewHolder.binding.btnDelete.setVisibility(View.VISIBLE);
						conversationViewHolder.binding.btnDeleteLeft.setVisibility(View.VISIBLE);
						if (conv != null) {
							final long mt = conv.getLongAttribute(Conversation.ATTRIBUTE_MUTED_TILL, 0);
							final boolean muted = mt == Long.MAX_VALUE || mt >= System.currentTimeMillis();
							final int muteIcon = muted ? R.drawable.ic_notifications_24dp : R.drawable.ic_notifications_off_24dp;
							conversationViewHolder.binding.btnMute.setImageResource(muteIcon);
							conversationViewHolder.binding.btnMuteLeft.setImageResource(muteIcon);
							final int pinIcon = conv.getBooleanAttribute(Conversation.ATTRIBUTE_PINNED_ON_TOP, false)
									? R.drawable.ic_push_pin_off_24dp : R.drawable.ic_push_pin_24dp;
							conversationViewHolder.binding.btnPin.setImageResource(pinIcon);
							conversationViewHolder.binding.btnPinLeft.setImageResource(pinIcon);
						}
					}
					// Close any other open item when a new drag starts
					if (isCurrentlyActive && viewHolder.getLayoutPosition() != swipedOpenPosition) {
						closeSwipedItem();
					}
					final float density = getResources().getDisplayMetrics().density;
					final float maxWidth = BUTTONS_WIDTH_DP * density;
					if (dX < 0) {
						// Cap translation so frame never scrolls past right-side buttons area
						if (dX < -maxWidth) dX = -maxWidth;
					} else {
						// Cap translation so frame never scrolls past left-side buttons area
						if (dX > maxWidth) dX = maxWidth;
					}
				}
				getDefaultUIUtil().onDraw(c, recyclerView, frame, dX, dY, actionState, isCurrentlyActive);
			}
		}

		@Override
		public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
			if (viewHolder instanceof ConversationAdapter.ConversationViewHolder conversationViewHolder) {
				final View frame = conversationViewHolder.binding.frame;
				// If this item is locked open, keep it there — clearView fires during
				// detach cleanup and must not undo the locked translationX.
				if (viewHolder.getLayoutPosition() == swipedOpenPosition && swipedOpenFrame != null) {
					return;
				}
				final android.graphics.drawable.Drawable original =
						(android.graphics.drawable.Drawable) frame.getTag(R.id.swipe_original_background);
				if (original != null) {
					frame.setBackground(original);
					frame.setTag(R.id.swipe_original_background, null);
				}
				frame.setElevation(0f);
				getDefaultUIUtil().clearView(frame);
			}
		}

		@Override
		public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder viewHolder) {
			return 0.2f;
		}

		@Override
		public float getSwipeEscapeVelocity(final float defaultEscapeVelocity) {
			return 2f * defaultEscapeVelocity;
		}

		@Override
		public void onSwiped(final RecyclerView.ViewHolder viewHolder, final int direction) {
			if (!(viewHolder instanceof ConversationAdapter.ConversationViewHolder cvh)) return;
			final int position = viewHolder.getLayoutPosition();
			if (position < 0 || position >= conversations.size()) return;

			// Close any previously open item without reattaching the helper yet
			if (swipedOpenFrame != null) {
				swipedOpenFrame.animate().cancel();
				swipedOpenFrame.setTranslationX(0f);
				final android.graphics.drawable.Drawable orig =
						(android.graphics.drawable.Drawable) swipedOpenFrame.getTag(R.id.swipe_original_background);
				if (orig != null) {
					swipedOpenFrame.setBackground(orig);
					swipedOpenFrame.setTag(R.id.swipe_original_background, null);
				}
				swipedOpenFrame.setElevation(0f);
				swipedOpenFrame = null;
				swipedOpenPosition = Math.toIntExact(RecyclerView.NO_ID);
			}

			final View frame = cvh.binding.frame;
			final float density = getResources().getDisplayMetrics().density;
			final Conversation conversation = conversations.get(position);

			// Determine current mute/pin state to set correct icons and actions
			final long mutedTill = conversation.getLongAttribute(Conversation.ATTRIBUTE_MUTED_TILL, 0);
			final boolean isMuted = mutedTill == Long.MAX_VALUE || mutedTill >= System.currentTimeMillis();
			final boolean isPinned = conversation.getBooleanAttribute(Conversation.ATTRIBUTE_PINNED_ON_TOP, false);

			final boolean isGroup = conversation.getMode() == Conversational.MODE_MULTI;
			if (direction == LEFT) {
				cvh.binding.btnDelete.setVisibility(View.VISIBLE);
				final float buttonsWidth = BUTTONS_WIDTH_DP * density;
				frame.setTranslationX(-buttonsWidth);

				cvh.binding.btnMute.setClickable(true);
				cvh.binding.btnPin.setClickable(true);
				if (isMuted) {
					cvh.binding.btnMute.setImageResource(R.drawable.ic_notifications_24dp);
					cvh.binding.btnMute.setContentDescription(getString(R.string.enable_notifications));
					cvh.binding.btnMute.setOnClickListener(v -> executeUnmute(conversation));
				} else {
					cvh.binding.btnMute.setImageResource(R.drawable.ic_notifications_off_24dp);
					cvh.binding.btnMute.setContentDescription(getString(R.string.action_mute_chat));
					cvh.binding.btnMute.setOnClickListener(v -> executeMute(conversation));
				}

				cvh.binding.btnPin.setImageResource(isPinned ? R.drawable.ic_push_pin_off_24dp : R.drawable.ic_push_pin_24dp);
				cvh.binding.btnPin.setOnClickListener(v -> executePin(conversation));
				cvh.binding.btnDelete.setClickable(true);
				cvh.binding.btnDelete.setContentDescription(
						getString(isGroup ? R.string.action_archive_group : R.string.action_archive_chat));
				cvh.binding.btnDelete.setOnClickListener(
						v -> executeSwipeAction(conversation, isGroup));
			} else {
				// Right swipe — reveal left-side action buttons
				cvh.binding.btnDeleteLeft.setVisibility(View.VISIBLE);
				final float buttonsWidthLeft = BUTTONS_WIDTH_DP * density;
				frame.setTranslationX(buttonsWidthLeft);

				cvh.binding.btnMuteLeft.setClickable(true);
				cvh.binding.btnPinLeft.setClickable(true);
				if (isMuted) {
					cvh.binding.btnMuteLeft.setImageResource(R.drawable.ic_notifications_24dp);
					cvh.binding.btnMuteLeft.setContentDescription(getString(R.string.enable_notifications));
					cvh.binding.btnMuteLeft.setOnClickListener(v -> executeUnmute(conversation));
				} else {
					cvh.binding.btnMuteLeft.setImageResource(R.drawable.ic_notifications_off_24dp);
					cvh.binding.btnMuteLeft.setContentDescription(getString(R.string.action_mute_chat));
					cvh.binding.btnMuteLeft.setOnClickListener(v -> executeMute(conversation));
				}

				cvh.binding.btnPinLeft.setImageResource(isPinned ? R.drawable.ic_push_pin_off_24dp : R.drawable.ic_push_pin_24dp);
				cvh.binding.btnPinLeft.setOnClickListener(v -> executePin(conversation));
				cvh.binding.btnDeleteLeft.setClickable(true);
				cvh.binding.btnDeleteLeft.setContentDescription(
						getString(isGroup ? R.string.action_archive_group : R.string.action_archive_chat));
				cvh.binding.btnDeleteLeft.setOnClickListener(
						v -> executeSwipeAction(conversation, isGroup));
			}

			swipedOpenPosition = position;
			swipedOpenFrame = frame;
			swipedOpenDirection = direction;

			// Detach so ItemTouchHelper stops intercepting touches — buttons become clickable
			if (touchHelper != null) touchHelper.attachToRecyclerView(null);
		}
	};

	private ItemTouchHelper touchHelper;

	public static Conversation getSuggestion(FragmentActivity activity) {
		return getSuggestion(activity, null);
	}

	public static Conversation getSuggestion(FragmentActivity activity, Conversation exception) {
		Fragment fragment = activity.getSupportFragmentManager().findFragmentById(R.id.main_fragment);
		if (fragment instanceof ConversationsOverviewFragment) {
			List<Conversation> conversations = ((ConversationsOverviewFragment) fragment).allConversations;
			if (!conversations.isEmpty()) {
				Conversation suggestion = conversations.get(0);
				if (suggestion == exception) {
					if (conversations.size() > 1) {
						return conversations.get(1);
					}
				} else {
					return suggestion;
				}
			}
		}
		return null;

	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		if (savedInstanceState == null) {
			return;
		}
		pendingScrollState.push(savedInstanceState.getParcelable(STATE_SCROLL_POSITION));
	}

	@Override
	public void onAttach(Context context) {
		super.onAttach(context);
		if (context instanceof XmppActivity) {
			this.activity = (XmppActivity) context;
		} else {
			throw new IllegalStateException("Trying to attach fragment to activity that is not an XmppActivity");
		}
	}
        @Override
        public void onDestroyView() {
                super.onDestroyView();
                this.swipedOpenFrame = null;
                this.swipedOpenPosition = Math.toIntExact(RecyclerView.NO_ID);
                this.swipedOpenDirection = 0;
                this.binding = null;
                this.conversationsAdapter = null;
                this.touchHelper = null;
                unregisterCoreTileStateReceiver();
        }
	@Override
	public void onDestroy() {
		super.onDestroy();

	}
        @Override
        public void onPause() {
                unregisterCoreTileStateReceiver();
                closeSwipedItem();
                super.onPause();
        }

	@Override
	public void onDetach() {
		super.onDetach();
		this.activity = null;
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setHasOptionsMenu(true);
	}

	@Override
	public View onCreateView(final LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		this.binding = DataBindingUtil.inflate(inflater, R.layout.fragment_conversations_overview, container, false);
		this.binding.sync.setOnClickListener((view) -> queryDb());
		this.binding.fab.setOnClickListener((view) -> StartConversationActivity.launch(getActivity()));
		View avater_layout = getActivity().findViewById(R.id.avater_layout);
		if (avater_layout != null) {
			avater_layout.setVisibility(View.VISIBLE);
		}
		View status_icon = getActivity().findViewById(R.id.statusIcon);
		if (status_icon != null) {
			status_icon.setVisibility(View.GONE);
		}
                View details_expand = getActivity().findViewById(R.id.details_expand);
                if (details_expand != null) {
                        details_expand.setVisibility(View.GONE);
                        details_expand.setOnClickListener(null);
                }
		View navView = getActivity().findViewById(R.id.navigation_conversations);
		if (navView != null) {
			navView.setVisibility(View.VISIBLE);
		}
		this.conversationsAdapter = new ConversationAdapter(this.activity, this.conversations);
		this.conversationsAdapter.setConversationClickListener(conversation -> {
			if (activity instanceof OnConversationSelected) {
				((OnConversationSelected) activity).onConversationSelected(conversation);
			} else {
				Log.w(Config.LOGTAG, "Activity does not implement OnConversationSelected");
			}
		});
		this.binding.list.setAdapter(this.conversationsAdapter);
		// Reset swipe state on any data change so a refresh/notifyDataSetChanged() while a row
		// is swiped open doesn't leave the helper detached and swipedOpenFrame stale.
		this.conversationsAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
			@Override public void onChanged() { resetSwipedItemImmediate(); }
			@Override public void onItemRangeChanged(int p, int c) { resetSwipedItemImmediate(); }
			@Override public void onItemRangeChanged(int p, int c, Object payload) { resetSwipedItemImmediate(); }
			@Override public void onItemRangeInserted(int p, int c) { resetSwipedItemImmediate(); }
			@Override public void onItemRangeRemoved(int p, int c) { resetSwipedItemImmediate(); }
			@Override public void onItemRangeMoved(int f, int t, int c) { resetSwipedItemImmediate(); }
		});
		this.binding.list.setLayoutManager(new LinearLayoutManager(getActivity(),LinearLayoutManager.VERTICAL,false));
		this.binding.list.addOnScrollListener(ExtendedFabSizeChanger.of(binding.fab));
                this.binding.list.addOnScrollListener(ExtendedFabSizeChanger.of(binding.sync));
                this.binding.list.addOnScrollListener(ExtendedFabSizeChanger.of(binding.phoneSync));


		this.binding.list.addOnScrollListener(new RecyclerView.OnScrollListener() {
			@Override
			public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
				if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
					closeSwipedItem();
				}
			}

			@Override
			public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
				if (isScrolledToTop()) {
					hideNewConversationsIndicator();
				}
			}
		});
		this.binding.scrollToTopButton.setOnClickListener((view) -> {
			binding.list.smoothScrollToPosition(0);
			hideNewConversationsIndicator();
		});
		this.binding.list.addOnItemTouchListener(new RecyclerView.OnItemTouchListener() {
			@Override
			public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull android.view.MotionEvent e) {
				if (swipedOpenFrame == null) return false;
				if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
					android.view.View child = rv.findChildViewUnder(e.getX(), e.getY());
					if (child == null) {
						closeSwipedItem();
						return true;
					}
					int touchedPos = rv.getChildAdapterPosition(child);
					if (touchedPos != swipedOpenPosition) {
						closeSwipedItem();
					}
				}
				return false;
			}
			@Override public void onTouchEvent(@NonNull RecyclerView rv, @NonNull android.view.MotionEvent e) {}
			@Override public void onRequestDisallowInterceptTouchEvent(boolean b) {}
		});
		this.binding.searchBar.setOnClickListener((view) ->
				startActivity(new Intent(getActivity(), SearchActivity.class)));
		this.binding.filterChipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
			final int newFilter;
			if (checkedIds.contains(R.id.chip_unread)) {
				newFilter = FILTER_UNREAD;
			} else if (checkedIds.contains(R.id.chip_groups)) {
				newFilter = FILTER_GROUPS;
			} else {
				newFilter = FILTER_ALL;
			}
			if (newFilter != activeFilter) {
				activeFilter = newFilter;
				closeSwipedItem();
				applyFilterAndRefresh();
				binding.list.scrollToPosition(0);
				hideNewConversationsIndicator();
			}
		});
		updateSwipeState();
                updateEmptyView();
                return binding.getRoot();
        }

	private List<Conversation> filterConversations(final List<Conversation> source, final int filter) {
		if (filter == FILTER_ALL) {
			return new ArrayList<>(source);
		}
		final List<Conversation> result = new ArrayList<>();
		for (final Conversation conversation : source) {
			if (filter == FILTER_UNREAD && conversation.unreadCount() > 0) {
				result.add(conversation);
			} else if (filter == FILTER_GROUPS
					&& conversation.getMode() == Conversational.MODE_MULTI) {
				result.add(conversation);
			}
		}
		return result;
	}

	private void applyFilterAndRefresh() {
		if (binding == null || conversationsAdapter == null) {
			return;
		}
		this.conversationsAdapter.refreshConversations(
				filterConversations(this.allConversations, this.activeFilter));
		updateFilterChips();
		updateEmptyView();
	}

	private void updateFilterChips() {
		if (binding == null) {
			return;
		}
		int unread = 0;
		int groups = 0;
		for (final Conversation conversation : this.allConversations) {
			if (conversation.unreadCount() > 0) {
				unread++;
			}
			if (conversation.getMode() == Conversational.MODE_MULTI) {
				groups++;
			}
		}
		binding.chipAll.setText(
				String.format("%s · %d", getString(R.string.filter_all), this.allConversations.size()));
		binding.chipUnread.setText(
				String.format("%s · %d", getString(R.string.filter_unread), unread));
		binding.chipGroups.setText(
				String.format("%s · %d", getString(R.string.filter_groups), groups));
		if (unread > 0) {
			binding.unreadSummary.setVisibility(View.VISIBLE);
			binding.unreadSummary.setText(getString(R.string.x_unread, unread));
		} else {
			binding.unreadSummary.setVisibility(View.GONE);
		}
	}

	private void queryDb() {
		if (activity.xmppConnectionServiceBound) {
			Account account = activity.xmppConnectionService.getFirstAccount();
			if (account != null) {
				activity.xmppConnectionService.queryLatestVersion();
				activity.xmppConnectionService.queryGlacierUsers(account, true);
			}
		}
	}

	@Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.fragment_conversations_overview, menu);
        MenuCompat.setGroupDividerEnabled(menu, true);
        AccountUtils.showHideMenuItems(menu);
        final MenuItem privacyPolicyMenuItem = menu.findItem(R.id.action_privacy_policy);
        privacyPolicyMenuItem.setVisible(QuickConversationsService.isPlayStoreFlavor());
        final MenuItem quickConnectItem = menu.findItem(R.id.action_core_connect);
        quickConnectItem.setVisible(getPreferences(getContext()).getBoolean("show_quick_connect", false));
        if (quickConnectItem.isVisible()) {
            boolean vpnEnabled = PersistentConnectionProperties.getInstance().isRunningTunnel();
            int iconRes = vpnEnabled ? R.drawable.ic_core_key_white_24dp : R.drawable.ic_core_key_grey_24dp;
            quickConnectItem.setIcon(iconRes);
        }
    }

	private void reattachTouchHelper() {
		if (touchHelper != null && binding != null) {
			touchHelper.attachToRecyclerView(binding.list);
		}
	}

	private void disableSwipeButtons(final View frame) {
		final android.view.ViewGroup parent = (android.view.ViewGroup) frame.getParent();
		if (parent == null) return;
		final int[] ids = {R.id.btn_mute, R.id.btn_pin, R.id.btn_delete,
				R.id.btn_mute_left, R.id.btn_pin_left, R.id.btn_delete_left};
		for (int id : ids) {
			final View btn = parent.findViewById(id);
			if (btn != null) {
				btn.setOnClickListener(null);
				btn.setClickable(false);
			}
		}
	}

	private void closeSwipedItem() {
		if (swipedOpenFrame != null) {
			final View frame = swipedOpenFrame;
			final android.graphics.drawable.Drawable original =
					(android.graphics.drawable.Drawable) frame.getTag(R.id.swipe_original_background);
			if (original != null) {
				frame.setBackground(original);
				frame.setTag(R.id.swipe_original_background, null);
			}
			frame.setElevation(0f);
			disableSwipeButtons(frame);
			frame.animate().translationX(0f).setDuration(200).start();
			swipedOpenFrame = null;
			swipedOpenPosition = Math.toIntExact(RecyclerView.NO_ID);
			reattachTouchHelper();
		}
	}

	private void resetSwipedItemImmediate() {
		if (swipedOpenFrame != null) {
			final View frame = swipedOpenFrame;
			frame.animate().cancel();
			final android.graphics.drawable.Drawable original =
					(android.graphics.drawable.Drawable) frame.getTag(R.id.swipe_original_background);
			if (original != null) {
				frame.setBackground(original);
				frame.setTag(R.id.swipe_original_background, null);
			}
			frame.setElevation(0f);
			disableSwipeButtons(frame);
			frame.setTranslationX(0f);
			swipedOpenFrame = null;
			swipedOpenPosition = Math.toIntExact(RecyclerView.NO_ID);
			reattachTouchHelper();
		}
	}

	private void executeMute(final Conversation conversation) {
		resetSwipedItemImmediate();
		final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity());
		builder.setTitle(R.string.disable_notifications);
		final int[] durations = getResources().getIntArray(R.array.mute_options_durations);
		final CharSequence[] labels = new CharSequence[durations.length];
		for (int i = 0; i < durations.length; ++i) {
			if (durations[i] == -1) {
				labels[i] = getString(R.string.until_further_notice);
			} else {
				labels[i] = com.glaciersecurity.glaciermessenger.utils.TimeFrameUtils.resolve(activity, 1000L * durations[i]);
			}
		}
		builder.setItems(labels, (dialog, which) -> {
			final long till;
			if (durations[which] == -1) {
				till = Long.MAX_VALUE;
			} else {
				till = System.currentTimeMillis() + (durations[which] * 1000L);
			}
			conversation.setMutedTill(till);
			activity.xmppConnectionService.updateConversation(conversation);
			if (activity instanceof OnConversationsListItemUpdated) {
				((OnConversationsListItemUpdated) activity).onConversationsListItemUpdated();
			}
			refresh();
		});
		builder.create().show();
	}

	private void executeUnmute(final Conversation conversation) {
		resetSwipedItemImmediate();
		conversation.setMutedTill(0);
		activity.xmppConnectionService.updateConversation(conversation);
		if (activity instanceof OnConversationsListItemUpdated) {
			((OnConversationsListItemUpdated) activity).onConversationsListItemUpdated();
		}
		refresh();
	}

	private void executePin(final Conversation conversation) {
		resetSwipedItemImmediate();
		final boolean pinned = conversation.getBooleanAttribute(Conversation.ATTRIBUTE_PINNED_ON_TOP, false);
		conversation.setAttribute(Conversation.ATTRIBUTE_PINNED_ON_TOP, !pinned);
		activity.xmppConnectionService.updateConversation(conversation);
        if (activity instanceof OnConversationsListItemUpdated) {
            ((OnConversationsListItemUpdated) activity).onConversationsListItemUpdated();
        }
        refresh();
		final int msgRes = pinned ? R.string.remove_from_favorites : R.string.add_to_favorites;
		Toast.makeText(activity, msgRes, Toast.LENGTH_SHORT).show();
	}

	private void executeSwipeAction(final Conversation conversation, final boolean isGroup) {
		if (isGroup) {
			confirmArchive(conversation);
		} else {
			executeArchive(conversation);
		}
	}

	/**
	 * Ask the user to confirm before archiving a group, since archiving only hides the
	 * chat until they choose to re-enter the group. Closing the swiped item first so the
	 * row returns to its resting position whether the user proceeds or cancels.
	 */
	private void confirmArchive(final Conversation conversation) {
		resetSwipedItemImmediate();
		final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity());
		builder.setTitle(R.string.action_archive_group);
		builder.setMessage(R.string.archive_group_dialog);
		builder.setNegativeButton(R.string.cancel, null);
		builder.setPositiveButton(R.string.confirm, (dialog, which) -> executeArchive(conversation));
		builder.create().show();
	}

	/**
	 * Archive from the conversations overview swipe. This only hides the conversation
	 * locally, keeping its message history (and, for groups, its bookmark) so it can be
	 * restored by re-opening the chat.
	 */
	private void executeArchive(final Conversation conversation) {
		resetSwipedItemImmediate();
		final int pos = conversations.indexOf(conversation);
		if (pos >= 0) {
			conversationsAdapter.remove(conversation, pos);
		}
		updateEmptyView();
		if (activity instanceof OnConversationArchived) {
			((OnConversationArchived) activity).onConversationArchived(conversation);
		}
		activity.xmppConnectionService.markRead(conversation);
		activity.xmppConnectionService.archiveConversation(conversation);
	}

	private void updateSwipeState() {
		if (this.binding == null) {
			return;
		}
		if (this.touchHelper == null) {
			this.touchHelper = new ItemTouchHelper(this.callback);
			this.touchHelper.attachToRecyclerView(this.binding.list);
		} else if (swipedOpenPosition == Math.toIntExact(RecyclerView.NO_ID)) {
			// Re-attach if no item is currently open (may have been detached after swipe)
			this.touchHelper.attachToRecyclerView(this.binding.list);
		}
	}
	@Override
	public void onBackendConnected() {
		setLoadingState(true);
		refresh();
		markInitialLoadComplete();
		refreshQuickConnectMenu();
	}

	@Override
	public void onSaveInstanceState(Bundle bundle) {
		super.onSaveInstanceState(bundle);
		ScrollState scrollState = getScrollState();
		if (scrollState != null) {
			bundle.putParcelable(STATE_SCROLL_POSITION, scrollState);
		}
	}

	private ScrollState getScrollState() {
		if (this.binding == null) {
			return null;
		}
		LinearLayoutManager layoutManager = (LinearLayoutManager) this.binding.list.getLayoutManager();
		int position = layoutManager.findFirstVisibleItemPosition();
		final View view = this.binding.list.getChildAt(0);
		if (view != null) {
			return new ScrollState(position,view.getTop());
		} else {
			return new ScrollState(position, 0);
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		if (activity.xmppConnectionService != null) {
			setLoadingState(true);
			refresh();
			markInitialLoadComplete();
		}
	}

	public void setLoadingState(final boolean isLoading) {
		this.isInitialLoadInProgress = isLoading;
		updateEmptyView();
	}

	public void onConversationDataLoaded() {
		setLoadingState(false);
		refresh();
	}

	public void markInitialLoadComplete() {
		if (isInitialLoadInProgress) {
			setLoadingState(false);
		}
	}

        @Override
        public void onResume() {
                super.onResume();
                // Messages may have arrived (and reordered the list) while the user was on a
                // different screen entirely, not scrolled away from a list they were watching.
                // Don't show the "new conversations" arrow for that — just let them see the
                // current list on return.
                hideNewConversationsIndicator();
                registerCoreTileStateReceiver();
                refreshQuickConnectMenu();
        }

        private void refreshQuickConnectMenu() {
                WireGuardStateMonitor.notifyStateChanged(() -> {
                        final FragmentActivity currentActivity = getActivity();
                        if (currentActivity == null) {
                                return;
                        }
                        currentActivity.runOnUiThread(() -> {
                                if (!isAdded()) {
                                        return;
                                }
                                currentActivity.invalidateOptionsMenu();
                        });
                });
        }

        private void registerCoreTileStateReceiver() {
                if (coreTileStateReceiverRegistered) {
                        return;
                }
                final Context context = getContext();
                if (context == null) {
                        return;
                }
                IntentFilter filter = new IntentFilter(CoreConnectionTileService.ACTION_CORE_CONNECTION_STATE_CHANGED);
                ContextCompat.registerReceiver(context, coreTileStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
                coreTileStateReceiverRegistered = true;
        }

        private void unregisterCoreTileStateReceiver() {
                if (!coreTileStateReceiverRegistered) {
                        return;
                }
                final Context context = getContext();
                if (context != null) {
                        try {
                                context.unregisterReceiver(coreTileStateReceiver);
                        } catch (IllegalArgumentException e) {
                                Log.w(Config.LOGTAG, "Core tile receiver already unregistered", e);
                        }
                }
                coreTileStateReceiverRegistered = false;
        }

	@Override
	public boolean onOptionsItemSelected(final MenuItem item) {
		if (MenuDoubleTabUtil.shouldIgnoreTap()) {
			return false;
		}
		final int id = item.getItemId();
		if (id == R.id.action_search) {
			startActivity(new Intent(getActivity(), SearchActivity.class));
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public void refresh() {
		if (this.binding == null || this.activity == null) {
			return;
		}
		updateSwipeState();
		final long previousTopMessageTime = this.topConversationMessageTime;
		// Captured before the adapter update: RecyclerView tends to preserve the old top
		// item's pixel position across a move-to-top, which pushes the new top item off
		// screen even for a user who was sitting at the very top. So whether we should
		// scroll back to top has to be decided from the pre-update state, not re-derived
		// from wherever the list happens to land after the move.
		final boolean wasTopVisibleBeforeUpdate = isScrolledToTop();
		final List<Conversation> newConversations = new ArrayList<>();
		this.activity.xmppConnectionService.populateWithOrderedConversations(newConversations);
		this.allConversations.clear();
		this.allConversations.addAll(newConversations);
		this.conversationsAdapter.refreshConversations(
				filterConversations(this.allConversations, this.activeFilter));
		updateFilterChips();
		updateEmptyView();
		final Conversation topConversation = this.allConversations.isEmpty() ? null : this.allConversations.get(0);
		final long newTopMessageTime =
				topConversation == null ? -1 : topConversation.getLatestMessage().getTimeSent();
		final String newTopUuid = topConversation == null ? null : topConversation.getUuid();
		this.topConversationMessageTime = newTopMessageTime;
		if (previousTopMessageTime >= 0 && newTopMessageTime > previousTopMessageTime) {
			// A single frame (post()) isn't long enough for the item animator to finish
			// animating the moved item into position 0 — its move animation runs for
			// several frames. Snapping the scroll position while that animation is still
			// in flight is what caused the visible flicker/jump. isRunning(listener) defers
			// the callback until the animator actually reports idle (or calls back
			// immediately if nothing is animating), so the two never fight over the item's
			// on-screen position.
			final RecyclerView.ItemAnimator itemAnimator = binding.list.getItemAnimator();
			if (itemAnimator != null) {
				itemAnimator.isRunning(() -> onNewContentAtTop(wasTopVisibleBeforeUpdate, newTopUuid));
			} else {
				binding.list.post(() -> onNewContentAtTop(wasTopVisibleBeforeUpdate, newTopUuid));
			}
		} else if (isScrolledToTop()) {
			hideNewConversationsIndicator();
		}
		try {
			ScrollState scrollState = pendingScrollState.pop();
			if (scrollState != null) {
				setScrollPosition(scrollState);
			}
		} catch (Exception e) {
		}
	}

        private void setScrollPosition(ScrollState scrollPosition) {
                if (scrollPosition != null) {
                        LinearLayoutManager layoutManager = (LinearLayoutManager) binding.list.getLayoutManager();
                        layoutManager.scrollToPositionWithOffset(scrollPosition.position, scrollPosition.offset);
                }
        }

        /**
         * True when the top row is at least partially on screen (even if cut off), so a
         * conversation that just moved to position 0 can be scrolled fully into view rather
         * than needing the "new conversations" indicator.
         */
        private boolean isScrolledToTop() {
                if (binding == null) {
                        return true;
                }
                final LinearLayoutManager layoutManager = (LinearLayoutManager) binding.list.getLayoutManager();
                if (layoutManager == null) {
                        return true;
                }
                final int firstVisible = layoutManager.findFirstVisibleItemPosition();
                if (firstVisible == RecyclerView.NO_POSITION) {
                        // The RecyclerView hasn't completed a layout pass yet, which happens
                        // right after the fragment's view is recreated (e.g. returning from a
                        // conversation via the back stack), so it can't answer this from the
                        // live layout. Use the scroll position that's about to be restored
                        // instead of assuming either way.
                        final ScrollState pending = pendingScrollState.peek();
                        return pending == null || pending.position == 0;
                }
                return firstVisible == 0;
        }

        private void onNewContentAtTop(boolean wasTopVisibleBeforeUpdate, String newTopUuid) {
                if (binding == null) {
                        return;
                }
                if (wasTopVisibleBeforeUpdate) {
                        // A plain jump, not smoothScrollToPosition: DiffUtil's own move-animation
                        // for this item is still in flight, and animating the container scroll
                        // at the same time visibly fights it (can even look like it scrolls the
                        // wrong way). Snapping straight to position 0 sidesteps that entirely.
                        final LinearLayoutManager layoutManager =
                                        (LinearLayoutManager) binding.list.getLayoutManager();
                        if (layoutManager != null) {
                                layoutManager.scrollToPositionWithOffset(0, 0);
                        }
                        hideNewConversationsIndicator();
                } else if (newTopUuid != null) {
                        conversationsNewAtTop.add(newTopUuid);
                        showNewConversationsIndicator(conversationsNewAtTop.size());
                }
        }

        private void showNewConversationsIndicator(int count) {
                if (binding == null) {
                        return;
                }
                binding.newConversationsIndicator.setVisibility(View.VISIBLE);
                binding.newConversationsCount.setVisibility(View.VISIBLE);
                binding.newConversationsCount.setUnreadCount(count);
        }

        private void hideNewConversationsIndicator() {
                this.conversationsNewAtTop.clear();
                if (binding == null) {
                        return;
                }
                binding.newConversationsIndicator.setVisibility(View.GONE);
        }

        private void updateEmptyView() {
                if (binding == null || conversationsAdapter == null) {
                        return;
                }
                // Show the search bar + filter chips whenever there is at least one conversation.
                final boolean hasAnyConversations = !allConversations.isEmpty();
                binding.listHeader.setVisibility(hasAnyConversations ? View.VISIBLE : View.GONE);

                if (conversationsAdapter.getItemCount() > 0) {
                        binding.loadingList.setVisibility(View.GONE);
                        binding.emptyList.setVisibility(View.GONE);
                        binding.emptyFilter.setVisibility(View.GONE);
                        binding.list.setVisibility(View.VISIBLE);
                        return;
                }

                if (isInitialLoadInProgress) {
                        binding.loadingList.setVisibility(View.VISIBLE);
                        binding.emptyList.setVisibility(View.GONE);
                        binding.emptyFilter.setVisibility(View.GONE);
                        binding.list.setVisibility(View.GONE);
                } else if (hasAnyConversations && activeFilter != FILTER_ALL) {
                        // Conversations exist, but none match the active filter.
                        binding.emptyFilter.setText(
                                        activeFilter == FILTER_UNREAD
                                                        ? R.string.no_unread_conversations
                                                        : R.string.no_group_conversations);
                        binding.loadingList.setVisibility(View.GONE);
                        binding.emptyList.setVisibility(View.GONE);
                        binding.emptyFilter.setVisibility(View.VISIBLE);
                        binding.list.setVisibility(View.GONE);
                } else {
                        binding.loadingList.setVisibility(View.GONE);
                        binding.emptyList.setVisibility(View.VISIBLE);
                        binding.emptyFilter.setVisibility(View.GONE);
                        binding.list.setVisibility(View.GONE);
                }
        }
}
