package com.glaciersecurity.glaciermessenger.ui.adapter;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.recyclerview.widget.RecyclerView;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.VoicemailRecord;
import com.glaciersecurity.glaciermessenger.ui.PhoneModuleActivity;
import com.glaciersecurity.glaciermessenger.ui.util.PlaybackSpeedOptions;
import com.glaciersecurity.glaciermessenger.utils.NativeContactsUtils;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;


public class VoicemailAdapter extends RecyclerView.Adapter<VoicemailAdapter.VoicemailRecyclerViewHolder> {
        private static final String[] FALLBACK_DATE_PATTERNS = {"M/d/yyyy h:mm a", "M/d/yyyy", "yyyy-MM-dd"};
        int selectedPosition = -1;
        int lastSelectedPosition = -1;
        private boolean deleteMode = false;
		private boolean selectAllMode = false;


	public final ArrayList<VoicemailRecord> voicemailLogList = new ArrayList<>();
	public PhoneModuleActivity activity;

        private OnVoicemailClickListener listener;
        private ProgressBar currentProgressBar;
        private int currentProgressValue = 0;
        private int currentProgressMax = 0;
        private int playbackSpeedIndex = PlaybackSpeedOptions.defaultIndex();

	public ArrayList<VoicemailRecord> getVoicemailLogList() {
		return voicemailLogList;
	}

        public void setVoicemailLogList(ArrayList<VoicemailRecord> voicemailLogList) {
                this.voicemailLogList.clear();
                this.voicemailLogList.addAll(voicemailLogList);
                Collections.sort(this.voicemailLogList, (left, right) -> {
                        Date leftDate = getComparableDate(left);
                        Date rightDate = getComparableDate(right);

                        if (leftDate == null && rightDate == null) {
                                String leftTime = left.getTime();
                                String rightTime = right.getTime();

                                if (leftTime == null && rightTime == null) {
                                        return 0;
                                } else if (leftTime == null) {
                                        return 1;
                                } else if (rightTime == null) {
                                        return -1;
                                }

                                return rightTime.compareTo(leftTime);
                        }

                        if (leftDate == null) {
                                return 1;
                        }

                        if (rightDate == null) {
                                return -1;
                        }

                        return rightDate.compareTo(leftDate);
                });
                notifyDataSetChanged();
        }

        private static Date getComparableDate(VoicemailRecord record) {
                Date parsed = record.getTimeStamp_date();
                if (parsed != null) {
                        return parsed;
                }

                Date fromOriginal = tryParseFallbackDate(record.getTimeStamp_string());
                if (fromOriginal != null) {
                        return fromOriginal;
                }

                return tryParseFallbackDate(record.getTime());
        }

        private static Date tryParseFallbackDate(String raw) {
                if (raw == null) {
                        return null;
                }

                String value = raw.trim();
                if (value.isEmpty()) {
                        return null;
                }

                for (String pattern : FALLBACK_DATE_PATTERNS) {
                        SimpleDateFormat formatter = new SimpleDateFormat(pattern, Locale.US);
                        formatter.setLenient(false);
                        try {
                                return formatter.parse(value);
                        } catch (ParseException ignore) {
                                // try the next pattern
                        }
                }

                return null;
        }

        public boolean isDeleteMode() {
                return deleteMode;
        }

        public void setDeleteMode(boolean mode) {
                this.deleteMode = mode;
                for (VoicemailRecord vr : voicemailLogList) {
                        vr.setDeletePendingView(mode);
                        if (!mode) {
                                vr.setSelected(false);
                        }
                }
                notifyDataSetChanged();
        }

        public void selectAll() {
                for (VoicemailRecord vr : voicemailLogList) {
                        if (vr.isDeletePendingView()) {
                                vr.setSelected(true);
                        }
                }
				selectAllMode = true;
                notifyDataSetChanged();
        }

	public void unselectAll() {
		for (VoicemailRecord vr : voicemailLogList) {
			if (vr.isDeletePendingView()) {
				vr.setSelected(false);
			}
		}
		selectAllMode = false;
		notifyDataSetChanged();
	}

	public VoicemailAdapter(PhoneModuleActivity activity) {
		this.activity = activity;
	}

