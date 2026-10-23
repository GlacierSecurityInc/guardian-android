package com.glaciersecurity.glaciermessenger.ui.fragment.phone;

import static androidx.core.content.PermissionChecker.checkSelfPermission;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.PermissionChecker;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ContactsListViewBinding;
import com.glaciersecurity.glaciermessenger.entities.NativeContact;
import com.glaciersecurity.glaciermessenger.entities.SmsProfile;
import com.glaciersecurity.glaciermessenger.entities.TwilioConversationEntity;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.ui.ExternalCallActivity;
import com.glaciersecurity.glaciermessenger.ui.SmsConvActivity;
import com.glaciersecurity.glaciermessenger.ui.StartSmsOrCallDialog;
import com.glaciersecurity.glaciermessenger.ui.UriHandlerActivity;
import com.glaciersecurity.glaciermessenger.ui.PhoneModuleActivity;
import com.glaciersecurity.glaciermessenger.ui.adapter.NativeListItemAdapter;
import com.glaciersecurity.glaciermessenger.ui.util.PendingItem;
import com.glaciersecurity.glaciermessenger.ui.util.SoftKeyboardUtils;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.utils.PhoneUtils;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public class ContactsPhoneFragment extends PhoneFragment
        implements XmppConnectionService.OnNativeContactUpdate, NativeListItemAdapter.OnContactActionListener {
    private ContactsListViewBinding binding;
    private final int REQUEST_SYNC_CONTACTS = 0x28ed;
    private final PendingItem<Intent> pendingViewIntent = new PendingItem<>();
    private final PendingItem<String> mInitialSearchValue = new PendingItem<>();
    private final AtomicBoolean oneShotKeyboardSuppress = new AtomicBoolean();
    private final List<NativeContact> nativeContacts = new ArrayList<>();
    private final List<NativeContact> nativeContactsDisplay = new ArrayList<>();
    private NativeListItemAdapter  mPhoneContactAdapter;
    private EditText mSearchEditText;
    private final AtomicBoolean mRequestedContactsPermission = new AtomicBoolean(false);
    private static final int CONTACT_PERMISSION_REQUEST_CODE = 0x0310;
    private PhoneModuleActivity activity;
    private TextView contactsTitle;
    private TextView contactsSubject;
    private final ExecutorService contactsExecutor = Executors.newSingleThreadExecutor();
    private static final Pattern NUMBER_PATTERN = Pattern.compile("^[+\\d][\\d\\s()\\-]*$");

    private final MenuItem.OnActionExpandListener mOnActionExpandListener =
            new MenuItem.OnActionExpandListener() {

                @Override
                public boolean onMenuItemActionExpand(@NonNull final MenuItem item) {
                    mSearchEditText.post(
                            () -> {
                                mSearchEditText.requestFocus();
                                if (oneShotKeyboardSuppress.compareAndSet(true, false)) {
                                    return;
                                }
                                InputMethodManager imm =
                                        (InputMethodManager)
                                                activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                                if (imm != null) {
                                    imm.showSoftInput(
                                            mSearchEditText, InputMethodManager.SHOW_IMPLICIT);
                                }
                            });

                    return true;
                }

                @Override
                public boolean onMenuItemActionCollapse(@NonNull final MenuItem item) {
                    SoftKeyboardUtils.hideSoftKeyboard(activity);
                    mSearchEditText.setText("");
                    filter(null);
                    return true;
                }
            };
    private final TextWatcher mSearchTextWatcher =
            new TextWatcher() {

                @Override
                public void afterTextChanged(Editable editable) {
                    filter(editable.toString());
                }

                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
            };
    private final TextWatcher mSearchNumberTextWatcher =
            new TextWatcher() {

                @Override
                public void afterTextChanged(Editable editable) {
                    filter(editable.toString());
                    updateActionRow(editable.toString());
                }

                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
            };
    private MenuItem mMenuSearchView;
    private final NativeListItemAdapter.OnTagClickedListener mOnTagClickedListener =
            new NativeListItemAdapter.OnTagClickedListener() {
                @Override
                public void onTagClicked(String tag) {
                    if (mMenuSearchView != null) {
                        mMenuSearchView.expandActionView();
                        mSearchEditText.setText("");
                        mSearchEditText.append(tag);
                        filter(tag);
                    }
                }
            };

    protected void filter(String needle) {
        if (activity.xmppConnectionServiceBound) {
            this.filterNativeContacts(needle);
        }
    }

    protected void filterNativeContacts(String needle) {
        this.nativeContactsDisplay.clear();
        for (NativeContact contact: nativeContacts){
            if (contact.match(getContext(), needle)){
                nativeContactsDisplay.add(contact);
            }
        }
        Collections.sort(this.nativeContactsDisplay);
        mPhoneContactAdapter.updateContactList(nativeContactsDisplay);
    }


    protected void refreshUiReal() {
        if (mSearchEditText != null) {
            filter(mSearchEditText.getText().toString());
        }
        else {
            filter(binding.searchPhoneNumber.getText().toString());
        }

    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = DataBindingUtil.inflate(inflater, R.layout.contacts_list_view, container, false);
        mPhoneContactAdapter = new NativeListItemAdapter(activity, nativeContacts);
        mPhoneContactAdapter.setOnTagClickedListener(this.mOnTagClickedListener);
        mPhoneContactAdapter.setOnContactActionListener(this);
        binding.recyclerView.setAdapter(mPhoneContactAdapter);
        this.binding.recyclerView.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.VERTICAL, false));

        binding.searchPhoneNumber.addTextChangedListener(mSearchNumberTextWatcher);
        binding.searchPhoneNumber.setOnEditorActionListener(mSearchDone);

        // Trailing pill controls: keypad opens the dialer, clear empties the field.
        binding.buttonKeypad.setOnClickListener(v -> openDialer());
        binding.clearSearch.setOnClickListener(v -> binding.searchPhoneNumber.setText(""));

        // Action rows: add a new contact, or call the number that was typed.
        binding.actionNewContact.setOnClickListener(v -> openNewContact());
        binding.actionCallNumber.setOnClickListener(v -> submitEntry());

        updateActionRow("");

        binding.grantPermissionsBtn.setOnClickListener(v -> askForContactsPermissions());
        View grantPermissionEmptyView =
                binding.emptyContactsList.findViewById(R.id.grant_permissions_btn);
        grantPermissionEmptyView.setOnClickListener(v -> askForContactsPermissions());
        grantPermissionEmptyView.setVisibility(View.GONE);
        contactsTitle = binding.emptyContactsList.findViewById(R.id.contacts_title);
        contactsSubject = binding.emptyContactsList.findViewById(R.id.contacts_subject);

        if (hasContactPermissions()){
            getContactList();
            binding.grantPermissionsBtn.setVisibility(View.GONE);

        } else {
            binding.grantPermissionsBtn.setVisibility(View.VISIBLE);
            contactsTitle.setText(R.string.no_contact_permission);
            contactsSubject.setText(R.string.no_contacts_permissions_yet);
        }

        if(!(nativeContactsDisplay.isEmpty())) {
            binding.emptyContactsList.setVisibility(View.GONE);
        } else {
            binding.emptyContactsList.setVisibility(View.VISIBLE);
        }

        return binding.getRoot();
    }

    @Override
    public void onStart() {
        super.onStart();
        mPhoneContactAdapter.refreshSettings();
        if (pendingViewIntent.peek() == null) {
            if (askForContactsPermissions()) {
                return;
            }
        }
    }

    private boolean askForContactsPermissions() {
        if (hasContactPermissions()) {
            return false;
        }
        if (shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)
                || !mRequestedContactsPermission.getAndSet(true)) {
            requestPermission(CONTACT_PERMISSION_REQUEST_CODE);
        } else {
            Intent intent =
                    new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", activity.getPackageName(), null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0)
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                UriHandlerActivity.onRequestPermissionResult(activity, requestCode, grantResults);
                if (requestCode == REQUEST_SYNC_CONTACTS && activity.xmppConnectionServiceBound) {
                    activity.xmppConnectionService.loadPhoneContacts();
                    activity.xmppConnectionService.startContactObserver();
                } else if (requestCode == CONTACT_PERMISSION_REQUEST_CODE) {
                    getContactList();
                    binding.grantPermissionsBtn.setVisibility(View.GONE);
                    View grantPermissionEmptyView =
                            binding.emptyContactsList.findViewById(R.id.grant_permissions_btn);
                    grantPermissionEmptyView.setVisibility(View.GONE);
                }
            }
    }

    @Override
    public void onResume() {
        super.onResume();
        StartSmsOrCallDialog.dismiss();
        binding.searchPhoneNumber.setText("");
        hideKeyboardOnEntry();
        // Reload from the address book so contacts added elsewhere (e.g. via the
        // "New contact" action) show up immediately on return, without re-entering.
        if (hasContactPermissions()) {
            getContactList();
        } else {
            filter(null);
        }
    }

    private void hideKeyboardOnEntry() {
        View view = activity.getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }


    private void submitEntry(){

        String phoneNumber = binding.searchPhoneNumber.getText().toString().trim();
        if (phoneNumber.length() < 3 || !NUMBER_PATTERN.matcher(phoneNumber).matches()) {
            return;
        }
        StartSmsOrCallDialog.show(activity, new NativeContact("Phone Number", phoneNumber, "phone"));
    }

    /**
     * Shows the "Call or message" affordance above the always-present "New
     * contact" action when the field holds a dialable number, and toggles the
     * trailing keypad / clear glyph.
     */
    private void updateActionRow(String text) {
        if (binding == null) {
            return;
        }
        String trimmed = text == null ? "" : text.trim();
        boolean isNumber = trimmed.length() >= 3 && NUMBER_PATTERN.matcher(trimmed).matches();

        binding.actionCallNumber.setVisibility(isNumber ? View.VISIBLE : View.GONE);
        if (isNumber) {
            binding.callNumberValue.setText(trimmed);
        }

        boolean hasText = !trimmed.isEmpty();
        binding.clearSearch.setVisibility(hasText ? View.VISIBLE : View.GONE);
        binding.buttonKeypad.setVisibility(hasText ? View.GONE : View.VISIBLE);
    }

    private void openDialer() {
        BottomNavigationView nav = activity.findViewById(R.id.navigation_phone);
        if (nav != null) {
            nav.setSelectedItemId(R.id.dialer_nav);
        }
    }

    private void openNewContact() {
        Intent intent = new Intent(Intent.ACTION_INSERT);
        intent.setType(ContactsContract.Contacts.CONTENT_TYPE);
        String typed = binding.searchPhoneNumber.getText().toString().trim();
        if (!typed.isEmpty() && NUMBER_PATTERN.matcher(typed).matches()) {
            intent.putExtra(ContactsContract.Intents.Insert.PHONE, typed);
        }
        try {
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            Log.w(Config.LOGTAG, "No activity found to insert a new contact", e);
        }
    }

    // ── Per-row quick actions ──────────────────────────────────────────────────
    private boolean hasMultipleNumbers(NativeContact contact) {
        return contact.getNumbers() != null && contact.getNumbers().size() > 1;
    }

    @Override
    public void onOpenContact(NativeContact contact) {
        // The number picker only makes sense when there's more than one number;
        // for a single number, default to opening the message conversation.
        if (hasMultipleNumbers(contact)) {
            StartSmsOrCallDialog.show(activity, contact);
        } else {
            onMessageContact(contact);
        }
    }

    @Override
    public void onCallContact(NativeContact contact) {
        if (hasMultipleNumbers(contact)) {
            StartSmsOrCallDialog.show(activity, contact);
            return;
        }
        String number = contact.getPrimaryNumber();
        SmsProfile profile = activity.selectedProfile;
        if (number == null || profile == null || activity.xmppConnectionService == null) {
            StartSmsOrCallDialog.show(activity, contact);
            return;
        }
        String toPhoneNumber = PhoneUtils.getStripPhoneNumber(number);
        if (activity.xmppConnectionService.getSmsInfo().containsProxy(toPhoneNumber)) {
            return;
        }
        Intent callActivity = new Intent(activity, ExternalCallActivity.class);
        callActivity.setAction(Config.ACTION_OUTGOING_CALL);
        callActivity.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        callActivity.putExtra("toNumber", toPhoneNumber);
        callActivity.putExtra(Config.TO_PROXY, profile.getUnformattedNumber());
        startActivity(callActivity);
    }

    @Override
    public void onMessageContact(NativeContact contact) {
        if (!hasMultipleNumbers(contact) && contact.getPrimaryNumber() != null) {
            OnSMSConversationClick(contact.getPrimaryNumber());
        } else {
            StartSmsOrCallDialog.show(activity, contact);
        }
    }



    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.action_contact_menu, menu);
        mMenuSearchView = menu.findItem(R.id.action_search);
        mMenuSearchView.setOnActionExpandListener(mOnActionExpandListener);
        View mSearchView = mMenuSearchView.getActionView();
        mSearchEditText = mSearchView.findViewById(R.id.search_field);
        mSearchEditText.setHint(R.string.search_contacts);
        mSearchEditText.setContentDescription(getString(R.string.search_contacts));
        mSearchEditText.addTextChangedListener(mSearchTextWatcher);
        mSearchEditText.setOnEditorActionListener(mSearchDone);
        String initialSearchValue = mInitialSearchValue.pop();
        if (initialSearchValue != null) {
            mMenuSearchView.expandActionView();
            mSearchEditText.append(initialSearchValue);
            filter(initialSearchValue);
        }
        super.onCreateOptionsMenu(menu, menuInflater);
    }

    private final TextView.OnEditorActionListener mSearchDone =
            (v, actionId, event) -> {
                boolean isEnterKeyEvent =
                        event != null
                                && event.getAction() == KeyEvent.ACTION_DOWN
                                && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;

                if (actionId == EditorInfo.IME_ACTION_DONE
                        || actionId == EditorInfo.IME_ACTION_SEND
                        || actionId == EditorInfo.IME_ACTION_GO
                        || isEnterKeyEvent) {
                    submitEntry();
                    return true;
                }

                return false;
            };

    protected boolean hasContactPermissions() {
        return checkSelfPermission(getContext(), Manifest.permission.READ_CONTACTS) == PermissionChecker.PERMISSION_GRANTED;
    }
    protected void requestPermission(final int request_code) {
        if (!hasContactPermissions()) {
            requestPermissions(
                    new String[]{Manifest.permission.READ_CONTACTS},
                    request_code);
        }
    }
    public void OnSMSConversationClick(String contactNumber) {
        TwilioConversationEntity conv = activity.xmppConnectionService.getConversationsBetweenContactAndProxy(contactNumber, activity.selectedProfile);
        Intent intent = new Intent(activity, SmsConvActivity.class);
        if (conv != null) {
            startActivity(intent.putExtra("conv_sid", conv.getConv_sid()).putExtra("friendly_name", conv.getFriendlyName()));
        } else {
            startActivity(intent.putExtra("friendly_name", PhoneUtils.getStripPhoneNumberWithCountryCode(contactNumber)));
        }
    }


    @SuppressLint("Range")
    private void getContactList(){
        contactsExecutor.execute(() -> {
            Context context = getContext();
            if (context == null) {
                return;
            }
        Uri uri = ContactsContract.Contacts.CONTENT_URI;
        String sort = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME+" ASC";
            List<NativeContact> loadedContacts = new ArrayList<>();
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, sort)) {
                if (cursor == null) {
                    Log.w(Config.LOGTAG, "getContactList: null cursor returned");
                    return;
                }
        if(cursor.getCount() > 0){
            while (cursor.moveToNext()){
                NativeContact nc = NativeContact.fromCursor(cursor);
                if (nc.getPrimaryNumber()!= null && nc.getDisplayName() != null) {
                            loadedContacts.add(nc);
                }

            }
                }
        }

            if (!isAdded()) {
                return;
            }
            if (activity == null) {
                return;
            }
            activity.runOnUiThread(() -> {
                nativeContacts.clear();
                nativeContactsDisplay.clear();
                nativeContacts.addAll(loadedContacts);
                nativeContactsDisplay.addAll(loadedContacts);
                Collections.sort(nativeContactsDisplay);
        if (nativeContactsDisplay.isEmpty()){
            binding.emptyContactsList.setVisibility(View.VISIBLE);
            contactsTitle.setText(R.string.no_contacts);
            contactsSubject.setText(R.string.no_contacts_yet);
        } else {
            binding.emptyContactsList.setVisibility(View.GONE);
        }
        mPhoneContactAdapter.updateContactList(nativeContactsDisplay);

            });
        });
    }

    @Override
    public void onNativeContactUpdate() {
        activity.refresh();
    }


    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (context instanceof PhoneModuleActivity) {
            this.activity = (PhoneModuleActivity) context;
        } else {
            throw new IllegalStateException(
                    "Trying to attach fragment to activity that is not the PHoneModuleActivity");
        }
    }


    @Override
    protected void refresh() {

    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        contactsExecutor.shutdownNow();
    }
    @Override
    public void onBackendConnected() {

        if (checkSelfPermission(getContext(), Manifest.permission.READ_CONTACTS)
                == PermissionChecker.PERMISSION_GRANTED) {
            activity.xmppConnectionService.getQuickConversationsService().considerSyncBackground();
        }
    }
}
