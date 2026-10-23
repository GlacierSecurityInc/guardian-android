package com.glaciersecurity.glaciermessenger.ui.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.HistoryItem;

import java.util.ArrayList;
import java.util.Collections;

public class ExternalContactHistoryAdapter extends RecyclerView.Adapter<ExternalContactHistoryAdapter.HistoryRecyclerViewHolder> {

	public ArrayList<HistoryItem> historyLogList = new ArrayList<>();
	private ViewGroup viewGroup;
	public Context mContext;

        public ExternalContactHistoryAdapter(ArrayList<HistoryItem> log) {
                historyLogList = log;
        }

        public void setHistoryLogList(ArrayList<HistoryItem> historyLogList) {
                this.historyLogList = historyLogList;
                Collections.sort(this.historyLogList, (o1, o2) -> {
                        if (o1.getTimeStamp_date() == null || o2.getTimeStamp_date() == null) {
                                return 0;
                        }
                        return o1.getTimeStamp_date().compareTo(o2.getTimeStamp_date());

                });
                notifyDataSetChanged();
        }

	@NonNull
	@Override
	public HistoryRecyclerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.contact_log, parent, false);
		viewGroup = parent;
		return new HistoryRecyclerViewHolder(view);
	}

	@Override
	public void onBindViewHolder(@NonNull HistoryRecyclerViewHolder holder, @SuppressLint("RecyclerView") int position) {
		HistoryItem historyItem = historyLogList.get(position);
		holder.status.setText(historyItem.getStatus());
		holder.callTime.setText(historyItem.getDateCreated());
		holder.status.setText(historyItem.getStatus());
		holder.duration.setText(historyItem.getDuration());
		switch(historyItem.getDirection()) {
			case "inbound": {
				if (historyItem.getStatus().equals("no-answer")) {
					holder.icon.setBackgroundResource(R.drawable.call_status_missed);
				} else {
					holder.icon.setBackgroundResource(R.drawable.call_status_incoming);
				}
				break;
			}
			case "outbound-dial": {
				if (historyItem.getStatus().equals("no-answer")) {
					holder.icon.setBackgroundResource(R.drawable.call_status_outgoing_noanswer);
				} else {
					holder.icon.setBackgroundResource(R.drawable.call_status_outgoing);
				}
				break;
			}
			default: holder.icon.setBackgroundResource(R.drawable.ic_phone_24dp);
		}

	}

	@Override
	public int getItemCount() {
		return historyLogList.size();
	}



	public static class HistoryRecyclerViewHolder extends RecyclerView.ViewHolder {
		ImageView icon;
		TextView caller;
		TextView callTime;

		TextView status;
		TextView direction;
		TextView duration;


		public HistoryRecyclerViewHolder(View view) {
			super(view);
			icon = view.findViewById(R.id.icon);
			caller = view.findViewById(R.id.caller);
			callTime = view.findViewById(R.id.hist_call_time);
			status = view.findViewById(R.id.hist_status);
			direction = view.findViewById(R.id.hist_direction);
			duration = view.findViewById(R.id.hist_duration);

		}
	}

	public interface OnHistoryClickListener {
		void onHistoryClick(View view, HistoryItem historyItem);
	}

}