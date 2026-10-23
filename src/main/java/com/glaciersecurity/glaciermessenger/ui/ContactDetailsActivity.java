package com.glaciersecurity.glaciermessenger.ui;

import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.ContactsContract.Contacts;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuCompat;
import androidx.core.view.ViewCompat;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;

import org.openintents.openpgp.util.OpenPgpUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.AxolotlService;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.FingerprintStatus;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.XmppAxolotlSession;
import com.glaciersecurity.glaciermessenger.databinding.ActivityContactDetailsBinding;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Contact;
import com.glaciersecurity.glaciermessenger.entities.ListItem;
import com.glaciersecurity.glaciermessenger.entities.Presence;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService.OnAccountUpdate;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService.OnRosterUpdate;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.ui.adapter.MediaAdapter;
import com.glaciersecurity.glaciermessenger.ui.interfaces.OnMediaLoaded;
import com.glaciersecurity.glaciermessenger.ui.util.Attachment;
import com.glaciersecurity.glaciermessenger.ui.util.AvatarWorkerTask;
import com.glaciersecurity.glaciermessenger.ui.util.GridManager;
import com.glaciersecurity.glaciermessenger.ui.util.JidDialog;
import com.glaciersecurity.glaciermessenger.ui.util.MenuDoubleTabUtil;
import com.glaciersecurity.glaciermessenger.utils.AccountUtils;
import com.glaciersecurity.glaciermessenger.utils.Compatibility;
import com.glaciersecurity.glaciermessenger.utils.Emoticons;
import com.glaciersecurity.glaciermessenger.utils.UIHelper;
import com.glaciersecurity.glaciermessenger.utils.XmppUri;
import com.glaciersecurity.glaciermessenger.xml.Namespace;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.glaciersecurity.glaciermessenger.xmpp.OnKeyStatusUpdated;
import com.glaciersecurity.glaciermessenger.xmpp.OnUpdateBlocklist;

public class ContactDetailsActivity extends OmemoActivity implements OnAccountUpdate, OnRosterUpdate, OnUpdateBlocklist, OnKeyStatusUpdated, OnMediaLoaded, XmppConnectionService.OnMediaAvailabilityChanged {
    public static final String ACTION_VIEW_CONTACT = "view_contact";
    private final int REQUEST_SYNC_CONTACTS = 0x28cf;
    ActivityContactDetailsBinding binding;
    private MediaAdapter mMediaAdapter;

