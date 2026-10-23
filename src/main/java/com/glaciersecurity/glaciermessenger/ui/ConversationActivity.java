package com.glaciersecurity.glaciermessenger.ui;

import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.persistance.DatabaseBackend;

import java.util.List;

public class ConversationActivity extends AppCompatActivity {


	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		// resolveLaunchIntent() reads the database, which throws when this build cannot read the
		// file on disk. Say so instead of crashing. Two ways that happens, and they need different
		// wording: the schema is newer than this build understands, or the file is encrypted and
		// this build links no SQLCipher.
		switch (DatabaseBackend.accessState(this)) {
			case TOO_NEW:
				showDatabaseTooNewDialog();
				return;
			case ENCRYPTED:
				showDatabaseEncryptedDialog();
				return;
			default:
				break;
		}
		startActivity(resolveLaunchIntent());
		finish();
	}

	/**
	 * Explain that this build is older than the data on the device, rather than crashing or
	 * silently discarding the history. Reachable only by installing an older build over a newer
	 * one; Play Store upgrades never go backwards.
	 */
	private void showDatabaseTooNewDialog() {
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.database_too_new_title)
				.setMessage(
						getString(
								R.string.database_too_new_message,
								DatabaseBackend.getOnDiskVersion(),
								DatabaseBackend.getAppDatabaseVersion()))
				.setPositiveButton(R.string.action_quit, (dialog, which) -> finish())
				.setCancelable(false)
				.show();
	}

	/**
	 * Explain that the data on disk is encrypted and this build cannot read it. Reachable the same
	 * way the too-new dialog is -- by installing an older build over a newer one -- and it exists so
	 * that doing so shows a sentence rather than crash-looping before any UI appears.
	 */
	private void showDatabaseEncryptedDialog() {
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.database_encrypted_title)
				.setMessage(R.string.database_encrypted_message)
				.setPositiveButton(R.string.action_quit, (dialog, which) -> finish())
				.setCancelable(false)
				.show();
	}

	private Intent resolveLaunchIntent() {
		final List<Account> accounts = DatabaseBackend.getInstance(this).getAccounts();
		if (accounts.isEmpty()) {
			return new Intent(this, StepperWizard.class)
					.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
		}

		for (Account account : accounts) {
			final Account.State status = account.getStatus();
			// OPTION_LOGGED_IN_SUCCESSFULLY is historical state and can be false for valid accounts
			// (for example after migration/reset), so status should determine launch eligibility.
			if (status != Account.State.DISABLED
					&& status != Account.State.LOGGED_OUT
					&& status != Account.State.UNAUTHORIZED) {
				return new Intent(this, ConversationsActivity.class);
			}
		}

		// If at least one account exists in the database, stay in app flow instead of forcing
		// LoginAccountActivity; explicit reauthentication should be initiated from account actions.
		return new Intent(this, ConversationsActivity.class);
	}
}