	@NonNull
	@Override
	public VoicemailRecyclerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.voicemail_log, parent, false);
		return new VoicemailRecyclerViewHolder(view);
	}

	@Override
        public void onBindViewHolder(@NonNull VoicemailRecyclerViewHolder holder, @SuppressLint("RecyclerView") int position) {
                VoicemailRecord record = voicemailLogList.get(position);
                holder.duration.setText(record.getDuration());
                holder.time.setText(record.getTime());
                holder.from.setText(NativeContactsUtils.getContactNameIfExists(activity,record.getFrom()));
                holder.delete.setVisibility(View.VISIBLE);
                if (selectedPosition == holder.getBindingAdapterPosition()) {
                        setCurrentProgressBar(holder.progress);
                        holder.progress.setMax(currentProgressMax);
                        holder.progress.setProgress(currentProgressValue);
                } else {
                        holder.progress.setProgress(0);
                }
        holder.pause.setVisibility(View.GONE);
        holder.play.setVisibility(View.VISIBLE);
        holder.playbackSpeed.setText(
                PlaybackSpeedOptions.labelForIndex(
                        holder.playbackSpeed.getContext(), playbackSpeedIndex));
        holder.playbackSpeed.setContentDescription(
                PlaybackSpeedOptions.contentDescriptionForIndex(
                        holder.playbackSpeed.getContext(), playbackSpeedIndex));
                switch(record.getStatus()){
                        case NEW:
                                holder.badge.setVisibility(View.VISIBLE);
                                holder.itemView.setVisibility(View.VISIBLE);
				holder.itemView.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
				break;
			case READ:
				holder.badge.setVisibility(View.GONE);
				holder.itemView.setVisibility(View.VISIBLE);
				holder.itemView.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
				break;
			case DELETED:
				holder.itemView.setVisibility(View.GONE);
				holder.itemView.setLayoutParams(new RecyclerView.LayoutParams(0, 0));
				holder.badge.setVisibility(View.GONE);
				break;
		}
		if (selectedPosition == holder.getBindingAdapterPosition()) {
			holder.audioControls.setVisibility(View.VISIBLE);
            holder.progress.setVisibility(View.VISIBLE);
		} else {
			holder.audioControls.setVisibility(View.GONE);
            holder.progress.setVisibility(View.GONE);
        }

		holder.itemView.setOnClickListener(v -> {
			lastSelectedPosition = selectedPosition;
			selectedPosition = holder.getBindingAdapterPosition();
			holder.pause.setVisibility(View.GONE);
			holder.play.setVisibility(View.VISIBLE);
			listener.onVoicemailClick(v, record);
			holder.badge.setVisibility(View.GONE);
            notifyItemChanged(lastSelectedPosition);
            notifyItemChanged(selectedPosition);

		});
                holder.play.setOnClickListener(v -> {
                        holder.play.setVisibility(View.GONE);
                        holder.pause.setVisibility(View.VISIBLE);
                        listener.onVoicemailClick_Play();
                });
                holder.pause.setOnClickListener(v -> {
                        holder.play.setVisibility(View.VISIBLE);
                        holder.pause.setVisibility(View.GONE);
                        listener.onVoicemailClick_Pause();
                });
                holder.playbackSpeed.setOnClickListener(v -> {
                        if (listener != null) {
                                listener.onVoicemailClick_Speed(v);
                        }
                });
                holder.call_back.setOnClickListener(v -> {
                        listener.onVoicemailClick_CallBack(record);
                });
		holder.info.setOnClickListener(v -> {
			listener.onVoicemailClick_Info(record);
		});
                holder.delete.setOnClickListener(v -> {
                        listener.onVoicemailClick_Delete(record);
                });

                holder.deleteCB.setOnCheckedChangeListener(null);
                holder.deleteCB.setChecked(record.isSelected());
                holder.deleteCB.setOnCheckedChangeListener((buttonView, isChecked) -> record.setSelected(isChecked));

                if (record.isDeletePendingView()) {
                        holder.deleteCBLayout.setVisibility(View.VISIBLE);
                } else {
                        holder.deleteCBLayout.setVisibility(View.GONE);
                }

        }

	@Override
	public int getItemCount() {
        return voicemailLogList.size();
    }


        public void setVoicemailClickListener(OnVoicemailClickListener listener) {
                this.listener = listener;
        }

        public void setPlaybackSpeedIndex(int playbackSpeedIndex) {
                this.playbackSpeedIndex = PlaybackSpeedOptions.normalizeIndex(playbackSpeedIndex);
                if (selectedPosition >= 0) {
                        notifyItemChanged(selectedPosition);
                }
        }

        public int getPlaybackSpeedIndex() {
                return playbackSpeedIndex;
        }

        public void setCurrentProgressBar(ProgressBar progressBar) {
                if (currentProgressBar != null && currentProgressBar != progressBar) {
                        currentProgressBar.setProgress(0);
                }
                currentProgressBar = progressBar;
                if (currentProgressBar != null) {
                        currentProgressBar.setMax(currentProgressMax);
                        currentProgressBar.setProgress(currentProgressValue);
                }
        }

        public void updateCurrentProgress(int progress) {
                currentProgressValue = progress;
                if (currentProgressBar != null) {
                        currentProgressBar.setProgress(progress);
                }
        }

        public void resetCurrentProgress() {
                currentProgressValue = 0;
                if (currentProgressBar != null) {
                        currentProgressBar.setProgress(0);

                }
                notifyDataSetChanged();

        }

        public void setCurrentMax(int max) {
                currentProgressMax = max;
                if (currentProgressBar != null) {
                        currentProgressBar.setMax(max);
                }
        }

	public void updateVmStatus(VoicemailRecord recording) {
		for (int i =0 ; i < voicemailLogList.size(); i ++){
			if (recording.getRecordingSid().equals(voicemailLogList.get(i).getRecordingSid())){
				voicemailLogList.set(i, recording);
				notifyItemChanged(i);
			}
		}
	}

        public void updateAllVmStatus(String status) {
                        for (VoicemailRecord vr: voicemailLogList){
                                vr.setStatus(status);
                        }
                notifyDataSetChanged();
        }

        public ArrayList<VoicemailRecord> getSelectedVoicemails() {
                ArrayList<VoicemailRecord> selected = new ArrayList<>();
                for (VoicemailRecord vr : voicemailLogList) {
                        if (vr.isSelected()) {
                                selected.add(vr);
                        }
                }
                return selected;
        }

        public void clearSelections() {
                for (VoicemailRecord vr : voicemailLogList) {
                        vr.setSelected(false);
                }
                notifyDataSetChanged();
        }

	public boolean isSelectAll() {
		return selectAllMode;
	}


	public class VoicemailRecyclerViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener, View.OnTouchListener, SwipeItemTouchHelper.TouchViewHolder{

                protected TextView duration;
                protected TextView time;
                protected TextView from;
                protected AppCompatImageButton play;
                protected AppCompatImageButton pause;
                protected AppCompatImageButton info;
                protected AppCompatImageButton call_back;
                protected AppCompatImageButton delete;
        protected CheckBox deleteCB;
        protected RelativeLayout deleteCBLayout;
        protected ProgressBar progress;
        protected TextView playbackSpeed;

                protected ImageView icon;
                protected ImageView badge;

                protected RelativeLayout audioControls;

		public VoicemailRecyclerViewHolder(View view) {
			super(view);
			duration = view.findViewById(R.id.duration);
			time = view.findViewById(R.id.time);
			from = view.findViewById(R.id.from);
			audioControls = view.findViewById(R.id.audioControls);
			play = view.findViewById(R.id.play);
			pause = view.findViewById(R.id.pause);
			info = view.findViewById(R.id.info);
                        call_back = view.findViewById(R.id.call_back);
                        delete = view.findViewById(R.id.delete);
                        icon = view.findViewById(R.id.icon);
                        badge = view.findViewById(R.id.badge);
                        deleteCB = view.findViewById(R.id.deleteCheckBox);
                        deleteCBLayout = view.findViewById(R.id.deleteCheckBoxLayout);
                        progress = view.findViewById(R.id.voicemail_progress);
                        playbackSpeed = view.findViewById(R.id.playback_speed);
                }

		@Override
		public void onClick(View v) {

		}

		@Override
		public boolean onTouch(View v, MotionEvent event) {
			return false;
		}

		@Override
		public void onItemSelected() {
			itemView.setBackgroundColor(activity.getResources().getColor(R.color.grey_5));
		}

		@Override
		public void onItemClear() {
			itemView.setBackgroundColor(0);
		}
	}


        public interface OnVoicemailClickListener {
                void onVoicemailClick_Play();
                void onVoicemailClick_Pause();
                void onVoicemailClick_Info(VoicemailRecord record);
                void onVoicemailClick_CallBack(VoicemailRecord record);
                void onVoicemailClick(View view, VoicemailRecord record);
                void onVoicemailClick_Delete(VoicemailRecord record);
                void onVoicemailClick_Speed(View view);
        }

}