    private Contact contact;
    private final DialogInterface.OnClickListener removeFromRoster = new DialogInterface.OnClickListener() {

        @Override
        public void onClick(DialogInterface dialog, int which) {
            xmppConnectionService.deleteContactOnServer(contact);
        }
    };
    private final OnCheckedChangeListener mOnSendCheckedChange = new OnCheckedChangeListener() {

        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (isChecked) {
                if (contact.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)) {
                    xmppConnectionService.stopPresenceUpdatesTo(contact);
                } else {
                    contact.setOption(Contact.Options.PREEMPTIVE_GRANT);
                }
            } else {
                contact.resetOption(Contact.Options.PREEMPTIVE_GRANT);
                xmppConnectionService.sendPresencePacket(contact.getAccount(), xmppConnectionService.getPresenceGenerator().stopPresenceUpdatesTo(contact));
            }
        }
    };
    private final OnCheckedChangeListener mOnReceiveCheckedChange = new OnCheckedChangeListener() {

        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (isChecked) {
                xmppConnectionService.sendPresencePacket(contact.getAccount(), xmppConnectionService.getPresenceGenerator().requestPresenceUpdatesFrom(contact));
            } else {
                xmppConnectionService.sendPresencePacket(contact.getAccount(), xmppConnectionService.getPresenceGenerator().stopPresenceUpdatesFrom(contact));
            }
        }
    };
    private Jid accountJid;
    private Jid contactJid;
    private boolean showDynamicTags = true;
    private boolean showLastSeen = false;
    private boolean showInactiveOmemo = false;
    private String messageFingerprint;

    @Override
    public void onRosterUpdate() {
        refreshUi();
    }

    @Override
    public void onAccountUpdate() {
        refreshUi();
    }

    @Override
    public void OnUpdateBlocklist() {
        refreshUi();
    }

    @Override
    protected void refreshUiReal() {
        invalidateOptionsMenu();
        populateView();
    }

    @Override
    protected String getShareableUri(boolean http) {
        if (http) {
            return "https://" + XmppUri.INVITE_DOMAIN + "/i/" + XmppUri.lameUrlEncode(contact.getJid().asBareJid().toEscapedString());
        } else {
            return "xmpp:" + contact.getJid().asBareJid().toEscapedString();
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showInactiveOmemo = savedInstanceState != null && savedInstanceState.getBoolean("show_inactive_omemo", false);
        if (getIntent().getAction().equals(ACTION_VIEW_CONTACT)) {
            try {
                this.accountJid = Jid.ofEscaped(getIntent().getExtras().getString(EXTRA_ACCOUNT));
            } catch (final IllegalArgumentException ignored) {
            }
            try {
                this.contactJid = Jid.ofEscaped(getIntent().getExtras().getString("contact"));
            } catch (final IllegalArgumentException ignored) {
            }
        }
        this.messageFingerprint = getIntent().getStringExtra("fingerprint");
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_contact_details);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());

        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());
        binding.showInactiveDevices.setOnClickListener(v -> {
            showInactiveOmemo = !showInactiveOmemo;
            populateView();
        });
        binding.addContactButton.setOnClickListener(v -> showAddToRosterDialog(contact));
        binding.editDisplaynameNameButton.setOnClickListener(v -> changeContactDisplayName());

        mMediaAdapter = new MediaAdapter(this, R.dimen.media_size);
        this.binding.media.setAdapter(mMediaAdapter);
        GridManager.setupLayoutManager(this, this.binding.media, R.dimen.media_size);
    }

    private void changeContactDisplayName() {
        Uri systemAccount = contact.getSystemAccount();
        if (systemAccount == null) {
            quickEdit(contact.getServerName(), R.string.contact_name, value -> {
                contact.setServerName(value);
                ContactDetailsActivity.this.xmppConnectionService.pushContactToServer(contact);
                populateView();
                return null;
            }, true);
        } else {
            Intent intent = new Intent(Intent.ACTION_EDIT);
            intent.setDataAndType(systemAccount, Contacts.CONTENT_ITEM_TYPE);
            intent.putExtra("finishActivityOnSaveCompleted", true);
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(ContactDetailsActivity.this, R.string.no_application_found_to_view_contact, Toast.LENGTH_SHORT).show();
            }

        }
    }

    @Override
    public void onSaveInstanceState(final Bundle savedInstanceState) {
        savedInstanceState.putBoolean("show_inactive_omemo", showInactiveOmemo);
        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    public void onStart() {
        super.onStart();
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        this.showLastSeen = preferences.getBoolean("last_activity", false);
        binding.mediaWrapper.setVisibility(Compatibility.hasStoragePermission() ? View.VISIBLE : View.GONE);
        mMediaAdapter.setAttachments(Collections.emptyList());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0)
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (requestCode == REQUEST_SYNC_CONTACTS && xmppConnectionServiceBound) {
                    xmppConnectionService.loadPhoneContacts();
                    xmppConnectionService.startContactObserver();
                }
            }
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem menuItem) {
        if (MenuDoubleTabUtil.shouldIgnoreTap()) {
            return false;
        }
        final int itemId = menuItem.getItemId();
        if (itemId == android.R.id.home) {
            finish();
        } else if (itemId == R.id.action_share_http) {
            shareLink(true);
        } else if (itemId == R.id.action_share_uri) {
            shareLink(false);
        } else if (itemId == R.id.action_delete_contact) {
            final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
            builder.setNegativeButton(getString(R.string.cancel), null);
            builder.setTitle(getString(R.string.action_delete_contact))
                    .setMessage(JidDialog.style(this, R.string.remove_contact_text, contact.getJid().toEscapedString()))
                    .setPositiveButton(getString(R.string.delete),
                            removeFromRoster).create().show();
        } else if (itemId == R.id.action_edit_contact) {
            changeContactDisplayName();
        } else if (itemId == R.id.action_block) {
            BlockContactDialog.show(this, contact);
        } else if (itemId == R.id.action_unblock) {
            BlockContactDialog.show(this, contact);
        }
        return super.onOptionsItemSelected(menuItem);
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.contact_details, menu);
        MenuCompat.setGroupDividerEnabled(menu, true);
        AccountUtils.showHideMenuItems(menu);
        final MenuItem deleteContact = menu.findItem(R.id.action_delete_contact);
        if (deleteContact != null && deleteContact.getIcon() != null) {
            int errorColor = MaterialColors.getColor(this, R.attr.errorColor, 0xFFE07B6E);
            deleteContact.getIcon().setTint(errorColor);
        }
        final MenuItem blockContact = menu.findItem(R.id.action_block);
        if (blockContact != null && blockContact.getIcon() != null) {
            int errorColor = MaterialColors.getColor(this, R.attr.errorColor, 0xFFE07B6E);
            blockContact.getIcon().setTint(errorColor);
        }
        if (contact == null) {
            return true;
        }
        return super.onCreateOptionsMenu(menu);
    }

    private void populateView() {
        if (contact == null) {
            return;
        }
        invalidateOptionsMenu();
        setTitle("Contact Details");
        if (contact.showInRoster()) {
            binding.detailsSendPresence.setVisibility(View.VISIBLE);
            binding.detailsReceivePresence.setVisibility(View.VISIBLE);
            binding.addContactButton.setVisibility(View.GONE);
            binding.detailsSendPresence.setOnCheckedChangeListener(null);
            binding.detailsReceivePresence.setOnCheckedChangeListener(null);

            List<String> statusMessages = contact.getPresences().getStatusMessages();
            if (statusMessages.size() == 0) {
                binding.statusMessage.setVisibility(View.GONE);
            } else if (statusMessages.size() == 1) {
                final String message = statusMessages.get(0);
                binding.statusMessage.setVisibility(View.VISIBLE);
                final Spannable span = new SpannableString(message);
                if (Emoticons.isOnlyEmoji(message)) {
                    span.setSpan(new RelativeSizeSpan(2.0f), 0, message.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                binding.statusMessage.setText(span);
            } else {
                StringBuilder builder = new StringBuilder();
                binding.statusMessage.setVisibility(View.VISIBLE);
                int s = statusMessages.size();
                for (int i = 0; i < s; ++i) {
                    builder.append(statusMessages.get(i));
                    if (i < s - 1) {
                        builder.append("\n");
                    }
                }
                binding.statusMessage.setText(builder);
            }

            if (contact.getOption(Contact.Options.FROM)) {
                binding.detailsSendPresence.setText(R.string.send_presence_updates);
                binding.detailsSendPresence.setChecked(true);
            } else if (contact.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)) {
                binding.detailsSendPresence.setChecked(false);
                binding.detailsSendPresence.setText(R.string.send_presence_updates);
            } else {
                binding.detailsSendPresence.setText(R.string.preemptively_grant);
                binding.detailsSendPresence.setChecked(contact.getOption(Contact.Options.PREEMPTIVE_GRANT));
            }
            if (contact.getOption(Contact.Options.TO)) {
                binding.detailsReceivePresence.setText(R.string.receive_presence_updates);
                binding.detailsReceivePresence.setChecked(true);
            } else {
                binding.detailsReceivePresence.setText(R.string.ask_for_presence_updates);
                binding.detailsReceivePresence.setChecked(contact.getOption(Contact.Options.ASKING));
            }
            if (contact.getAccount().isOnlineAndConnected()) {
                binding.detailsReceivePresence.setEnabled(true);
                binding.detailsSendPresence.setEnabled(true);
            } else {
                binding.detailsReceivePresence.setEnabled(false);
                binding.detailsSendPresence.setEnabled(false);
            }
            binding.detailsSendPresence.setOnCheckedChangeListener(this.mOnSendCheckedChange);
            binding.detailsReceivePresence.setOnCheckedChangeListener(this.mOnReceiveCheckedChange);
        } else {
            binding.addContactButton.setVisibility(View.VISIBLE);
            binding.detailsSendPresence.setVisibility(View.GONE);
            binding.detailsReceivePresence.setVisibility(View.GONE);
            binding.statusMessage.setVisibility(View.GONE);
        }

        if (contact.isBlocked() && !this.showDynamicTags) {
            binding.detailsLastseen.setVisibility(View.VISIBLE);
            binding.detailsLastseen.setText(R.string.contact_blocked);
        } else {
            if (showLastSeen
                    && contact.getLastseen() > 0
                    && contact.getPresences().allOrNonSupport(Namespace.IDLE)) {
                binding.detailsLastseen.setVisibility(View.VISIBLE);
                final Presence.Status shownStatus = contact.getShownStatus();
                final boolean showAsActive = contact.isActive()
                        && (shownStatus == Presence.Status.ONLINE || shownStatus == Presence.Status.CHAT);
                binding.detailsLastseen.setText(
                        UIHelper.lastseen(getApplicationContext(), showAsActive, contact.getLastseen()));
            } else {
                binding.detailsLastseen.setVisibility(View.GONE);
            }
        }

        binding.detailsContactjid.setText(contact.getDisplayName());
        binding.accountJid.setText(contact.getJid().getEscapedLocal());

        final String account = contact.getAccount().getJid().asBareJid().toEscapedString();
        binding.detailsAccount.setText(account);
        AvatarWorkerTask.loadAvatar(contact, binding.detailsContactBadge, R.dimen.avatar_on_details_screen_size);
        final Presence.Status shownStatus = contact.getShownStatus();
        binding.detailsContactpresenceIcon.setImageResource(shownStatus.getStatusIcon());
        binding.detailsContactpresenceIcon.setVisibility(View.VISIBLE);

        if (shownStatus != Presence.Status.ONLINE && shownStatus != Presence.Status.CHAT) {
            binding.detailsStatusTag.setVisibility(View.VISIBLE);
            UIHelper.setStatus(binding.detailsStatusTag, shownStatus);
        } else {
            binding.detailsStatusTag.setVisibility(View.GONE);
        }
        updateStatusContainerVisibility();

        binding.detailsContactKeys.removeAllViews();
        boolean hasKeys = false;
        final LayoutInflater inflater = getLayoutInflater();
        final AxolotlService axolotlService = contact.getAccount().getAxolotlService();
        if (Config.supportOmemo() && axolotlService != null) {
            final Collection<XmppAxolotlSession> sessions = axolotlService.findSessionsForContact(contact);
            boolean anyActive = false;
            for (XmppAxolotlSession session : sessions) {
                anyActive = session.getTrust().isActive();
                if (anyActive) {
                    break;
                }
            }
            boolean skippedInactive = false;
            boolean showsInactive = false;
            boolean showUnverifiedWarning = false;
            for (final XmppAxolotlSession session : sessions) {
                final FingerprintStatus trust = session.getTrust();
                hasKeys |= !trust.isCompromised();
                if (!trust.isActive() && anyActive) {
                    if (showInactiveOmemo) {
                        showsInactive = true;
                    } else {
                        skippedInactive = true;
                        continue;
                    }
                }
                if (!trust.isCompromised()) {
                    boolean highlight = session.getFingerprint().equals(messageFingerprint);
                    addFingerprintRow(binding.detailsContactKeys, session, highlight);
                }
                if (trust.isUnverified()) {
                    showUnverifiedWarning = true;
                }
            }
            binding.unverifiedWarning.setVisibility(showUnverifiedWarning ? View.VISIBLE : View.GONE);
            if (showsInactive || skippedInactive) {
                binding.showInactiveDevices.setText(showsInactive ? R.string.hide_inactive_devices : R.string.show_inactive_devices);
                binding.showInactiveDevices.setVisibility(View.VISIBLE);
            } else {
                binding.showInactiveDevices.setVisibility(View.GONE);
            }
        } else {
            binding.showInactiveDevices.setVisibility(View.GONE);
        }
        if (hasKeys) {
            binding.scanButton.setOnClickListener((v) -> ScanActivity.scan(this));
        }
        if (Config.supportOpenPgp() && contact.getPgpKeyId() != 0) {
            hasKeys = true;
            View view = inflater.inflate(R.layout.contact_key, binding.detailsContactKeys, false);
            TextView key = view.findViewById(R.id.key);
            TextView keyType = view.findViewById(R.id.key_type);
            keyType.setText(R.string.openpgp_key_id);
            if ("pgp".equals(messageFingerprint)) {
                keyType.setTextColor(MaterialColors.getColor(keyType, com.google.android.material.R.attr.colorPrimaryVariant));
            }
            key.setText(OpenPgpUtils.convertKeyIdToHex(contact.getPgpKeyId()));
            final OnClickListener openKey = v -> launchOpenKeyChain(contact.getPgpKeyId());
            view.setOnClickListener(openKey);
            key.setOnClickListener(openKey);
            keyType.setOnClickListener(openKey);
            binding.detailsContactKeys.addView(view);
        }
        binding.keysWrapper.setVisibility(hasKeys ? View.VISIBLE : View.GONE);

        final List<ListItem.Tag> tagList = contact.getTags();
        final boolean hasMetaTags = contact.isBlocked();
        if ((tagList.isEmpty() && !hasMetaTags) || !this.showDynamicTags) {
            binding.tags.setVisibility(View.GONE);
        } else {
            binding.tags.setVisibility(View.VISIBLE);
            binding.tags.removeViews(1, binding.tags.getChildCount() - 1);
            final ImmutableList.Builder<Integer> viewIdBuilder = new ImmutableList.Builder<>();
            for (final ListItem.Tag tag : tagList) {
                final String name = tag.getName();
                final TextView tv = (TextView) inflater.inflate(R.layout.item_tag, binding.tags, false);
                tv.setText(name);
                final int id = ViewCompat.generateViewId();
                tv.setId(id);
                viewIdBuilder.add(id);
                binding.tags.addView(tv);
            }
            if (contact.isBlocked()) {
                final TextView tv =
                        (TextView)
                                inflater.inflate(
                                        R.layout.item_tag, binding.tags, false);
                tv.setText(R.string.blocked);
                tv.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.harmonizeWithPrimary(tv.getContext(), ContextCompat.getColor(tv.getContext(),R.color.gray_800))));
                final int id = ViewCompat.generateViewId();
                tv.setId(id);
                viewIdBuilder.add(id);
                binding.tags.addView(tv);
            }
            binding.flowWidget.setReferencedIds(Ints.toArray(viewIdBuilder.build()));
        }
    }

    private void updateStatusContainerVisibility() {
        if (binding.statusContainer == null) {
            return;
        }
        final boolean lastSeenVisible = binding.detailsLastseen.getVisibility() == View.VISIBLE;
        final boolean statusVisible = binding.detailsStatusTag.getVisibility() == View.VISIBLE;
        if (statusVisible) {
            final ViewGroup.MarginLayoutParams layoutParams =
                    (ViewGroup.MarginLayoutParams) binding.detailsStatusTag.getLayoutParams();
            final int marginStart =
                    lastSeenVisible
                            ? getResources().getDimensionPixelSize(R.dimen.spacing_small)
                            : 0;
            if (layoutParams.getMarginStart() != marginStart) {
                layoutParams.setMarginStart(marginStart);
                binding.detailsStatusTag.setLayoutParams(layoutParams);
            }
        }
        binding.statusContainer.setVisibility((lastSeenVisible || statusVisible) ? View.VISIBLE : View.GONE);
    }


    @Override
    public void onBackendConnected() {
        if (accountJid != null && contactJid != null) {
            Account account = xmppConnectionService.findAccountByJid(accountJid);
            if (account == null) {
                return;
            }
            this.contact = account.getRoster().getContact(contactJid);
            if (mPendingFingerprintVerificationUri != null) {
                processFingerprintVerification(mPendingFingerprintVerificationUri);
                mPendingFingerprintVerificationUri = null;
            }

            if (Compatibility.hasStoragePermission()) {
                loadMedia();
                this.binding.showMedia.setOnClickListener((v) -> MediaBrowserActivity.launch(this, contact));
            }
            populateView();
        }
    }

    @Override
    public void onKeyStatusUpdated(AxolotlService.FetchStatus report) {
        refreshUi();
    }

    @Override
    protected void addFingerprintRow(LinearLayout keys, final XmppAxolotlSession session, boolean highlight) {
        final Account account = session.getAccount();
        final String fingerprint = session.getFingerprint();
        addFingerprintRowWithListeners(keys,
                account,
                fingerprint,
                highlight,
                session.getTrust(),
                true,
                true,
                null,
                (buttonView, isChecked) -> account.getAxolotlService().setFingerprintTrust(fingerprint, FingerprintStatus.createActive(isChecked)));
    }

    @Override
    protected void processFingerprintVerification(XmppUri uri) {
        if (contact != null && contact.getJid().asBareJid().equals(uri.getJid()) && uri.hasFingerprints()) {
            if (xmppConnectionService.verifyFingerprints(contact, uri.getFingerprints())) {
                Toast.makeText(this, R.string.verified_fingerprints, Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, R.string.invalid_barcode, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Also called when a scan changes which files are reachable, so media restored while this
     * screen is open appears without having to close and reopen it.
     */
    @Override
    public void onMediaAvailabilityChanged() {
        runOnUiThread(this::loadMedia);
    }

    private void loadMedia() {
        if (xmppConnectionService == null || contact == null) {
            return;
        }
        final int limit = GridManager.getCurrentColumnCount(this.binding.media);
        xmppConnectionService.getAttachments(
                contact.getAccount(), contact.getJid().asBareJid(), limit, this);
    }

    @Override
    public void onMediaLoaded(List<Attachment> attachments) {
        runOnUiThread(() -> {
            int limit = GridManager.getCurrentColumnCount(binding.media);
            mMediaAdapter.setAttachments(attachments.subList(0, Math.min(limit, attachments.size())));
            binding.mediaWrapper.setVisibility(attachments.size() > 0 ? View.VISIBLE : View.GONE);
        });

    }
}
