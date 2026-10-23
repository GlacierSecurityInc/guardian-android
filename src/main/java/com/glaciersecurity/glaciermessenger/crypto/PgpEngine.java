package com.glaciersecurity.glaciermessenger.crypto;

import android.app.PendingIntent;
import android.content.Intent;

import androidx.annotation.StringRes;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;

import org.openintents.openpgp.OpenPgpError;
import org.openintents.openpgp.OpenPgpSignatureResult;
import org.openintents.openpgp.util.OpenPgpApi;
import org.openintents.openpgp.util.OpenPgpApi.IOpenPgpCallback;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Contact;
import com.glaciersecurity.glaciermessenger.entities.Conversation;
import com.glaciersecurity.glaciermessenger.entities.DownloadableFile;
import com.glaciersecurity.glaciermessenger.entities.Message;
import com.glaciersecurity.glaciermessenger.persistance.FileBackend;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.ui.UiCallback;
import com.glaciersecurity.glaciermessenger.utils.AsciiArmor;

public class PgpEngine {
    private final OpenPgpApi api;
    private final XmppConnectionService mXmppConnectionService;

    public PgpEngine(OpenPgpApi api, XmppConnectionService service) {
        this.api = api;
        this.mXmppConnectionService = service;
    }

    private static void logError(Account account, OpenPgpError error) {
        if (error != null) {
            error.describeContents();
        }
    }

