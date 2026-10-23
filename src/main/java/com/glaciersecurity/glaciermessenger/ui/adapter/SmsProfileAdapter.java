package com.glaciersecurity.glaciermessenger.ui.adapter;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.app.NotificationManager;
import android.service.notification.StatusBarNotification;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.RecyclerView;


import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.DialogSmsNameBinding;
import com.glaciersecurity.glaciermessenger.entities.SmsProfile;
import com.glaciersecurity.glaciermessenger.services.NotificationService;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.ui.interfaces.OnSMSNameClickListener;
import com.glaciersecurity.glaciermessenger.ui.interfaces.OnSMSProfileClickListener;
import com.glaciersecurity.glaciermessenger.ui.interfaces.OnSMSRemoveClickListener;
import com.glaciersecurity.glaciermessenger.utils.PhoneUtils;
import com.twilio.conversations.Conversation;

import java.util.ArrayList;

import static android.content.Context.CLIPBOARD_SERVICE;

import org.json.JSONObject;

public class SmsProfileAdapter extends RecyclerView.Adapter<SmsProfileAdapter.SMSRecyclerViewHolder> {

    public ArrayList<SmsProfile> smsProfileList = new ArrayList<>();
    private final java.util.Map<String, Integer> unreadCountMap = new java.util.HashMap<>();
    private final OnSMSProfileClickListener listener;
    private final OnSMSRemoveClickListener removeListener;
    private final OnSMSNameClickListener nameListener;
    private ViewGroup viewGroup;
    public Context mContext;
    private XmppConnectionService xmppConnectionService;


    public SmsProfileAdapter(Context mContext, OnSMSProfileClickListener listener, OnSMSRemoveClickListener removeListener, OnSMSNameClickListener nameListener, ArrayList<SmsProfile> smsProfileList) {
        this.smsProfileList = smsProfileList;
        this.removeListener = removeListener;
        this.listener = listener;
        this.nameListener = nameListener;
        this.mContext = mContext;
    }

    public void setXmppConnectionService(XmppConnectionService xmppConnectionService) {
        this.xmppConnectionService = xmppConnectionService;
    }

    public void setSmsProfileList(ArrayList<SmsProfile> smsProfileList) {
        ArrayList<SmsProfile> activeProfiles = new ArrayList<>();
        if (smsProfileList != null) {
            for (SmsProfile sp : smsProfileList) {
                if (sp.isActive()) {
                    activeProfiles.add(sp);
                }
            }
        }
        this.smsProfileList = activeProfiles;
        notifyDataSetChanged();
    }


