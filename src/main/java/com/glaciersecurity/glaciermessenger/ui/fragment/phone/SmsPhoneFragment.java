package com.glaciersecurity.glaciermessenger.ui.fragment.phone;


import android.content.Context;
import android.content.DialogInterface;

import androidx.fragment.app.FragmentActivity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.SmsProfile;
import com.glaciersecurity.glaciermessenger.entities.TwilioConversationEntity;
import com.glaciersecurity.glaciermessenger.ui.ExtendedFabSizeChanger;
import com.glaciersecurity.glaciermessenger.ui.PhoneModuleActivity;
import com.glaciersecurity.glaciermessenger.ui.RecyclerViews;
import com.glaciersecurity.glaciermessenger.ui.adapter.SmsConversationsAdapter;
import com.glaciersecurity.glaciermessenger.ui.util.PendingActionHelper;
import com.glaciersecurity.glaciermessenger.ui.util.PendingItem;
import com.glaciersecurity.glaciermessenger.ui.util.SmsUnavailableDialogHelper;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.twilio.conversations.Message;

import java.util.ArrayList;

public class SmsPhoneFragment extends PhoneFragment implements SmsConversationsAdapter.OnSmsConvAdapterLoadedListener {

    private ExtendedFloatingActionButton fab_contact;
    private View emptyLayout;
    private View loadingLayout;
    private FloatingActionButton scrollToTopButton;
    private SmsConversationsAdapter smsConversationsAdapter;
    private PhoneModuleActivity activity;
    private RecyclerView recyclerViewConversations;
    private final PendingItem<TwilioConversationEntity> swipedConv = new PendingItem<>();
    private final PendingActionHelper pendingActionHelper = new PendingActionHelper();
    private LinearLayoutManager layoutManager;


    @Override
    public View onCreateView(final LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.list_sms, container, false);
        scrollToTopButton = v.findViewById(R.id.scroll_to_top_button);
        scrollToTopButton.setOnClickListener(this.mScrollButtonListener);

        fab_contact = v.findViewById(R.id.fab_chat);
        fab_contact.setOnClickListener(view -> SmsUnavailableDialogHelper.show(activity));

        emptyLayout = v.findViewById(R.id.empty_list);
        loadingLayout = v.findViewById(R.id.loading_list);