    public void encrypt(final Message message, final UiCallback<Message> callback) {
        Intent params = new Intent();
        params.setAction(OpenPgpApi.ACTION_ENCRYPT);
        final Conversation conversation = (Conversation) message.getConversation();
        if (conversation.getMode() == Conversation.MODE_SINGLE) {
            long[] keys = {
                    conversation.getContact().getPgpKeyId(),
                    conversation.getAccount().getPgpId()
            };
            params.putExtra(OpenPgpApi.EXTRA_KEY_IDS, keys);
        } else {
            params.putExtra(OpenPgpApi.EXTRA_KEY_IDS, conversation.getMucOptions().getPgpKeyIds());
        }

        if (!message.needsUploading()) {
            params.putExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, true);
            String body;
            if (message.hasFileOnRemoteHost()) {
                body = message.getFileParams().url;
            } else {
                body = message.getBody();
            }
            InputStream is = new ByteArrayInputStream(body.getBytes());
            final OutputStream os = new ByteArrayOutputStream();
            api.executeApiAsync(params, is, os, result -> {
                switch (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
                    case OpenPgpApi.RESULT_CODE_SUCCESS:
                        try {
                            os.flush();
                            final ArrayList<String> encryptedMessageBody = new ArrayList<>();
                            final String[] lines = os.toString().split("\n");
                            for (int i = 2; i < lines.length - 1; ++i) {
                                if (!lines[i].contains("Version")) {
                                    encryptedMessageBody.add(lines[i].trim());
                                }
                            }
                            message.setEncryptedBody(Joiner.on('\n').join(encryptedMessageBody));
                            message.setEncryption(Message.ENCRYPTION_DECRYPTED);
                            mXmppConnectionService.sendMessage(message);
                            callback.success(message);
                        } catch (IOException e) {
                            callback.error(R.string.openpgp_error);
                        }

                        break;
                    case OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED:
                        callback.userInputRequired(result.getParcelableExtra(OpenPgpApi.RESULT_INTENT));
                        break;
                    case OpenPgpApi.RESULT_CODE_ERROR:
                        OpenPgpError error = result.getParcelableExtra(OpenPgpApi.RESULT_ERROR);
                        String errorMessage = error != null ? error.getMessage() : null;
                        @StringRes final int res;
                        if (errorMessage != null && errorMessage.startsWith("Bad key for encryption")) {
                            res = R.string.bad_key_for_encryption;
                        } else {
                            res = R.string.openpgp_error;
                        }
                        logError(conversation.getAccount(), error);
                        callback.error(res);
                        break;
                }
            });
        } else {
            try {
                DownloadableFile inputFile = this.mXmppConnectionService
                        .getFileBackend().getFile(message, true);
                DownloadableFile outputFile = this.mXmppConnectionService
                        .getFileBackend().getFile(message, false);
                outputFile.getParentFile().mkdirs();
                if (outputFile.createNewFile()) {
                }
                final InputStream is = new FileInputStream(inputFile);
                final OutputStream os = new FileOutputStream(outputFile);
                api.executeApiAsync(params, is, os, result -> {
                    try {
                        switch (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
                            case OpenPgpApi.RESULT_CODE_SUCCESS:
                                try {
                                    os.flush();
                                } catch (IOException ignored) {
                                    //ignored
                                }
                                FileBackend.close(os);
                                mXmppConnectionService.sendMessage(message);
                                callback.success(message);
                                break;
                            case OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED:
                                callback.userInputRequired(result.getParcelableExtra(OpenPgpApi.RESULT_INTENT));
                                break;
                            case OpenPgpApi.RESULT_CODE_ERROR:
                                logError(conversation.getAccount(), result.getParcelableExtra(OpenPgpApi.RESULT_ERROR));
                                callback.error(R.string.openpgp_error);
                                break;
                        }
                    } finally {
                        // The API is done with both streams by the time it calls back;
                        // os is closed above on success, and close() is idempotent.
                        FileBackend.close(is);
                        FileBackend.close(os);
                    }
                });
            } catch (final IOException e) {
                callback.error(R.string.openpgp_error);
            }
        }
    }

    public long fetchKeyId(final Account account, final String status, final String signature) {
        if (signature == null || api == null) {
            return 0;
        }
        final Intent params = new Intent();
        params.setAction(OpenPgpApi.ACTION_DECRYPT_VERIFY);
        try {
            params.putExtra(OpenPgpApi.RESULT_DETACHED_SIGNATURE, AsciiArmor.decode(signature));
        } catch (final Exception e) {
            return 0;
        }
        final InputStream is = new ByteArrayInputStream(Strings.nullToEmpty(status).getBytes());
        final ByteArrayOutputStream os = new ByteArrayOutputStream();
        final Intent result = api.executeApi(params, is, os);
        switch (result.getIntExtra(OpenPgpApi.RESULT_CODE,
                OpenPgpApi.RESULT_CODE_ERROR)) {
            case OpenPgpApi.RESULT_CODE_SUCCESS:
                final OpenPgpSignatureResult sigResult = result.getParcelableExtra(OpenPgpApi.RESULT_SIGNATURE);
                if (sigResult != null) {
                    return sigResult.getKeyId();
                } else {
                    return 0;
                }
            case OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED:
                return 0;
            case OpenPgpApi.RESULT_CODE_ERROR:
                logError(account, result.getParcelableExtra(OpenPgpApi.RESULT_ERROR));
                return 0;
        }
        return 0;
    }

    public void chooseKey(final Account account, final UiCallback<Account> callback) {
        Intent p = new Intent();
        p.setAction(OpenPgpApi.ACTION_GET_SIGN_KEY_ID);
        api.executeApiAsync(p, null, null, result -> {
            switch (result.getIntExtra(OpenPgpApi.RESULT_CODE, 0)) {
                case OpenPgpApi.RESULT_CODE_SUCCESS:
                    callback.success(account);
                    return;
                case OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED:
                    callback.userInputRequired(result.getParcelableExtra(OpenPgpApi.RESULT_INTENT));
                    return;
                case OpenPgpApi.RESULT_CODE_ERROR:
                    logError(account, result.getParcelableExtra(OpenPgpApi.RESULT_ERROR));
                    callback.error(R.string.openpgp_error);
            }
        });
    }

    public void generateSignature(Intent intent, final Account account, String status, final UiCallback<String> callback) {
        if (account.getPgpId() == 0) {
            return;
        }
        Intent params = intent == null ? new Intent() : intent;
        params.setAction(OpenPgpApi.ACTION_CLEARTEXT_SIGN);
        params.putExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, true);
        params.putExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, account.getPgpId());
        InputStream is = new ByteArrayInputStream(status.getBytes());
        final OutputStream os = new ByteArrayOutputStream();
        api.executeApiAsync(params, is, os, result -> {
            switch (result.getIntExtra(OpenPgpApi.RESULT_CODE, 0)) {
                case OpenPgpApi.RESULT_CODE_SUCCESS:
                    final ArrayList<String> signature = new ArrayList<>();
                    try {
                        os.flush();
                        boolean sig = false;
                        for (final String line : Splitter.on('\n').split(os.toString())) {
                            if (sig) {
                                if (line.contains("END PGP SIGNATURE")) {
                                    sig = false;
                                } else {
                                    if (!line.contains("Version")) {
                                        signature.add(line.trim());
                                    }
                                }
                            }
                            if (line.contains("BEGIN PGP SIGNATURE")) {
                                sig = true;
                            }
                        }
                    } catch (IOException e) {
                        callback.error(R.string.openpgp_error);
                        return;
                    }
                    callback.success(Joiner.on('\n').join(signature));
                    return;
                case OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED:
                    callback.userInputRequired(result.getParcelableExtra(OpenPgpApi.RESULT_INTENT));
                    return;
                case OpenPgpApi.RESULT_CODE_ERROR:
                    OpenPgpError error = result.getParcelableExtra(OpenPgpApi.RESULT_ERROR);
                    if (error != null && "signing subkey not found!".equals(error.getMessage())) {
                        callback.error(0);
                    } else {
                        logError(account, error);
                        callback.error(R.string.unable_to_connect_to_keychain);
                    }
            }
        });
    }

    public void hasKey(final Contact contact, final UiCallback<Contact> callback) {
        Intent params = new Intent();
        params.setAction(OpenPgpApi.ACTION_GET_KEY);
        params.putExtra(OpenPgpApi.EXTRA_KEY_ID, contact.getPgpKeyId());
        api.executeApiAsync(params, null, null, new IOpenPgpCallback() {

            @Override
            public void onReturn(Intent result) {
                switch (result.getIntExtra(OpenPgpApi.RESULT_CODE, 0)) {
                    case OpenPgpApi.RESULT_CODE_SUCCESS:
                        callback.success(contact);
                        return;
                    case OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED:
                        callback.userInputRequired(result.getParcelableExtra(OpenPgpApi.RESULT_INTENT));
                        return;
                    case OpenPgpApi.RESULT_CODE_ERROR:
                        logError(contact.getAccount(), result.getParcelableExtra(OpenPgpApi.RESULT_ERROR));
                        callback.error(R.string.openpgp_error);
                }
            }
        });
    }

    public PendingIntent getIntentForKey(long pgpKeyId) {
        Intent params = new Intent();
        params.setAction(OpenPgpApi.ACTION_GET_KEY);
        params.putExtra(OpenPgpApi.EXTRA_KEY_ID, pgpKeyId);
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        final ByteArrayInputStream inputStream = new ByteArrayInputStream(new byte[0]);
        Intent result = api.executeApi(params, inputStream, outputStream);
        return (PendingIntent) result.getParcelableExtra(OpenPgpApi.RESULT_INTENT);
    }
}
