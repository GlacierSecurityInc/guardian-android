package com.glaciersecurity.glaciermessenger.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.databinding.DataBindingUtil;
import androidx.core.content.ContextCompat;

import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.crypto.OmemoSetting;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.AxolotlService;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.FingerprintStatus;
import com.glaciersecurity.glaciermessenger.databinding.ActivityTrustKeysBinding;
import com.glaciersecurity.glaciermessenger.databinding.KeysCardBinding;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Contact;
import com.glaciersecurity.glaciermessenger.entities.Conversation;
import com.glaciersecurity.glaciermessenger.entities.Message;
import com.glaciersecurity.glaciermessenger.utils.CryptoHelper;
import com.glaciersecurity.glaciermessenger.ui.util.AvatarWorkerTask;
import com.glaciersecurity.glaciermessenger.utils.XmppUri;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.glaciersecurity.glaciermessenger.xmpp.OnKeyStatusUpdated;

import org.signal.libsignal.protocol.IdentityKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class TrustKeysActivity extends OmemoActivity implements OnKeyStatusUpdated {
	private final Map<String, Boolean> ownKeysToTrust = new HashMap<>();
	private final Map<Jid, Map<String, Boolean>> foreignKeysToTrust = new HashMap<>();
	private final OnClickListener mCancelButtonListener = v -> {
		setResult(RESULT_CANCELED);
		finish();
	};
	private List<Jid> contactJids;
	private Account mAccount;
	private Conversation mConversation;
        private final OnClickListener mSaveButtonListener = v -> {
                commitTrusts();
                finishOk(false);
        };
	private final AtomicBoolean mUseCameraHintShown = new AtomicBoolean(false);
	private AxolotlService.FetchStatus lastFetchReport = AxolotlService.FetchStatus.SUCCESS;
	private Toast mUseCameraHintToast = null;
	private ActivityTrustKeysBinding binding;

	@Override
	protected void refreshUiReal() {
		invalidateOptionsMenu();
		populateView();
	}

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		this.binding = DataBindingUtil.setContentView(this, R.layout.activity_trust_keys);
		Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
		this.contactJids = new ArrayList<>();
		final var intent = getIntent();
		final String[] contacts = intent == null ? null : intent.getStringArrayExtra("contacts");
		for (final String jid : (contacts == null ? new String[0] : contacts)) {
			try {
				this.contactJids.add(Jid.of(jid));
			} catch (final IllegalArgumentException ignored) {
			}
		}

		binding.cancelButton.setOnClickListener(mCancelButtonListener);
		binding.saveButton.setOnClickListener(mSaveButtonListener);

		setSupportActionBar(binding.toolbar);
		configureActionBar(getSupportActionBar());

		if (savedInstanceState != null) {
			mUseCameraHintShown.set(savedInstanceState.getBoolean("camera_hint_shown", false));
		}
	}

	@Override
	public void onSaveInstanceState(Bundle savedInstanceState) {
		savedInstanceState.putBoolean("camera_hint_shown", mUseCameraHintShown.get());
		super.onSaveInstanceState(savedInstanceState);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.trust_keys, menu);
		return super.onCreateOptionsMenu(menu);
	}

	private void showCameraToast() {
		mUseCameraHintToast = Toast.makeText(this, R.string.use_camera_icon_to_scan_barcode, Toast.LENGTH_LONG);
		ActionBar actionBar = getSupportActionBar();
		mUseCameraHintToast.setGravity(Gravity.TOP | Gravity.END, 0, actionBar == null ? 0 : actionBar.getHeight());
		mUseCameraHintToast.show();
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void onStop() {
		super.onStop();
		if (mUseCameraHintToast != null) {
			mUseCameraHintToast.cancel();
		}
	}

	@Override
	protected void processFingerprintVerification(XmppUri uri) {
		if (mConversation != null
				&& mAccount != null
				&& uri.hasFingerprints()
				&& mAccount.getAxolotlService().getCryptoTargets(mConversation).contains(uri.getJid())) {
			boolean performedVerification = xmppConnectionService.verifyFingerprints(mAccount.getRoster().getContact(uri.getJid()), uri.getFingerprints());
			boolean keys = reloadFingerprints();
			if (performedVerification && !keys && !hasNoOtherTrustedKeys() && !hasPendingKeyFetches()) {
				finishOk(false);
				return;
			} else if (performedVerification) {
			}
		} else {
			reloadFingerprints();
		}
		populateView();
	}

	private void populateView() {
		setTitle(getString(R.string.trust_omemo_fingerprints));
		binding.ownKeysDetails.removeAllViews();
		binding.foreignKeys.removeAllViews();
		boolean hasOwnKeys = false;
		boolean hasForeignKeys = false;
		for (final String fingerprint : ownKeysToTrust.keySet()) {
			hasOwnKeys = true;
			addFingerprintRowWithListeners(binding.ownKeysDetails, mAccount, fingerprint, false,
					FingerprintStatus.createActiveFromNullable(ownKeysToTrust.get(fingerprint)), false, false,
					(buttonView, isChecked) -> {
						ownKeysToTrust.put(fingerprint, isChecked);
						// own fingerprints have no impact on locked status.
					}
			);
		}

		synchronized (this.foreignKeysToTrust) {
			for (Map.Entry<Jid, Map<String, Boolean>> entry : foreignKeysToTrust.entrySet()) {
				hasForeignKeys = true;
                                KeysCardBinding keysCardBinding = DataBindingUtil.inflate(getLayoutInflater(), R.layout.keys_card, binding.foreignKeys, false);
                                final Jid jid = entry.getKey();
                                final Contact contact = mAccount.getRoster().getContact(jid);
                                final String displayName = contact == null ? jid.getEscapedLocal() : contact.getDisplayName();
                                keysCardBinding.foreignKeysTitle.setText(displayName);
                                keysCardBinding.foreignKeysSubtitle.setText("@" + jid.asBareJid().getEscapedLocal());
                                View.OnClickListener contactDetailsClickListener = contact == null ? null : v -> switchToContactDetails(contact);
                                keysCardBinding.foreignKeysTitle.setOnClickListener(contactDetailsClickListener);
                                keysCardBinding.foreignKeysSubtitle.setOnClickListener(contactDetailsClickListener);
                                keysCardBinding.contactAvatar.setOnClickListener(contactDetailsClickListener);
                                if (contact != null) {
                                        AvatarWorkerTask.loadAvatar(contact, keysCardBinding.contactAvatar, R.dimen.avatar);
                                } else {
                                        keysCardBinding.contactAvatar.setImageResource(R.drawable.baseline_person_24);
                                }
                                final Map<String, Boolean> fingerprints = entry.getValue();
                                for (final String fingerprint : fingerprints.keySet()) {
                                        addFingerprintRowWithListeners(keysCardBinding.foreignKeysDetails, mAccount, fingerprint, false,
                                                        FingerprintStatus.createActiveFromNullable(fingerprints.get(fingerprint)), false, false,
                                                        (buttonView, isChecked) -> {
                                                                fingerprints.put(fingerprint, isChecked);
                                                                updateForeignKeyStatusChip(keysCardBinding, jid, fingerprints);
                                                                lockOrUnlockAsNeeded();
                                                        }
                                        );
                                }
                                if (fingerprints.isEmpty()) {
					if (hasNoOtherTrustedKeys(jid)) {
						if (!mAccount.getRoster().getContact(jid).mutualPresenceSubscription()) {
						} else {
						}
					} else {
                                        }
                                } else {
                                }
                                updateForeignKeyStatusChip(keysCardBinding, jid, fingerprints);
                                binding.foreignKeys.addView(keysCardBinding.foreignKeysCard);
                        }
                }

		if ((hasOwnKeys || foreignActuallyHasKeys()) && isCameraFeatureAvailable() && mUseCameraHintShown.compareAndSet(false, true)) {
			showCameraToast();
		}

		binding.ownKeysTitle.setText(mAccount.getJid().getEscapedLocal());
		binding.ownKeysCard.setVisibility(hasOwnKeys ? View.VISIBLE : View.GONE);
		binding.foreignKeys.setVisibility(hasForeignKeys ? View.VISIBLE : View.GONE);
                if (hasPendingKeyFetches()) {
                        setFetching();
                        lock();
                } else {
                        if (!hasForeignKeys && hasNoOtherTrustedKeys()) {
                                binding.keyErrorMessageCard.setVisibility(View.VISIBLE);
				boolean lastReportWasError = lastFetchReport == AxolotlService.FetchStatus.ERROR;
				boolean errorFetchingBundle = mAccount.getAxolotlService().fetchMapHasErrors(contactJids);
				boolean errorFetchingDeviceList = mAccount.getAxolotlService().hasErrorFetchingDeviceList(contactJids);
				boolean anyWithoutMutualPresenceSubscription = anyWithoutMutualPresenceSubscription(contactJids);
                                if (errorFetchingDeviceList) {
                                        binding.keyErrorMessage.setVisibility(View.VISIBLE);
                                        binding.keyErrorMessage.setText(R.string.error_trustkey_device_list);
                                } else if (errorFetchingBundle || lastReportWasError) {
					binding.keyErrorMessage.setVisibility(View.VISIBLE);
					binding.keyErrorMessage.setText(R.string.error_trustkey_bundle);
				} else {
					binding.keyErrorMessage.setVisibility(View.GONE);
				}
				this.binding.keyErrorHintMutual.setVisibility(anyWithoutMutualPresenceSubscription ? View.VISIBLE : View.GONE);
				Contact contact = mAccount.getRoster().getContact(contactJids.get(0));
				binding.keyErrorGeneral.setText(getString(R.string.error_trustkey_general, getString(R.string.app_name), contact.getDisplayName()));
				binding.ownKeysDetails.removeAllViews();
				if (OmemoSetting.isAlways()) {
					binding.disableButton.setVisibility(View.GONE);
				} else {
					binding.disableButton.setVisibility(View.VISIBLE);
					binding.disableButton.setOnClickListener(this::disableEncryptionDialog);
				}
				binding.ownKeysCard.setVisibility(View.GONE);
				binding.foreignKeys.removeAllViews();
				binding.foreignKeys.setVisibility(View.GONE);
			}
                        lockOrUnlockAsNeeded();
                        setDone();
                }
        }

	private void disableEncryptionDialog(final View view) {
		final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
		builder.setTitle(R.string.disable_encryption);
		builder.setMessage(R.string.disable_encryption_message);
		builder.setPositiveButton(R.string.disable_now, (dialog, which) -> {
			mConversation.setNextEncryption(Message.ENCRYPTION_NONE);
			xmppConnectionService.updateConversation(mConversation);
			finishOk(true);
		});
		builder.setNegativeButton(R.string.cancel, null);
		builder.create().show();
	}

	private boolean anyWithoutMutualPresenceSubscription(List<Jid> contactJids) {
		for (Jid jid : contactJids) {
			if (!mAccount.getRoster().getContact(jid).mutualPresenceSubscription()) {
				return true;
			}
		}
		return false;
	}

        private void updateForeignKeyStatusChip(KeysCardBinding keysCardBinding, Jid jid, Map<String, Boolean> fingerprints) {
                Chip statusChip = keysCardBinding.foreignKeysStatusChip;
                if (statusChip == null) {
                        return;
                }
                boolean hasTrustedKeys = false;
                if (fingerprints != null) {
                        for (Boolean trusted : fingerprints.values()) {
                                if (Boolean.TRUE.equals(trusted)) {
                                        hasTrustedKeys = true;
                                        break;
                                }
                        }
                }
                if (!hasTrustedKeys && mAccount != null) {
                        hasTrustedKeys = mAccount.getAxolotlService().getNumTrustedKeys(jid) > 0;
                }
                final var context = statusChip.getContext();
                if (hasTrustedKeys) {
                        statusChip.setText(R.string.status_trusted);
                        statusChip.setChipIconResource(R.drawable.ic_check_24dp);
                        statusChip.setChipIconTint(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.green_700)));
                        statusChip.setTextColor(ContextCompat.getColor(context, R.color.green_700));
                        statusChip.setChipBackgroundColor(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.green_100)));
                } else {
                        statusChip.setText(R.string.status_unverified);
                        statusChip.setChipIconResource(R.drawable.ic_error_24dp);
                        statusChip.setChipIconTint(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.red_700)));
                        statusChip.setTextColor(ContextCompat.getColor(context, R.color.red_700));
                        statusChip.setChipBackgroundColor(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.red_100)));
                }
        }

        private boolean foreignActuallyHasKeys() {
                synchronized (this.foreignKeysToTrust) {
                        for (Map.Entry<Jid, Map<String, Boolean>> entry : foreignKeysToTrust.entrySet()) {
                                if (!entry.getValue().isEmpty()) {
                                        return true;
				}
			}
		}
		return false;
	}

	private boolean reloadFingerprints() {
		List<Jid> acceptedTargets = mConversation == null ? new ArrayList<>() : mConversation.getAcceptedCryptoTargets();
		ownKeysToTrust.clear();
		if (this.mAccount == null) {
			return false;
		}
		AxolotlService service = this.mAccount.getAxolotlService();
		Set<IdentityKey> ownKeysSet = service.getKeysWithTrust(FingerprintStatus.createActiveUndecided());
		for (final IdentityKey identityKey : ownKeysSet) {
			final String fingerprint = CryptoHelper.bytesToHex(identityKey.getPublicKey().serialize());
			if (!ownKeysToTrust.containsKey(fingerprint)) {
				ownKeysToTrust.put(fingerprint, false);
			}
		}
		synchronized (this.foreignKeysToTrust) {
			foreignKeysToTrust.clear();
			for (Jid jid : contactJids) {
				Set<IdentityKey> foreignKeysSet = service.getKeysWithTrust(FingerprintStatus.createActiveUndecided(), jid);
				if (hasNoOtherTrustedKeys(jid) && ownKeysSet.isEmpty()) {
					foreignKeysSet.addAll(service.getKeysWithTrust(FingerprintStatus.createActive(false), jid));
				}
				Map<String, Boolean> foreignFingerprints = new HashMap<>();
				for (final IdentityKey identityKey : foreignKeysSet) {
					final String fingerprint = CryptoHelper.bytesToHex(identityKey.getPublicKey().serialize());
					if (!foreignFingerprints.containsKey(fingerprint)) {
						foreignFingerprints.put(fingerprint, false);
					}
				}
				if (!foreignFingerprints.isEmpty() || !acceptedTargets.contains(jid)) {
					foreignKeysToTrust.put(jid, foreignFingerprints);
				}
			}
		}
		return ownKeysSet.size() + foreignKeysToTrust.size() > 0;
	}

	@Override
	public void onBackendConnected() {
		Intent intent = getIntent();
		this.mAccount = extractAccount(intent);
		if (this.mAccount != null && intent != null) {
			String uuid = intent.getStringExtra("conversation");
			this.mConversation = xmppConnectionService.findConversationByUuid(uuid);
			if (this.mPendingFingerprintVerificationUri != null) {
				processFingerprintVerification(this.mPendingFingerprintVerificationUri);
				this.mPendingFingerprintVerificationUri = null;
			} else {
				final boolean keysToTrust = reloadFingerprints();
				if (keysToTrust || hasPendingKeyFetches() || hasNoOtherTrustedKeys()) {
					populateView();
					invalidateOptionsMenu();
				} else {
					finishOk(false);
				}
			}
		}
	}

	private boolean hasNoOtherTrustedKeys() {
		return mAccount == null || mAccount.getAxolotlService().anyTargetHasNoTrustedKeys(contactJids);
	}

	private boolean hasNoOtherTrustedKeys(Jid contact) {
		return mAccount == null || mAccount.getAxolotlService().getNumTrustedKeys(contact) == 0;
	}

	private boolean hasPendingKeyFetches() {
		return mAccount != null && mAccount.getAxolotlService().hasPendingKeyFetches(contactJids);
	}


	@Override
	public void onKeyStatusUpdated(final AxolotlService.FetchStatus report) {
		final boolean keysToTrust = reloadFingerprints();
		if (report != null) {
			lastFetchReport = report;
			runOnUiThread(() -> {
				if (mUseCameraHintToast != null && !keysToTrust) {
					mUseCameraHintToast.cancel();
				}
				switch (report) {
					case ERROR:
						break;
					case SUCCESS_TRUSTED:
						break;
					case SUCCESS_VERIFIED:
//						Toast.makeText(TrustKeysActivity.this,
//								Config.X509_VERIFICATION ? R.string.verified_omemo_key_with_certificate : R.string.all_omemo_keys_have_been_verified,
						break;
				}
			});

		}
		if (keysToTrust || hasPendingKeyFetches() || hasNoOtherTrustedKeys()) {
			refreshUi();
		} else {
			runOnUiThread(() -> finishOk(false));

		}
	}

	private void finishOk(boolean disabled) {
		Intent data = new Intent();
		data.putExtra("choice", getIntent().getIntExtra("choice", ConversationFragment.ATTACHMENT_CHOICE_INVALID));
		data.putExtra("disabled", disabled);
		setResult(RESULT_OK, data);
		finish();
	}

	private void commitTrusts() {
		for (final String fingerprint : ownKeysToTrust.keySet()) {
			mAccount.getAxolotlService().setFingerprintTrust(
					fingerprint,
					FingerprintStatus.createActiveFromNullable(ownKeysToTrust.get(fingerprint)));
		}
		List<Jid> acceptedTargets = mConversation == null ? new ArrayList<>() : mConversation.getAcceptedCryptoTargets();
		synchronized (this.foreignKeysToTrust) {
			for (Map.Entry<Jid, Map<String, Boolean>> entry : foreignKeysToTrust.entrySet()) {
				Jid jid = entry.getKey();
				Map<String, Boolean> value = entry.getValue();
				if (!acceptedTargets.contains(jid)) {
					acceptedTargets.add(jid);
				}
				for (final String fingerprint : value.keySet()) {
					mAccount.getAxolotlService().setFingerprintTrust(
							fingerprint,
							FingerprintStatus.createActiveFromNullable(value.get(fingerprint)));
				}
			}
		}
		if (mConversation != null && mConversation.getMode() == Conversation.MODE_MULTI) {
			mConversation.setAcceptedCryptoTargets(acceptedTargets);
			xmppConnectionService.updateConversation(mConversation);
		}
	}

	private void unlock() {
		binding.saveButton.setEnabled(true);
	}

	private void lock() {
		binding.saveButton.setEnabled(false);
	}

        private void lockOrUnlockAsNeeded() {
                if (hasDeviceListFetchErrors()) {
                        binding.missingFingerprintWarning.setVisibility(View.VISIBLE);
                        binding.missingFingerprintWarning.setText(R.string.omemo_device_list_fetch_failed_warning);
                        unlock();
                        return;
                }
                if (hasContactsMissingTrustedKeys()) {
                        binding.missingFingerprintWarning.setVisibility(View.VISIBLE);
                        binding.missingFingerprintWarning.setText(R.string.omemo_missing_fingerprints_warning);
                        unlock();
                        return;
                }
                binding.missingFingerprintWarning.setVisibility(View.GONE);
                unlock();
        }

        private boolean hasDeviceListFetchErrors() {
                return mAccount != null && mAccount.getAxolotlService().hasErrorFetchingDeviceList(contactJids);
        }

        private void setDone() {
                binding.saveButton.setText(getString(R.string.done));
        }

        private void setFetching() {
                binding.saveButton.setText(getString(R.string.fetching_keys));
                binding.missingFingerprintWarning.setVisibility(View.GONE);
        }

        private boolean hasContactsMissingTrustedKeys() {
                if (mAccount == null) {
                        return false;
                }
                synchronized (this.foreignKeysToTrust) {
                        for (Jid jid : contactJids) {
                                if (!hasNoOtherTrustedKeys(jid)) {
                                        continue;
                                }
                                Map<String, Boolean> fingerprints = foreignKeysToTrust.get(jid);
                                if (fingerprints == null || fingerprints.isEmpty()) {
                                        return true;
                                }
                                boolean hasTrustedSelection = false;
                                for (Boolean trusted : fingerprints.values()) {
                                        if (Boolean.TRUE.equals(trusted)) {
                                                hasTrustedSelection = true;
                                                break;
                                        }
                                }
                                if (!hasTrustedSelection) {
                                        return true;
                                }
                        }
                }
                return false;
        }
}