        recyclerViewConversations = v.findViewById(R.id.choose_conversation_list);
        recyclerViewConversations.addOnScrollListener(ExtendedFabSizeChanger.of(fab_contact));
        recyclerViewConversations.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                toggleScrollUpButton();
            }
        });

        layoutManager = new LinearLayoutManager(getContext());
        recyclerViewConversations.setLayoutManager(layoutManager);
        smsConversationsAdapter = new SmsConversationsAdapter(activity,activity, new ArrayList<>(), this);
        recyclerViewConversations.setAdapter(smsConversationsAdapter);
        ItemTouchHelper.SimpleCallback simpleItemTouchCallback = new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onChildDraw(Canvas c, RecyclerView recyclerView,
                                    RecyclerView.ViewHolder viewHolder, float dX, float dY,
                                    int actionState, boolean isCurrentlyActive) {
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);

                View itemView = viewHolder.itemView;
                float height = (float) itemView.getBottom() - itemView.getTop();

                Paint paint = new Paint();
                paint.setColor(getResources().getColor(R.color.red_700));  // Red background

                if (dX > 0) { // Swiping right
                    c.drawRect((float) itemView.getLeft(), (float) itemView.getTop(), dX,
                            (float) itemView.getBottom(), paint);
                } else if (dX < 0) { // Swiping left
                    c.drawRect((float) itemView.getRight() + dX, (float) itemView.getTop(),
                            (float) itemView.getRight(), (float) itemView.getBottom(), paint);
                }

                // Draw trash icon (example using Bitmap)
                Drawable icon = ContextCompat.getDrawable(activity, R.drawable.ic_delete_24dp); // your delete icon
                int iconMargin = (int) (height - icon.getIntrinsicHeight()) / 2;
                int iconTop = itemView.getTop() + (itemView.getHeight() - icon.getIntrinsicHeight()) / 2;
                int iconBottom = iconTop + icon.getIntrinsicHeight();

                if (dX > 0) { // Right swipe
                    int iconLeft = itemView.getLeft() + iconMargin;
                    int iconRight = iconLeft + icon.getIntrinsicWidth();
                    icon.setBounds(iconLeft, iconTop, iconRight, iconBottom);
                    icon.draw(c);
                } else if (dX < 0) { // Left swipe
                    int iconRight = itemView.getRight() - iconMargin;
                    int iconLeft = iconRight - icon.getIntrinsicWidth();
                    icon.setBounds(iconLeft, iconTop, iconRight, iconBottom);
                    icon.draw(c);
                }
            }



            @Override
            public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
                pendingActionHelper.execute();
                int position = viewHolder.getLayoutPosition();
                try {
                    swipedConv.push(smsConversationsAdapter.twilioConversations.get(position));
                } catch (IndexOutOfBoundsException e) {
                    return;
                }
                swipeDelete(swipedConv, position);
            }

        };
        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(simpleItemTouchCallback);
        itemTouchHelper.attachToRecyclerView(recyclerViewConversations);
        toggleScrollUpButton();
        return v;
    }

    protected void swipeDelete(PendingItem<TwilioConversationEntity> swipedConvo, int position) {
        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(getContext());
        builder.setMessage(R.string.delete_history_log);
        builder.setTitle(R.string.dialog_confirmation_title);
        builder.setCancelable(false);
        builder.setPositiveButton(R.string.dialog_delete,(dialogInterface, i) -> {
            smsConversationsAdapter.remove(swipedConvo.peek(), position);
            showLoading(false);
        });
        builder.setNegativeButton(R.string.cancel, (dialogInterface, i) -> {
            pendingActionHelper.undo();
            swipedConvo.pop();
            smsConversationsAdapter.notifyDataSetChanged();
        });
        AlertDialog alertDialog = builder.create();
        alertDialog.show();
    }

    private void toggleScrollUpButton() {
        if (smsConversationsAdapter == null) {
            scrollToTopButton.setEnabled(false);
            scrollToTopButton.hide();
            return;
        }
        if (RecyclerViews.scrolledToTop(recyclerViewConversations)) {
            scrollToTopButton.setEnabled(false);
            scrollToTopButton.hide();
        } else {
            scrollToTopButton.setEnabled(true);
            scrollToTopButton.show();
        }
    }

    private final View.OnClickListener mScrollButtonListener = v -> scrollToTop();


    private void stopScrolling() {
        long now = SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        recyclerViewConversations.dispatchTouchEvent(cancel);
    }

    private void scrollToTop() {
        stopScrolling();
        if (recyclerViewConversations != null) {
            recyclerViewConversations.scrollToPosition(0);
        }
        toggleScrollUpButton();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.action_sms_menu, menu);
        super.onCreateOptionsMenu(menu, menuInflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.delete_all) {
            checkListDelete();
        }
        return super.onOptionsItemSelected(item);
    }

    protected void checkListDelete(){
        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(getContext());
        builder.setMessage(R.string.delete_sms_history);
        builder.setTitle(R.string.dialog_confirmation_title);
        builder.setCancelable(false);
        builder.setPositiveButton(R.string.dialog_delete,new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int k)    {
                smsConversationsAdapter.removeAllForProxy(activity.selectedProfile);
                showLoading(false);
            }
        });
        builder.setNegativeButton(R.string.cancel, null);
        AlertDialog alertDialog = builder.create();
        alertDialog.show();
    }

    public void receivedNewMessage() {
        refreshList();
        showLoading(false);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshList();
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (activity.xmppConnectionServiceBound){
            refreshList();

        }
    }

    @Override
    public void OnSMSProfileClick(SmsProfile smsProfile) {
        if (smsProfile != null){
           loadConversations();
        } else {
            refreshList();
            showLoading(false);
        }
    }

    public void showLoading(boolean isLoading) {
        if (!isLoading){
            if (loadingLayout != null) {
                loadingLayout.setVisibility(View.GONE);
            }
            if (emptyLayout != null) {
                if (smsConversationsAdapter != null && smsConversationsAdapter.getItemCount() > 0) {
                    emptyLayout.setVisibility(View.GONE);
                } else {
                    emptyLayout.setVisibility(View.VISIBLE);
                }
            }
        } else {
            if (emptyLayout != null) {
                emptyLayout.setVisibility(View.GONE);
            }
            if (loadingLayout != null) {
                if (smsConversationsAdapter != null && smsConversationsAdapter.getItemCount() > 0) {
                    loadingLayout.setVisibility(View.GONE);
                } else {
                    loadingLayout.setVisibility(View.VISIBLE);
                }
            }
        }
    }



    @Override
    protected void refresh() {

    }

    @Override
    public void onBackendConnected() {
        loadConversations();
    }


    private void loadConversations() {
        if (activity == null || activity.xmppConnectionService == null) {
            return;
        }
        showLoading(true);

        if (activity.account != null && !activity.xmppConnectionService.hasPhoneLoaded()) {
            if (!activity.xmppConnectionService.isPhoneSyncInProgress()) {
                activity.xmppConnectionService.initializeConversationsClient(activity.account, false);
            }
            return;
        }

        if (activity.xmppConnectionService.getSmsInfo() != null &&
                activity.xmppConnectionService.getSmsInfo().getSelectedProfile() != null) {
            refreshList();
        } else {
            showLoading(false);
        }
    }

    public void refreshList() {
        if (activity == null) {
            FragmentActivity currentActivity = getActivity();
            if (currentActivity instanceof PhoneModuleActivity) {
                activity = (PhoneModuleActivity) currentActivity;
            }
        }

        if (activity == null || activity.conversationsManager == null) {
            showLoading(false);
            return;
        }

        if (activity.selectedProfile == null) {
            smsConversationsAdapter.setConversationsObjList(new ArrayList<>());
            showLoading(false);
            return;
        }

        smsConversationsAdapter.setConversationsObjList(activity.conversationsManager.getConversationsForProxy(activity.selectedProfile));
        showLoading(false);
    }



    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (context instanceof PhoneModuleActivity) {
            this.activity = (PhoneModuleActivity) context;
        } else {
            throw new IllegalStateException(
                    "Trying to attach fragment to activity that is not the PHoneModuleActivity");
        }
    }



    @Override
    public void onSmsConvAdapterLoaded() {
        toggleScrollUpButton();
        showLoading(false);
    }
}

