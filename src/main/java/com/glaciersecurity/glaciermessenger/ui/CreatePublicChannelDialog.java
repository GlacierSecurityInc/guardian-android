package com.glaciersecurity.glaciermessenger.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.DialogFragment;

import com.glaciersecurity.glaciermessenger.utils.RandomNameGenerator;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.DialogCreatePublicChannelBinding;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.ui.adapter.KnownHostsAdapter;
import com.glaciersecurity.glaciermessenger.ui.interfaces.OnBackendConnected;
import com.glaciersecurity.glaciermessenger.utils.CryptoHelper;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.glaciersecurity.glaciermessenger.xmpp.XmppConnection;

public class CreatePublicChannelDialog extends DialogFragment implements OnBackendConnected {

    private static final char[] FORBIDDEN = new char[]{'\u0022','&','\'','/',':','<','>','@'};

    private static final String ACCOUNTS_LIST_KEY = "activated_accounts_list";
    private CreatePublicChannelDialogListener mListener;
    private KnownHostsAdapter knownHostsAdapter;
    private boolean jidWasModified = false;
    private boolean skipTetxWatcher = false;

    public static CreatePublicChannelDialog newInstance(final List<String> accounts) {
        CreatePublicChannelDialog dialog = new CreatePublicChannelDialog();
        Bundle bundle = new Bundle();
        bundle.putStringArrayList(ACCOUNTS_LIST_KEY, new ArrayList<>(accounts));
        dialog.setArguments(bundle);
        return dialog;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setRetainInstance(true);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        jidWasModified = savedInstanceState != null && savedInstanceState.getBoolean("jid_was_modified", false);
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity());
        builder.setTitle(R.string.create_public_channel);
        final DialogCreatePublicChannelBinding binding = DataBindingUtil.inflate(getActivity().getLayoutInflater(), R.layout.dialog_create_public_channel, null, false);
        binding.account.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateJidSuggestion(binding);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });
        binding.jid.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable s) {
                if (skipTetxWatcher) {
                    return;
                }
                if (jidWasModified) {
                    jidWasModified = !TextUtils.isEmpty(s);
                } else {
                    jidWasModified = !s.toString().equals(getJidSuggestion(binding));
                }
            }
        });
        ArrayList<String> mActivatedAccounts = getArguments().getStringArrayList(ACCOUNTS_LIST_KEY);
        StartConversationActivity.populateAccountSpinner(getActivity(), mActivatedAccounts, binding.account);
        builder.setView(binding.getRoot());
        builder.setPositiveButton(R.string.create,  null);
        builder.setNegativeButton(R.string.cancel, null);
        this.knownHostsAdapter = new KnownHostsAdapter(getActivity(), R.layout.item_autocomplete);
        binding.jid.setAdapter(knownHostsAdapter);
        binding.groupChatName.setHint(RandomNameGenerator.generateRandomName());
        final AlertDialog dialog = builder.create();
        binding.groupChatName.setOnEditorActionListener((v, actionId, event) -> {
            submit(dialog, binding);
            return true;
        });
        dialog.setOnShowListener(dialogInterface -> {
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v -> goBack(dialog));
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> submit(dialog, binding));
        });
        return dialog;
    }

    private void updateJidSuggestion(final DialogCreatePublicChannelBinding binding) {
        if (jidWasModified) {
            return;
        }
        String jid = getJidSuggestion(binding);
        skipTetxWatcher = true;
        binding.jid.setText(jid);
        skipTetxWatcher = false;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("jid_was_modified",jidWasModified);
        super.onSaveInstanceState(outState);
    }

    private static String getJidSuggestion(final DialogCreatePublicChannelBinding binding) {
        final Account account = StartConversationActivity.getSelectedAccount(binding.getRoot().getContext(), binding.account);
        final XmppConnection connection = account == null ? null : account.getXmppConnection();
        if (connection == null) {
            return "";
        }
        String groupName = binding.groupChatName.getText().toString().trim();
        if (groupName.isEmpty()) {
            groupName = binding.groupChatName.getHint().toString().trim();
        }
        final String domain = connection.getMucServer();
        if (domain == null) {
            return "";
        }
        final String localpart = clean(groupName);
        if (TextUtils.isEmpty(localpart)) {
            return "";
        } else {
            try {
                return Jid.of(localpart, domain, null).toEscapedString();
            } catch (IllegalArgumentException e) {
                return Jid.of(CryptoHelper.pronounceable(), domain, null).toEscapedString();
            }
        }
    }

    private static String clean(String name) {
        for(char c : FORBIDDEN) {
            name = name.replace(String.valueOf(c),"");
        }
        return name.replaceAll("\\s+","-");
    }

    private void goBack(AlertDialog dialog) {
        dialog.dismiss();
    }

    private void submit(AlertDialog dialog, DialogCreatePublicChannelBinding binding) {
        final Context context = binding.getRoot().getContext();
        String groupName = binding.groupChatName.getText().toString().trim();
        if (groupName.isEmpty()) {
            groupName = binding.groupChatName.getHint().toString().trim();
        }
        binding.xmppAddressLayout.setError(null);
        if (groupName.isEmpty()) {
            binding.nameLayout.setError(context.getText(R.string.please_enter_name));
            return;
        }
        if (StartConversationActivity.isValidJid(groupName)) {
            binding.nameLayout.setError(context.getText(R.string.this_is_an_xmpp_address));
            return;
        }
        binding.nameLayout.setError(null);
        // through updateJidSuggestion, not by hand: it silences the watcher, so refreshing the
        // suggestion cannot itself latch jidWasModified and freeze the address on a later retry
        updateJidSuggestion(binding);
        final Editable addressText = binding.jid.getText();
        final String address = addressText == null ? "" : addressText.toString().trim();
        if (address.isEmpty()) {
            binding.xmppAddressLayout.setError(context.getText(R.string.please_enter_xmpp_address));
            return;
        }
        final Jid jid;
        try {
            jid = Jid.ofUserInput(address);
        } catch (IllegalArgumentException e) {
            binding.xmppAddressLayout.setError(context.getText(R.string.invalid_jid));
            return;
        }
        final Account account = StartConversationActivity.getSelectedAccount(context, binding.account);
        if (account == null) {
            return;
        }
        final XmppConnectionService service = ((XmppActivity) context).xmppConnectionService;
        if (service != null && service.findFirstMuc(jid) != null) {
            binding.xmppAddressLayout.setError(context.getString(R.string.channel_already_exists));
            return;
        }
        mListener.onCreatePublicChannel(account, groupName, jid);
        dialog.dismiss();
    }




    @Override
    public void onBackendConnected() {
        refreshKnownHosts();
    }

    private void refreshKnownHosts() {
        Activity activity = getActivity();
        if (activity instanceof XmppActivity) {
            Collection<String> hosts = ((XmppActivity) activity).xmppConnectionService.getKnownConferenceHosts();
            this.knownHostsAdapter.refresh(hosts);
        }
    }

    public interface CreatePublicChannelDialogListener {
        void onCreatePublicChannel(Account account, String name, Jid address);
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        try {
            mListener = (CreatePublicChannelDialogListener) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context.toString()
                    + " must implement CreateConferenceDialogListener");
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        final Activity activity = getActivity();
        if (activity instanceof XmppActivity && ((XmppActivity) activity).xmppConnectionService != null) {
            refreshKnownHosts();
        }
    }

    @Override
    public void onDestroyView() {
        Dialog dialog = getDialog();
        if (dialog != null && getRetainInstance()) {
            dialog.setDismissMessage(null);
        }
        super.onDestroyView();
    }
}
