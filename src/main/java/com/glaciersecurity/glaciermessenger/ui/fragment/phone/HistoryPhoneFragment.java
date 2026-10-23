package com.glaciersecurity.glaciermessenger.ui.fragment.phone;

import static com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider.getOkHttpClient;

import android.content.Context;
import android.content.DialogInterface;

import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.EndPointConfig;
import com.glaciersecurity.glaciermessenger.Endpoints;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.HistoryItem;
import com.glaciersecurity.glaciermessenger.entities.SmsProfile;
import com.glaciersecurity.glaciermessenger.ui.ExternalContactDetailsActivity;
import com.glaciersecurity.glaciermessenger.ui.PhoneModuleActivity;
import com.glaciersecurity.glaciermessenger.ui.adapter.HistoryAdapter;
import com.glaciersecurity.glaciermessenger.ui.interfaces.OnSMSProfileClickListener;
import com.glaciersecurity.glaciermessenger.ui.util.PendingActionHelper;
import com.glaciersecurity.glaciermessenger.ui.util.PendingItem;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider;
import com.glaciersecurity.glaciermessenger.utils.SMSdbInfo;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.Collections;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class HistoryPhoneFragment extends PhoneFragment implements OnSMSProfileClickListener {

    private PhoneModuleActivity activity;

    private final PendingItem<HistoryItem> swipedHistoryLog = new PendingItem<>();
    private final PendingActionHelper pendingActionHelper = new PendingActionHelper();
    private HistoryAdapter adapter;
    private View emptyLayout;
    private ProgressBar progressBar;
    private RecyclerView recyclerView;


    @Override
    public View onCreateView(final LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.history, container, false);
        progressBar = v.findViewById(R.id.progressBar);
        recyclerView = v.findViewById(R.id.history_list);
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        recyclerView.setLayoutManager(layoutManager);
        adapter = new HistoryAdapter(activity);
        emptyLayout = v.findViewById(R.id.empty_history_list);
        adapter.setHistoryClickListener(new HistoryAdapter.OnHistoryClickListener() {
            @Override
            public void onHistoryClick(HistoryItem historyItem) {
                Intent contactDetails = new Intent(getContext(), ExternalContactDetailsActivity.class);
                contactDetails.putExtra("toNumber", historyItem.getRemote());
                startActivity(contactDetails);
            }
        });


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
                    swipedHistoryLog.push(adapter.historyLogList.get(position));
                } catch (IndexOutOfBoundsException e) {
                    return;
                }
                swipeDelete(swipedHistoryLog, position);
            }

        };
        recyclerView.setAdapter(adapter);
        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(simpleItemTouchCallback);
        itemTouchHelper.attachToRecyclerView(recyclerView);
        return v;
    }


    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

    }

    @Override
    public void onResume() {
        super.onResume();
        activity.binding.consoleError.setVisibility(View.GONE);
        if (activity.xmppConnectionServiceBound){
            CallHistoryRequest();
        }
    }

        protected void swipeDelete(PendingItem<HistoryItem> swipedCallLog, int position) {
        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(getContext());
        builder.setMessage(R.string.delete_history_log);
        builder.setTitle(R.string.call_history_confirmation_title);
        builder.setCancelable(false);
        builder.setPositiveButton(R.string.call_history_delete_button, (dialogInterface, i) -> CallHistoryDeleteRequest(swipedCallLog.peek().getSid(), position));
        builder.setNegativeButton(R.string.cancel, (dialogInterface, i) -> {
            pendingActionHelper.undo();
            swipedCallLog.pop();
            adapter.notifyDataSetChanged();
        });
        AlertDialog alertDialog = builder.create();
        alertDialog.show();
    }


    private void CallHistoryDeleteRequest(String callSid, int position) {
        new Thread(()->{
            runOnUiThread(()->{
            activity.binding.consoleError.setVisibility(View.GONE);
            });
            String callHistory =
                    Endpoints.base() + EndPointConfig.DELETE_CALL_HISTORY_URL;
        OkHttpClient client = getOkHttpClient();
        Map<String, String> formFields = new HashMap<>();
        formFields.put("callSid", callSid);
        formFields.put("username", activity.account.getUsername());
        formFields.put("organization", activity.account.getOrganization());
            Request request = null;
            try {
                request = OkHttpClientProvider.buildJwtFormRequestBuilder(callHistory, formFields)
                        .build();
            } catch (IOException e) {
                pendingActionHelper.undo();
                return;
            }
        try (Response response = client.newCall(request).execute()) {
            String responseBody = "{\"message\":\"failure\"}";
            if (response.body() != null) {
                responseBody = response.body().string();
            }
            Gson gson = new Gson();
            SMSdbInfo.CallHistoryDeleteResponse callHistoryDeleteResponse;
            try {
                callHistoryDeleteResponse = gson.fromJson(responseBody, SMSdbInfo.CallHistoryDeleteResponse.class);
            } catch (JsonSyntaxException jsonSyntaxException) {
                handleWebhookJsonSyntaxException("delete call history", responseBody, jsonSyntaxException);
                runOnUiThread(() -> {
                    activity.xmppConnectionService.checkConsoleStatus();
                    Toast.makeText(getContext(), R.string.call_history_delete_failed, Toast.LENGTH_LONG).show();
                    pendingActionHelper.undo();
                    adapter.notifyDataSetChanged();
                    if (adapter.historyLogList.isEmpty()) {
                        emptyLayout.setVisibility(View.VISIBLE);
                    } else {
                        emptyLayout.setVisibility(View.GONE);
                    }
                });
                return;
            }
            runOnUiThread(() -> {
                if (callHistoryDeleteResponse != null && callHistoryDeleteResponse.message.equals("success")) {
                    activity.xmppConnectionService.removeHistoryLog(callSid);
                    adapter.remove(position);
                } else {
                    activity.binding.consoleError.setVisibility(View.VISIBLE);
                    Toast.makeText(getContext(), R.string.call_history_delete_failed, Toast.LENGTH_LONG).show();
                    pendingActionHelper.undo();
                }
                    adapter.notifyDataSetChanged();
                    if (adapter.historyLogList.isEmpty()) {
                        emptyLayout.setVisibility(View.VISIBLE);
                    } else {
                        emptyLayout.setVisibility(View.GONE);
                    }
            });

        } catch (Exception ex) {
            runOnUiThread(()->{
                activity.binding.consoleError.setVisibility(View.VISIBLE);
            });
            pendingActionHelper.undo();

        }

        }).start();
    }


    private void CallHistoryDeleteListRequest() {
        new Thread(()->  {
        String callHistory =
                Endpoints.base() + EndPointConfig.DELETE_ALL_CALL_HISTORY_URL;
        OkHttpClient client = getOkHttpClient();
        Map<String, String> formFields = new HashMap<>();
        formFields.put("number", activity.selectedProfile.getUnformattedNumber());
        formFields.put("username", activity.account.getUsername());
        formFields.put("organization", activity.account.getOrganization());

            Request request = null;
            try {
                request = OkHttpClientProvider.buildJwtFormRequestBuilder(callHistory, formFields)
                    .build();
            } catch (IOException e) {
                activity.xmppConnectionService.checkConsoleStatus();
                return;
            }
        try (Response response = client.newCall(request).execute()) {
            String responseBody = "{\"message\":\"failure\"}";
            if (response.body() != null) {
                responseBody = response.body().string();
            }
            Gson gson = new Gson();
            SMSdbInfo.CallHistoryDeleteResponse callHistoryDeleteResponse;
            try {
                callHistoryDeleteResponse = gson.fromJson(responseBody, SMSdbInfo.CallHistoryDeleteResponse.class);
            } catch (JsonSyntaxException jsonSyntaxException) {
                handleWebhookJsonSyntaxException("delete all call history", responseBody, jsonSyntaxException);
                runOnUiThread(() -> activity.binding.consoleError.setVisibility(View.VISIBLE));
                return;
            }
            if (callHistoryDeleteResponse != null && callHistoryDeleteResponse.message.equals("success")) {
            activity.xmppConnectionService.deleteAllHistoryLogsForProxy(activity.selectedProfile);
            runOnUiThread(() -> {
                adapter.historyLogList.clear();
                progressBar.setVisibility(View.GONE);
                emptyLayout.setVisibility(View.VISIBLE);
                adapter.notifyDataSetChanged();
            });} else {
                runOnUiThread(() -> {
                    activity.binding.consoleError.setVisibility(View.VISIBLE);
                });
                }
        } catch (Exception ex) {
            activity.xmppConnectionService.checkConsoleStatus();
        }
    }).start();

    }

    private void handleWebhookJsonSyntaxException(String context, String responseBody, JsonSyntaxException exception) {
        Log.e(Config.LOGTAG, "Failed to parse " + context + " webhook response", exception);
        activity.xmppConnectionService.checkConsoleStatus();
    }

    @Override
    public void onBackendConnected() {
        CallHistoryRequest();
    }

    @Override
    protected void refresh() {

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
    public void OnSMSProfileClick(SmsProfile sp) {
        CallHistoryRequest();
        if (activity != null) {
            activity.clearMissedCallNotificationForProfile(sp);
        }
    }

    private boolean isQueryRunning = false;


    private void CallHistoryRequest() {
        if (activity.xmppConnectionService == null) {
            return;
        }
        if (isQueryRunning) {
            return;
        }
        isQueryRunning = true;
        runOnUiThread(() -> {
            activity.binding.consoleError.setVisibility(View.GONE);
            ArrayList<HistoryItem> historyItems = activity.xmppConnectionService.getHistoryLogListforProxy(activity.selectedProfile);
            sortHistoryItems(historyItems);
            adapter.setHistoryLogList(historyItems);
            if (historyItems.isEmpty()) {
                emptyLayout.setVisibility(View.VISIBLE);
            } else {
                emptyLayout.setVisibility(View.GONE);
            }
            progressBar.setVisibility(View.VISIBLE);
            markMissedCallsAsRead();
        });
        activity.xmppConnectionService.getSmsInfo().CallHistoryRequest(activity.selectedProfile, activity.xmppConnectionService.getFirstAccount(), new SMSdbInfo.HistoryCallback<Boolean>() {
            @Override
            public void onComplete(boolean result, ArrayList<HistoryItem> arrayList) {
                isQueryRunning = false;
                runOnUiThread(() -> {
                    if (result) {
                        sortHistoryItems(arrayList);
                        adapter.setHistoryLogList(arrayList);
                        markMissedCallsAsRead();
                    } else {
                        activity.binding.consoleError.setVisibility(View.VISIBLE);
                    }
                    checkEmpty();
                });
            }
        });

    }

    private void sortHistoryItems(ArrayList<HistoryItem> historyItems) {
        Collections.sort(historyItems, (o1, o2) -> {
            if (o1.getTimeStamp_date() == null || o2.getTimeStamp_date() == null) {
                return 0;
            }
            return o2.getTimeStamp_date().compareTo(o1.getTimeStamp_date());
        });
    }

    private void checkEmpty() {
        if (adapter.historyLogList.isEmpty()) {
            emptyLayout.setVisibility(View.VISIBLE);
        } else {
            emptyLayout.setVisibility(View.GONE);
        }
        progressBar.setVisibility(View.GONE);
    }

    private void markMissedCallsAsRead() {
        if (activity == null || activity.xmppConnectionService == null || activity.selectedProfile == null) {
            return;
        }
        activity.xmppConnectionService.markMissedCallsReadForProxy(activity.selectedProfile,
                activity::refreshPhoneNavigationBadges);
    }




    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }
    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.action_history_menu, menu);
        super.onCreateOptionsMenu(menu, menuInflater);
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int id = item.getItemId();
        if (id == R.id.delete_all) {
            checkListDelete();
        }
        return super.onOptionsItemSelected(item);
    }


    protected void checkListDelete(){
        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(getContext());
        builder.setMessage(R.string.delete_history_logs);
        builder.setTitle(R.string.dialog_confirmation_title);
        builder.setCancelable(false);
        builder.setPositiveButton(R.string.dialog_delete, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int k)    {
                progressBar.setVisibility(View.VISIBLE);
                new Thread(() -> {
                    try {
                        CallHistoryDeleteListRequest();
                    } catch (Exception e) {}
                }).start();
            }
        });
        builder.setNegativeButton(R.string.cancel, null);
        AlertDialog alertDialog = builder.create();
        alertDialog.show();
    }

}
