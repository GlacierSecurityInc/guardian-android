package com.glaciersecurity.glaciermessenger.ui;

import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ContactCallSmsDialogBinding;
import com.glaciersecurity.glaciermessenger.entities.NativeContact;
import com.glaciersecurity.glaciermessenger.ui.adapter.ContactPhoneNumberAdapter;
import com.glaciersecurity.glaciermessenger.ui.util.AvatarWorkerTask;
import com.google.android.material.bottomsheet.BottomSheetDialog;

public final class StartSmsOrCallDialog {
	private static BottomSheetDialog dialog;

	public static void show(final XmppActivity activity, NativeContact contact) {
		ContactCallSmsDialogBinding binding = DataBindingUtil.inflate(activity.getLayoutInflater(), R.layout.contact_call_sms_dialog, null, false);
		binding.contactDisplayName.setText(contact.getDisplayName());
		binding.phoneNumbers.setLayoutManager(new LinearLayoutManager(activity));
		binding.phoneNumbers.setAdapter(new ContactPhoneNumberAdapter(activity, contact));
		AvatarWorkerTask.loadAvatar(contact, binding.detailsContactBadge, R.dimen.avatar_on_details_screen_size);

		dialog = new BottomSheetDialog(activity);
		dialog.setContentView(binding.getRoot());
		dialog.show();
	}

	public static void dismiss() {
		if (dialog != null && dialog.isShowing()) {
			dialog.dismiss();
		}
		dialog = null;
	}
}
