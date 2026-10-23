package com.glaciersecurity.glaciermessenger.ui.adapter;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.NativeContact;
import com.glaciersecurity.glaciermessenger.entities.PhoneNumberEntry;
import com.glaciersecurity.glaciermessenger.entities.SmsProfile;
import com.glaciersecurity.glaciermessenger.entities.TwilioConversationEntity;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.ui.ExternalCallActivity;
import com.glaciersecurity.glaciermessenger.ui.SmsConvActivity;
import com.glaciersecurity.glaciermessenger.ui.XmppActivity;
import com.glaciersecurity.glaciermessenger.ui.StartSmsOrCallDialog;
import com.glaciersecurity.glaciermessenger.utils.PhoneUtils;

public class ContactPhoneNumberAdapter extends RecyclerView.Adapter<ContactPhoneNumberAdapter.PhoneContactRecyclerViewHolder> {
    private XmppActivity activity;
    private NativeContact contact;
    private XmppConnectionService xmppConnectionService;
    private SmsProfile smsProfile;
    public ContactPhoneNumberAdapter(XmppActivity activity, NativeContact contact) {
        this.activity = activity;
        this.contact = contact;
        this.xmppConnectionService = activity.xmppConnectionService;
        this.smsProfile = xmppConnectionService.getSmsInfo().getSelectedProfile();
    }

    @NonNull
    @Override
    public PhoneContactRecyclerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.phone_contact_entry,parent,false);
        return new PhoneContactRecyclerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PhoneContactRecyclerViewHolder holder, int position) {
        PhoneNumberEntry numEntry = contact.getNumbers().get(position);
        String num = numEntry.phoneNumber;
        String type = numEntry.phoneType;
        holder.phoneNumber.setText(PhoneUtils.reformatNumber(num));
        if (type!= null && !type.isEmpty()) {
            holder.phoneType.setVisibility(View.VISIBLE);
            holder.phoneType.setText(type);
        } else {
            holder.phoneType.setVisibility(View.GONE);
        }
        holder.callButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (activity.xmppConnectionService == null || smsProfile == null) {
                    return;
                }
                if (num != null && !num.isEmpty()) {
                    String toPhoneNumber = PhoneUtils.getStripPhoneNumber(num);
                    if (!xmppConnectionService.getSmsInfo().containsProxy(toPhoneNumber)) {
                        Intent callActivity = new Intent(activity, ExternalCallActivity.class);
                        callActivity.setAction(Config.ACTION_OUTGOING_CALL);
                        callActivity.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                        callActivity.putExtra("toNumber", toPhoneNumber);
                        callActivity.putExtra(Config.TO_PROXY, smsProfile.getUnformattedNumber());

                        activity.startActivity(callActivity);
                        StartSmsOrCallDialog.dismiss();
                    }
                }
            }
        });

        holder.smsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TwilioConversationEntity conv = xmppConnectionService.getConversationsBetweenContactAndProxy(num, smsProfile);
                Intent intent = new Intent(activity, SmsConvActivity.class);
                if (conv != null) {
                    activity.startActivity(intent.putExtra("conv_sid", conv.getConv_sid()).putExtra("friendly_name", conv.getFriendlyName()));
                } else {
                    activity.startActivity(intent.putExtra("friendly_name", num));
                }
                StartSmsOrCallDialog.dismiss();
            }
        });

    }

    @Override
    public int getItemCount() {
        return contact.getNumbers().size();
    }

    public static class PhoneContactRecyclerViewHolder extends RecyclerView.ViewHolder {
        TextView phoneNumber;
        TextView phoneType;
        ImageView callButton;
        ImageView smsButton;


        public PhoneContactRecyclerViewHolder(View view) {
            super(view);
            phoneNumber = view.findViewById(R.id.phone_number);
            phoneType = view.findViewById(R.id.phone_type);
            callButton = view.findViewById(R.id.start_call_button);
            smsButton = view.findViewById(R.id.start_sms_button);
        }
    }
}
