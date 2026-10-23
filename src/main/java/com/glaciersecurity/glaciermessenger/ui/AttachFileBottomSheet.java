package com.glaciersecurity.glaciermessenger.ui;

import android.app.Dialog;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.glaciersecurity.glaciermessenger.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

public class AttachFileBottomSheet extends BottomSheetDialogFragment {

    public interface OnAttachOptionSelectedListener {
        void onAttachOptionSelected(int choice);
    }

    private OnAttachOptionSelectedListener listener;
    private boolean microphoneAvailable = true;

    public static AttachFileBottomSheet newInstance(boolean microphoneAvailable) {
        AttachFileBottomSheet sheet = new AttachFileBottomSheet();
        Bundle args = new Bundle();
        args.putBoolean("microphone_available", microphoneAvailable);
        sheet.setArguments(args);
        return sheet;
    }

    public void setOnAttachOptionSelectedListener(OnAttachOptionSelectedListener listener) {
        this.listener = listener;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            microphoneAvailable = getArguments().getBoolean("microphone_available", true);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_attach_file, container, false);
    }

    @Override
    public void onStart() {
        super.onStart();
        final Dialog dialog = getDialog();
        final Window window = dialog != null ? dialog.getWindow() : null;
        if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            window.setBackgroundBlurRadius((int) (24 * getResources().getDisplayMetrics().density));
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        view.findViewById(R.id.attach_choose_file).setOnClickListener(v -> select(ConversationFragment.ATTACHMENT_CHOICE_CHOOSE_FILE));
        view.findViewById(R.id.attach_choose_picture).setOnClickListener(v -> select(ConversationFragment.ATTACHMENT_CHOICE_CHOOSE_IMAGE));
        view.findViewById(R.id.attach_take_picture).setOnClickListener(v -> select(ConversationFragment.ATTACHMENT_CHOICE_TAKE_PHOTO));
        view.findViewById(R.id.attach_record_video).setOnClickListener(v -> select(ConversationFragment.ATTACHMENT_CHOICE_RECORD_VIDEO));
        view.findViewById(R.id.attach_location).setOnClickListener(v -> select(ConversationFragment.ATTACHMENT_CHOICE_LOCATION));

        View voiceRow = view.findViewById(R.id.attach_record_voice);
        voiceRow.setVisibility(microphoneAvailable ? View.VISIBLE : View.GONE);
        voiceRow.setOnClickListener(v -> select(ConversationFragment.ATTACHMENT_CHOICE_RECORD_VOICE));
    }

    private void select(int choice) {
        dismiss();
        if (listener != null) {
            listener.onAttachOptionSelected(choice);
        }
    }
}
