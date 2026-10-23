package com.glaciersecurity.glaciermessenger.ui.adapter;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.HistoryItem;
import com.glaciersecurity.glaciermessenger.ui.PhoneModuleActivity;
import com.glaciersecurity.glaciermessenger.utils.NativeContactsUtils;

import java.util.ArrayList;
import java.util.Collections;


public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.HistoryRecyclerViewHolder> {

    public final ArrayList<HistoryItem> historyLogList = new ArrayList<>();
    private OnHistoryClickListener listener;
    private PhoneModuleActivity activity;


    public HistoryAdapter( PhoneModuleActivity activity) {
        this.activity = activity;
    }

    public void setHistoryLogList(ArrayList<HistoryItem> historyLogList) {
        this.historyLogList.clear();
        this.historyLogList.addAll(historyLogList);
        Collections.sort(this.historyLogList, (o1, o2) -> {
            if (o1.getTimeStamp_date() == null || o2.getTimeStamp_date() == null) {
                return 0;
            }
            return o2.getTimeStamp_date().compareTo(o1.getTimeStamp_date());

        });
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public HistoryRecyclerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.history_log, parent, false);
        return new HistoryRecyclerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull HistoryRecyclerViewHolder holder, @SuppressLint("RecyclerView") int position) {
        HistoryItem historyItem = historyLogList.get(position);
        holder.caller.setText(NativeContactsUtils.getContactNameIfExists(activity, historyItem.getRemote()));
        holder.callTime.setText(historyItem.getDateCreated());
        holder.status.setText(historyItem.getStatus());
        holder.direction.setText(historyItem.getDirection());
        switch (historyItem.getDirection()) {
            case "inbound": {
                if (historyItem.getStatus().equals("no-answer")) {
                    holder.icon.setBackgroundResource(R.drawable.call_status_missed);
                } else {
                    holder.icon.setBackgroundResource(R.drawable.call_status_incoming);
                }
                break;
            }
            case "outbound-dial":
                if (historyItem.getStatus().equals("no-answer")) {
                    holder.icon.setBackgroundResource(R.drawable.call_status_outgoing_noanswer);
                } else {
                    holder.icon.setBackgroundResource(R.drawable.call_status_outgoing);
                }
                break;
            default:
                holder.icon.setBackgroundResource(R.drawable.ic_phone_24dp);
        }
        holder.itemView.setOnClickListener(v -> listener.onHistoryClick(historyItem));
        holder.deleteCB.setChecked(historyItem.isSelected());
        if (historyItem.isDeletePendingView()) {
            holder.deleteCBLayout.setVisibility(View.VISIBLE);
            holder.callTime.setVisibility(View.GONE);
        } else {
            holder.deleteCBLayout.setVisibility(View.GONE);
            holder.callTime.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public int getItemCount() {
        return historyLogList.size();
    }


    public void setHistoryClickListener(OnHistoryClickListener listener) {
        this.listener = listener;
    }

    public void remove(int position) {
        historyLogList.remove(position);
        notifyDataSetChanged();
    }

    public interface OnHistoryClickListener {
        void onHistoryClick(HistoryItem historyItem);
    }

    static class HistoryRecyclerViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView caller;
        TextView callTime;
        TextView status;
        TextView direction;
        CheckBox deleteCB;
        RelativeLayout deleteCBLayout;

        public HistoryRecyclerViewHolder(View view) {
            super(view);
            icon = view.findViewById(R.id.icon);
            caller = view.findViewById(R.id.caller);
            callTime = view.findViewById(R.id.hist_call_time);
            status = view.findViewById(R.id.hist_status);
            direction = view.findViewById(R.id.hist_direction);
            deleteCB = view.findViewById(R.id.deleteCheckBox);
            deleteCBLayout = view.findViewById(R.id.deleteCheckBoxLayout);
        }
    }
}