    @NonNull
    @Override
    public SMSRecyclerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.sms_profile, parent, false);
        viewGroup = parent;
        return new SMSRecyclerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SMSRecyclerViewHolder holder, @SuppressLint("RecyclerView") int position) {
        SmsProfile smsProf = smsProfileList.get(position);
        holder.primaryView.setText(smsProf.getFormattedNumber());
        holder.secondaryView.setVisibility(View.GONE);
        if (smsProf.getNickname() != null && !smsProf.getNickname().isEmpty()
                && !PhoneUtils.areSameNumber(smsProf.getFormattedNumber(), smsProf.getNickname())) {
            holder.primaryView.setText(smsProf.getNickname());
            holder.secondaryView.setText(smsProf.getFormattedNumber());
            holder.secondaryView.setVisibility(View.VISIBLE);
        }
        holder.profileView.setBackgroundColor(smsProf.getColor());

        holder.profView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                listener.OnSMSProfileClick(smsProf);
            }
        });
        holder.profView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                ClipboardManager clipboard = (ClipboardManager) mContext.getSystemService(CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Glacier sms:", smsProf.getFormattedNumber());
                clipboard.setPrimaryClip(clip);
                Toast.makeText(mContext, mContext.getString(R.string.copied_to_clipboard_number, smsProf.getFormattedNumber()), Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        if (shouldShowBadgeForProfile(smsProf)) {
            holder.notificationDot.setVisibility(View.VISIBLE);
        } else {
            holder.notificationDot.setVisibility(View.GONE);
        }
        holder.removeNumBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AlertDialog.Builder builder = new MaterialAlertDialogBuilder(view.getContext());
                builder.setMessage(view.getContext().getString(R.string.sms_release_number_message, smsProfileList.get(position).getFormattedNumber()));
                builder.setTitle(R.string.dialog_confirmation_title);
                builder.setCancelable(true);
                builder.setPositiveButton(R.string.dialog_release, (dialog, id) -> {
                    dialog.dismiss();
                    removeListener.OnSMSRemoveClick(smsProf);
                }
                );
                builder.setNegativeButton(R.string.cancel, null);
                AlertDialog alertDialog = builder.create();
                alertDialog.show();

            }
        });
        holder.nameNumBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(view.getContext());
                builder.setNegativeButton(R.string.cancel, null);
                builder.setTitle(R.string.display_name_title);
                builder.setCancelable(true);

                DialogSmsNameBinding binding = DataBindingUtil.inflate(LayoutInflater.from(view.getContext()), R.layout.dialog_sms_name, null, false);
                binding.smsNickname.setText(smsProf.getNickname());
                binding.deleteNicknameBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        binding.smsNickname.setText("");
                    }
                });

                builder.setView(binding.getRoot());
                builder.setPositiveButton(R.string.update_name_button, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int id) {
                                String smsNickname = binding.smsNickname.getText().toString();
                                dialog.dismiss();

                                nameListener.OnSMSNameClick(smsNickname, smsProf);
                            }
                        }
                );
                AlertDialog dialog = builder.create();
                binding.smsNickname.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                    @Override
                    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                        boolean handled = false;
                        if (actionId == EditorInfo.IME_ACTION_DONE) {
                            handled = true;
                            String smsNickname = binding.smsNickname.getText().toString();
                            dialog.dismiss();

                            nameListener.OnSMSNameClick(smsNickname, smsProf);
                        }
                        return handled;
                    }
                });
                dialog.show();

            }
        });

    }

    @Override
    public int getItemCount() {
        if (smsProfileList == null){
            return 0;
        }
        return smsProfileList.size();
    }


    public void toggleDeleteVisible() {
        if (viewGroup != null) {
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                View v = viewGroup.getChildAt(i);
                ImageButton removeNumBtn = v.findViewById(R.id.remove_sms_btn);
                if (removeNumBtn.getVisibility() == View.VISIBLE) {
                    removeNumBtn.setVisibility(View.GONE);
                } else {
                    removeNumBtn.setVisibility(View.VISIBLE);
                }
            }
        }
    }

    public void toggleNameVisible() {
        if (viewGroup != null) {
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                View v = viewGroup.getChildAt(i);
                ImageButton nameNumBtn = v.findViewById(R.id.name_sms_btn);
                if (nameNumBtn.getVisibility() == View.VISIBLE) {
                    nameNumBtn.setVisibility(View.GONE);
                } else {
                    nameNumBtn.setVisibility(View.VISIBLE);
                }
            }
        }
    }

    public void toggleDeleteOff() {
        if (viewGroup != null) {
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                View v = viewGroup.getChildAt(i);
                ImageButton removeNumBtn = v.findViewById(R.id.remove_sms_btn);
                removeNumBtn.setVisibility(View.GONE);
            }
        }
    }

    public void toggleNameOff() {
        if (viewGroup != null) {
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                View v = viewGroup.getChildAt(i);
                ImageButton addNumBtn = v.findViewById(R.id.name_sms_btn);
                addNumBtn.setVisibility(View.GONE);
            }
        }
    }

    public void removeProfile(SmsProfile smsProfile, XmppConnectionService xmppConnectionService) {
        for (int i = 0; i < smsProfileList.size(); i++) {
            if (smsProfile.getId().equals(smsProfileList.get(i).getId())) {
                smsProfileList.remove(i);
                if (xmppConnectionService != null) {
                    xmppConnectionService.deleteSmsProfile(smsProfile);
                    xmppConnectionService.getSmsInfo().removeDbProfs(smsProfile);

                    if (xmppConnectionService.getSmsInfo().getSelectedProfile().getId().equals(smsProfile.getId())) {
                        xmppConnectionService.getSmsInfo().setSelectedSmsProfile(null);
                    }
                }

                break;
            }
        }
        notifyDataSetChanged();

    }

    public void addProfile(SmsProfile smsProfile, XmppConnectionService xmppConnectionService) {
        smsProfile.setActive(true);
        smsProfileList.add(smsProfile);
        if (xmppConnectionService != null) {
            xmppConnectionService.updateSmsProfileList(smsProfile);
            xmppConnectionService.getSmsInfo().addDbProfs(smsProfile);
        }
        notifyDataSetChanged();
    }

    public void nickname(SmsProfile smsProfile, XmppConnectionService xmppConnectionService) {
        for (int i = 0; i < smsProfileList.size(); i++) {
            if (smsProfile.getId().equals(smsProfileList.get(i).getId())) {
                smsProfileList.set(i, smsProfile);
                if (xmppConnectionService != null) {
                    xmppConnectionService.updateSmsProfileList(smsProfile);
                    xmppConnectionService.getSmsInfo().removeDbProfs(smsProfile);
                    xmppConnectionService.getSmsInfo().addDbProfs(smsProfile);
                }
                notifyItemChanged(i);
                return;
            }
        }
    }

    public void clearUnreadForProfile(SmsProfile smsProfile) {
        unreadCountMap.remove(smsProfile.getId());
        notifyDataSetChanged();
    }

    public void setUnreadCountForProfile(Conversation conversation) {
        try {
            JSONObject attrs = conversation.getAttributes().getJSONObject();
            if (attrs != null && attrs.has("identity_number")) {
                String identity = PhoneUtils.getStripPhoneNumberWithCountryCode(attrs.getString("identity_number"));
                for (SmsProfile sp : smsProfileList) {
                    if (PhoneUtils.areSameNumber(identity, sp.getUnformattedNumber())) {
                        int count = unreadCountMap.getOrDefault(sp.getId(), 0);
                        unreadCountMap.put(sp.getId(), count + 1);
                        notifyDataSetChanged();
                        break;
                    }
                }
            }
        } catch (Exception e) {
        }
    }

    public boolean shouldShowBadgeForProfile(SmsProfile smsProfile) {
        if (smsProfile == null) {
            return false;
        }
        return hasAssociatedNotification(smsProfile)
                || hasUnreadVoicemail(smsProfile)
                || hasUnreadSms(smsProfile);
    }

    private boolean hasUnreadVoicemail(SmsProfile smsProfile) {
        return xmppConnectionService != null
                && xmppConnectionService.getUnreadVoicemailsCount(smsProfile) > 0;
    }

    private boolean hasUnreadSms(SmsProfile smsProfile) {
        return xmppConnectionService != null
                && xmppConnectionService.getSmsInfo() != null
                && xmppConnectionService.getSmsInfo().hasUnreadForProfile(smsProfile.getUnformattedNumber());
    }

    private boolean hasAssociatedNotification(SmsProfile smsProfile) {
        NotificationManager notificationManager = mContext.getSystemService(NotificationManager.class);
        if (notificationManager == null) {
            return false;
        }
        String profileTag = PhoneUtils.getStripPhoneNumberWithCountryCode(smsProfile.getUnformattedNumber());
        StatusBarNotification[] notifications = notificationManager.getActiveNotifications();
        if (notifications == null) {
            return false;
        }
        for (StatusBarNotification notification : notifications) {
            if (notification == null) {
                continue;
            }
            int id = notification.getId();
            if (id != NotificationService.MISSED_PHONE_CALL_NOTIFICATION_ID
                    && id != NotificationService.VOICEMAIL_NOTIFICATION_ID
                    && id != NotificationService.SMS_NOTIFICATION_ID) {
                continue;
            }
            String notificationTag = PhoneUtils.getStripPhoneNumberWithCountryCode(notification.getTag());
            if (profileTag != null && profileTag.equals(notificationTag)) {
                return true;
            }
        }
        return false;
    }

    public static class SMSRecyclerViewHolder extends RecyclerView.ViewHolder {
        public final View profView;
        TextView primaryView;
        TextView secondaryView;
        ImageView notificationDot;
        ImageButton removeNumBtn;
        ImageButton nameNumBtn;
        View profileView;


        public SMSRecyclerViewHolder(View view) {
            super(view);
            profileView = view.findViewById(R.id.sms_profile_view);
            primaryView = (TextView) view.findViewById(R.id.sms_profile_primary_view);
            secondaryView = (TextView) view.findViewById(R.id.sms_profile_secondary_view);
            notificationDot = view.findViewById(R.id.sms_profile_notification_dot);
            removeNumBtn = view.findViewById(R.id.remove_sms_btn);
            nameNumBtn = view.findViewById(R.id.name_sms_btn);
            profView = view;
        }

    }
}
