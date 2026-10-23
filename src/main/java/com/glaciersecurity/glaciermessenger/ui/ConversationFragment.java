package com.glaciersecurity.glaciermessenger.ui;

import static com.glaciersecurity.glaciermessenger.ui.XmppActivity.EXTRA_ACCOUNT;
import static com.glaciersecurity.glaciermessenger.ui.XmppActivity.REQUEST_INVITE_TO_CONVERSATION;
import static com.glaciersecurity.glaciermessenger.ui.util.SoftKeyboardUtils.hideSoftKeyboard;
import static com.glaciersecurity.glaciermessenger.utils.Compatibility.getPreferences;
import static com.glaciersecurity.glaciermessenger.utils.PermissionUtils.allGranted;
import static com.glaciersecurity.glaciermessenger.utils.PermissionUtils.audioGranted;
import static com.glaciersecurity.glaciermessenger.utils.PermissionUtils.cameraGranted;
import static com.glaciersecurity.glaciermessenger.utils.PermissionUtils.getFirstDenied;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender.SendIntentException;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextUtils;

import com.glaciersecurity.glaciermessenger.EndPointConfig;
import com.glaciersecurity.glaciermessenger.utils.ContentUriValidator;
import com.glaciersecurity.glaciermessenger.utils.SerialSingleThreadExecutor;
import com.glaciersecurity.glaciermessenger.utils.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AbsListView.OnScrollListener;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView.OnEditorActionListener;
import android.widget.Toast;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.MenuCompat;
import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.core.view.inputmethod.InputContentInfoCompat;
import androidx.databinding.DataBindingUtil;

import com.glaciersecurity.glaciermessenger.databinding.FragmentConversationBinding;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.AxolotlService;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.FingerprintStatus;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Bookmark;
import com.glaciersecurity.glaciermessenger.entities.Blockable;
import com.glaciersecurity.glaciermessenger.entities.Contact;
import com.glaciersecurity.glaciermessenger.entities.Conversation;
import com.glaciersecurity.glaciermessenger.entities.Conversational;
import com.glaciersecurity.glaciermessenger.entities.DownloadableFile;
import com.glaciersecurity.glaciermessenger.entities.Message;
import com.glaciersecurity.glaciermessenger.entities.MucOptions;
import com.glaciersecurity.glaciermessenger.entities.MucOptions.User;
import com.glaciersecurity.glaciermessenger.entities.ReadByMarker;
import com.glaciersecurity.glaciermessenger.entities.Transferable;
import com.glaciersecurity.glaciermessenger.entities.TransferablePlaceholder;
import com.glaciersecurity.glaciermessenger.http.mobile.MobileUploadClient;
import com.glaciersecurity.glaciermessenger.persistance.DatabaseBackend;
import com.glaciersecurity.glaciermessenger.persistance.FileBackend;
import com.glaciersecurity.glaciermessenger.services.CallIntegrationConnectionService;
import com.glaciersecurity.glaciermessenger.services.MessageArchiveService;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.ui.adapter.MediaPreviewAdapter;
import com.glaciersecurity.glaciermessenger.ui.adapter.MessageAdapter;
import com.glaciersecurity.glaciermessenger.ui.util.ActivityResult;
import com.glaciersecurity.glaciermessenger.ui.util.Attachment;
import com.glaciersecurity.glaciermessenger.ui.util.ConversationMenuConfigurator;
import com.glaciersecurity.glaciermessenger.ui.util.DateSeparator;
import com.glaciersecurity.glaciermessenger.ui.util.EditMessageActionModeCallback;
import com.glaciersecurity.glaciermessenger.ui.util.ListViewUtils;
import com.glaciersecurity.glaciermessenger.ui.util.MenuDoubleTabUtil;
import com.glaciersecurity.glaciermessenger.ui.util.MucDetailsContextMenuHelper;
import com.glaciersecurity.glaciermessenger.ui.util.PendingItem;
import com.glaciersecurity.glaciermessenger.ui.util.PresenceSelector;
import com.glaciersecurity.glaciermessenger.ui.util.ScrollState;
import com.glaciersecurity.glaciermessenger.ui.util.SendButtonAction;
import com.glaciersecurity.glaciermessenger.ui.util.SendButtonTool;
import com.glaciersecurity.glaciermessenger.ui.util.ShareUtil;
import com.glaciersecurity.glaciermessenger.ui.util.ViewUtil;
import com.glaciersecurity.glaciermessenger.ui.widget.EditMessage;
import com.glaciersecurity.glaciermessenger.utils.Compatibility;
import com.glaciersecurity.glaciermessenger.utils.GeoHelper;
import com.glaciersecurity.glaciermessenger.utils.MessageUtils;
import com.glaciersecurity.glaciermessenger.utils.NickValidityChecker;
import com.glaciersecurity.glaciermessenger.utils.PermissionUtils;
import com.glaciersecurity.glaciermessenger.utils.QuickLoader;
import com.glaciersecurity.glaciermessenger.utils.StylingHelper;
import com.glaciersecurity.glaciermessenger.utils.TimeFrameUtils;
import com.glaciersecurity.glaciermessenger.utils.UIHelper;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.glaciersecurity.glaciermessenger.xmpp.XmppConnection;
import com.glaciersecurity.glaciermessenger.xmpp.chatstate.ChatState;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.AbstractJingleConnection;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.JingleConnectionManager;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.Media;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.OngoingRtpSession;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.RtpCapability;


import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConversationFragment extends XmppFragment
        implements EditMessage.KeyboardListener,
                MessageAdapter.OnContactPictureLongClicked,
                MessageAdapter.OnContactPictureClicked {

    public static final int REQUEST_SEND_MESSAGE = 0x0201;
    public static final int REQUEST_DECRYPT_PGP = 0x0202;
    public static final int REQUEST_ENCRYPT_MESSAGE = 0x0207;
    public static final int REQUEST_TRUST_KEYS_TEXT = 0x0208;
    public static final int REQUEST_TRUST_KEYS_ATTACHMENTS = 0x0209;
    public static final int REQUEST_START_DOWNLOAD = 0x0210;
    public static final int REQUEST_ADD_EDITOR_CONTENT = 0x0211;
    public static final int REQUEST_COMMIT_ATTACHMENTS = 0x0212;
    public static final int REQUEST_START_AUDIO_CALL = 0x213;
    public static final int REQUEST_START_VIDEO_CALL = 0x214;
    public static final int REQUEST_SAVE_BACKUP = 0x215;
    public static final int REQUEST_DOWNLOAD_TO = 0x0215;
    public static final int ATTACHMENT_CHOICE_CHOOSE_IMAGE = 0x0301;
    public static final int ATTACHMENT_CHOICE_TAKE_PHOTO = 0x0302;
    public static final int ATTACHMENT_CHOICE_CHOOSE_FILE = 0x0303;
    public static final int ATTACHMENT_CHOICE_RECORD_VOICE = 0x0304;
    public static final int ATTACHMENT_CHOICE_LOCATION = 0x0305;
    public static final int ATTACHMENT_CHOICE_INVALID = 0x0306;
    public static final int ATTACHMENT_CHOICE_RECORD_VIDEO = 0x0307;

    public static final String RECENTLY_USED_QUICK_ACTION = "recently_used_quick_action";
    public static final String STATE_CONVERSATION_UUID =
            ConversationFragment.class.getName() + ".uuid";
    public static final String STATE_SCROLL_POSITION =
            ConversationFragment.class.getName() + ".scroll_position";
    public static final String STATE_PHOTO_URI =
            ConversationFragment.class.getName() + ".media_previews";
    public static final String STATE_MEDIA_PREVIEWS =
            ConversationFragment.class.getName() + ".take_photo_uri";
    private static final String STATE_LAST_MESSAGE_UUID = "state_last_message_uuid";

    private final List<Message> messageList = new ArrayList<>();
    private final PendingItem<ActivityResult> postponedActivityResult = new PendingItem<>();
    private final PendingItem<String> pendingConversationsUuid = new PendingItem<>();
    private final PendingItem<ArrayList<Attachment>> pendingMediaPreviews = new PendingItem<>();
    private final PendingItem<Bundle> pendingExtras = new PendingItem<>();
    private final PendingItem<Uri> pendingTakePhotoUri = new PendingItem<>();
    private final PendingItem<ScrollState> pendingScrollState = new PendingItem<>();
    private final PendingItem<String> pendingLastMessageUuid = new PendingItem<>();
    private final PendingItem<Message> pendingMessage = new PendingItem<>();
    private final PendingItem<Message> pendingPgpMessage = new PendingItem<>();
    public Uri mPendingEditorContent = null;
    protected MessageAdapter messageListAdapter;
    private MediaPreviewAdapter mediaPreviewAdapter;
    private String lastMessageUuid = null;
    private Conversation conversation;
    private FragmentConversationBinding binding;
    private Toast messageLoaderToast;
    private ConversationsActivity activity;
    private boolean reInitRequiredOnStart = true;
    private Handler disappearingHandler;
    private Runnable disRunnable;

    // Attachment export and expired-message purging both touch the disk or the database;
    // keep them off the UI thread, and off each other's thread so a large copy cannot
    // stall the one-second expiry tick.
    private static final SerialSingleThreadExecutor ATTACHMENT_EXPORT_EXECUTOR =
            new SerialSingleThreadExecutor("AttachmentExport");
    private static final SerialSingleThreadExecutor DISAPPEARING_EXECUTOR =
            new SerialSingleThreadExecutor("DisappearingMessages");
    private final OnClickListener clickToMuc =
            new OnClickListener() {

                @Override
                public void onClick(View v) {
                    ConferenceDetailsActivity.open(getActivity(), conversation);
                }
            };
    private final OnClickListener leaveMuc =
            new OnClickListener() {

                @Override
                public void onClick(View v) {
                    activity.xmppConnectionService.archiveConversation(conversation);
                }
            };
    private final OnClickListener joinMuc =
            new OnClickListener() {

                @Override
                public void onClick(View v) {
                    activity.xmppConnectionService.joinMuc(conversation);
                }
            };

    private final OnClickListener acceptJoin =
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    conversation.setAttribute("accept_non_anonymous", true);
                    activity.xmppConnectionService.updateConversation(conversation);
                    activity.xmppConnectionService.joinMuc(conversation);
                }
            };

    private final OnClickListener enterPassword =
            new OnClickListener() {

                @Override
                public void onClick(View v) {
                    MucOptions muc = conversation.getMucOptions();
                    String password = muc.getPassword();
                    if (password == null) {
                        password = "";
                    }
                    activity.quickPasswordEdit(
                            password,
                            value -> {
                                activity.xmppConnectionService.providePasswordForMuc(
                                        conversation, value);
                                return null;
                            });
                }
            };
    private final OnScrollListener mOnScrollListener =
            new OnScrollListener() {

                @Override
                public void onScrollStateChanged(AbsListView view, int scrollState) {
                    if (AbsListView.OnScrollListener.SCROLL_STATE_IDLE == scrollState) {
                        fireReadEvent();
                    }
                }

                @Override
                public void onScroll(
                        final AbsListView view,
                        int firstVisibleItem,
                        int visibleItemCount,
                        int totalItemCount) {
                    toggleScrollDownButton(view);
                    synchronized (ConversationFragment.this.messageList) {
                        if (firstVisibleItem < 5
                                && conversation != null
                                && conversation.messagesLoaded.compareAndSet(true, false)
                                && messageList.size() > 0) {
                            long timestamp;
                            if (messageList.get(0).getType() == Message.TYPE_STATUS
                                    && messageList.size() >= 2) {
                                timestamp = messageList.get(1).getTimeSent();
                            } else {
                                timestamp = messageList.get(0).getTimeSent();
                            }
                            activity.xmppConnectionService.loadMoreMessages(
                                    conversation,
                                    timestamp,
                                    new XmppConnectionService.OnMoreMessagesLoaded() {
                                        @Override
                                        public void onMoreMessagesLoaded(
                                                final Conversation conversation) {
                                            if (ConversationFragment.this.conversation
                                                    != conversation) {
                                                conversation.messagesLoaded.set(true);
                                                return;
                                            }
                                            runOnUiThread(
                                                    () -> {
                                                        if (binding == null) {
                                                            // Scrolled to the top, then navigated
                                                            // away before the load finished. Still
                                                            // release the latch: leaving it false
                                                            // blocks every later load.
                                                            conversation.messagesLoaded.set(true);
                                                            return;
                                                        }
                                                        synchronized (messageList) {
                                                            final int oldPosition =
                                                                    binding.messagesView
                                                                            .getFirstVisiblePosition();
                                                            Message message = null;
                                                            int childPos;
                                                            for (childPos = 0;
                                                                    childPos + oldPosition
                                                                            < messageList.size();
                                                                    ++childPos) {
                                                                message =
                                                                        messageList.get(
                                                                                oldPosition
                                                                                        + childPos);
                                                                if (message.getType()
                                                                        != Message.TYPE_STATUS) {
                                                                    break;
                                                                }
                                                            }
                                                            final String uuid =
                                                                    message != null
                                                                            ? message.getUuid()
                                                                            : null;
                                                            View v =
                                                                    binding.messagesView.getChildAt(
                                                                            childPos);
                                                            final int pxOffset =
                                                                    (v == null) ? 0 : v.getTop();
                                                            ConversationFragment.this.conversation
                                                                    .populateWithMessages(
                                                                            ConversationFragment
                                                                                    .this
                                                                                    .messageList);
                                                            try {
                                                                updateStatusMessages();
                                                            } catch (IllegalStateException e) {
                                                            }
                                                            messageListAdapter
                                                                    .notifyDataSetChanged();
                                                            int pos =
                                                                    Math.max(
                                                                            getIndexOf(
                                                                                    uuid,
                                                                                    messageList),
                                                                            0);
                                                            binding.messagesView
                                                                    .setSelectionFromTop(
                                                                            pos, pxOffset);
                                                            if (messageLoaderToast != null) {
                                                                messageLoaderToast.cancel();
                                                            }
                                                            conversation.messagesLoaded.set(true);
                                                        }
                                                    });
                                        }

                                        @Override
                                        public void informUser(final int resId) {

                                            runOnUiThread(
                                                    () -> {
                                                        if (messageLoaderToast != null) {
                                                            messageLoaderToast.cancel();
                                                        }
                                                        if (ConversationFragment.this.conversation
                                                                != conversation) {
                                                            return;
                                                        }
                                                        messageLoaderToast =
                                                                Toast.makeText(
                                                                        view.getContext(),
                                                                        resId,
                                                                        Toast.LENGTH_LONG);
                                                        messageLoaderToast.show();
                                                    });
                                        }
                                    });
                        }
                    }
                }
            };
    private final EditMessage.OnCommitContentListener mEditorContentListener =
            new EditMessage.OnCommitContentListener() {
                @Override
                public boolean onCommitContent(
                        InputContentInfoCompat inputContentInfo, int flags) {
                    // try to get permission to read the image, if applicable
                    if ((flags & InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION)
                            != 0) {
                        try {
                            inputContentInfo.requestPermission();
                        } catch (Exception e) {
                            Log.e(
                                    Config.LOGTAG,
                                    "InputContentInfoCompat#requestPermission() failed.",
                                    e);
                            Toast.makeText(
                                            getActivity(),
                                            activity.getString(
                                                    R.string.no_permission_to_access_x,
                                                    inputContentInfo.getDescription()),
                                            Toast.LENGTH_LONG)
                                    .show();
                            return false;
                        }
                    }
                    if (hasPermissions(REQUEST_ADD_EDITOR_CONTENT)) {
                        attachEditorContentToConversation(inputContentInfo.getContentUri());
                    } else {
                        mPendingEditorContent = inputContentInfo.getContentUri();
                    }
                    return true;
                }
            };
    private final OnClickListener mEnableAccountListener =
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    final Account account = conversation == null ? null : conversation.getAccount();
                    if (account != null) {
                        account.setOption(Account.OPTION_SOFT_DISABLED, false);
                        account.setOption(Account.OPTION_DISABLED, false);
                        activity.xmppConnectionService.updateAccount(account);
                    }
                }
            };
    private final OnClickListener mUnblockClickListener =
            new OnClickListener() {
                @Override
                public void onClick(final View v) {
                    v.post(() -> v.setVisibility(View.INVISIBLE));
                    if (conversation.isDomainBlocked()) {
                        BlockContactDialog.show(activity, conversation);
                    } else {
                        unblockConversation(conversation);
                    }
                }
            };
    private final OnClickListener mAddBackClickListener =
            new OnClickListener() {

                @Override
                public void onClick(View v) {
                    final Contact contact = conversation == null ? null : conversation.getContact();
                    if (contact != null) {
                        activity.xmppConnectionService.createContact(contact, true);
                        activity.switchToContactDetails(contact);
                    }
                }
            };
    private final View.OnLongClickListener mLongPressBlockListener = this::showBlockSubmenu;
    private final OnClickListener mAllowPresenceSubscription =
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    final Contact contact = conversation == null ? null : conversation.getContact();
                    if (contact != null) {
                        activity.xmppConnectionService.sendPresencePacket(
                                contact.getAccount(),
                                activity.xmppConnectionService
                                        .getPresenceGenerator()
                                        .sendPresenceUpdatesTo(contact));
                        hideSnackbar();
                    }
                }
            };
    protected OnClickListener clickToDecryptListener =
            new OnClickListener() {

                @Override
                public void onClick(View v) {
                    PendingIntent pendingIntent =
                            conversation.getAccount().getPgpDecryptionService().getPendingIntent();
                    if (pendingIntent != null) {
                        try {
                            getActivity()
                                    .startIntentSenderForResult(
                                            pendingIntent.getIntentSender(),
                                            REQUEST_DECRYPT_PGP,
                                            null,
                                            0,
                                            0,
                                            0,
                                            Compatibility.pgpStartIntentSenderOptions());
                        } catch (SendIntentException e) {
                            Toast.makeText(
                                            getActivity(),
                                            R.string.unable_to_connect_to_keychain,
                                            Toast.LENGTH_SHORT)
                                    .show();
                            conversation
                                    .getAccount()
                                    .getPgpDecryptionService()
                                    .continueDecryption(true);
                        }
                    }
                    updateSnackBar(conversation);
                }
            };
    private final AtomicBoolean mSendingPgpMessage = new AtomicBoolean(false);
    private final OnEditorActionListener mEditorActionListener =
            (v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    InputMethodManager imm =
                            (InputMethodManager)
                                    activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null && imm.isFullscreenMode()) {
                        imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                    }
                    sendMessage();
                    return true;
                } else {
                    return false;
                }
            };
    private final OnClickListener mScrollButtonListener =
            new OnClickListener() {

                @Override
                public void onClick(View v) {
                    stopScrolling();
                    setSelection(binding.messagesView.getCount() - 1, true);
                }
            };
    private final OnClickListener mSendButtonListener =
            new OnClickListener() {

                @Override
                public void onClick(View v) {
                    Object tag = v.getTag();
                    if (tag instanceof SendButtonAction) {
                        SendButtonAction action = (SendButtonAction) tag;
                        switch (action) {
                            case TAKE_PHOTO:
                            case RECORD_VIDEO:
                            case SEND_LOCATION:
                            case RECORD_VOICE:
                            case CHOOSE_PICTURE:
                                attachFile(action.toChoice());
                                break;
                            case CANCEL:
                                if (conversation != null) {
                                    if (conversation.setCorrectingMessage(null)) {
                                        binding.textinput.setText("");
                                        binding.textinput.append(conversation.getDraftMessage());
                                        conversation.setDraftMessage(null);
                                    } else if (conversation.getMode() == Conversation.MODE_MULTI) {
                                        conversation.setNextCounterpart(null);
                                        binding.textinput.setText("");
                                    } else {
                                        binding.textinput.setText("");
                                    }
                                    updateChatMsgHint();
                                    updateSendButton();
                                    updateEditablity();
                                }
                                break;
                            default:
                                sendMessage();
                        }
                    } else {
                        sendMessage();
                    }
                }
            };
    private int completionIndex = 0;
    private int lastCompletionLength = 0;
    private String incomplete;
    private int lastCompletionCursor;
    private boolean firstWord = false;
    private Message mPendingDownloadableMessage;
    private Message pendingBackupExportMessage;
    private Message pendingAttachmentExportMessage;


    private static ConversationFragment findConversationFragment(FragmentActivity activity) {
        Fragment fragment = activity.getSupportFragmentManager().findFragmentById(R.id.main_fragment);
        if (fragment instanceof ConversationFragment) {
            return (ConversationFragment) fragment;
        }
        fragment = activity.getSupportFragmentManager().findFragmentById(R.id.secondary_fragment);
        if (fragment instanceof ConversationFragment) {
            return (ConversationFragment) fragment;
        }
        return null;
    }

    public static void startStopPending(FragmentActivity activity) {
        ConversationFragment fragment = findConversationFragment(activity);
        if (fragment != null) {
            fragment.messageListAdapter.startStopPending();
        }
    }

    public static void downloadFile(FragmentActivity activity, Message message) {
        ConversationFragment fragment = findConversationFragment(activity);
        if (fragment != null) {
            fragment.startDownloadable(message);
        }
    }

    public static void registerPendingMessage(FragmentActivity activity, Message message) {
        ConversationFragment fragment = findConversationFragment(activity);
        if (fragment != null) {
            fragment.pendingMessage.push(message);
        }
    }

    public static void openPendingMessage(FragmentActivity activity) {
        ConversationFragment fragment = findConversationFragment(activity);
        if (fragment != null) {
            Message message = fragment.pendingMessage.pop();
            if (message != null) {
                fragment.messageListAdapter.openDownloadable(message);
            }
        }
    }

    public static Conversation getConversation(FragmentActivity activity) {
        return getConversation(activity, R.id.secondary_fragment);
    }

    private static Conversation getConversation(FragmentActivity activity, @IdRes int res) {
        final Fragment fragment = activity.getSupportFragmentManager().findFragmentById(res);
        if (fragment instanceof ConversationFragment) {
            return ((ConversationFragment) fragment).getConversation();
        } else {
            return null;
        }
    }

    public static ConversationFragment get(FragmentActivity activity) {
        FragmentManager fragmentManager = activity.getSupportFragmentManager();
        Fragment fragment = fragmentManager.findFragmentById(R.id.main_fragment);
        if (fragment instanceof ConversationFragment) {
            return (ConversationFragment) fragment;
        } else {
            fragment = fragmentManager.findFragmentById(R.id.secondary_fragment);
            return fragment instanceof ConversationFragment
                    ? (ConversationFragment) fragment
                    : null;
        }
    }

    public static Conversation getConversationReliable(FragmentActivity activity) {
        final Conversation conversation = getConversation(activity, R.id.secondary_fragment);
        if (conversation != null) {
            return conversation;
        }
        return getConversation(activity, R.id.main_fragment);
    }

    private static boolean scrolledToBottom(AbsListView listView) {
        final int count = listView.getCount();
        if (count == 0) {
            return true;
        } else if (listView.getLastVisiblePosition() == count - 1) {
            final View lastChild = listView.getChildAt(listView.getChildCount() - 1);
            return lastChild != null && lastChild.getBottom() <= listView.getHeight();
        } else {
            return false;
        }
    }

    private void toggleScrollDownButton() {
        toggleScrollDownButton(binding.messagesView);
    }

    private void toggleScrollDownButton(AbsListView listView) {
        if (conversation == null) {
            return;
        }
        if (scrolledToBottom(listView)) {
            lastMessageUuid = null;
            hideUnreadMessagesCount();
        } else {
            binding.scrollToBottomButton.setEnabled(true);
            binding.scrollToBottomButton.show();
            if (lastMessageUuid == null) {
                lastMessageUuid = conversation.getLatestMessage().getUuid();
            }
            if (conversation.getReceivedMessagesCountSinceUuid(lastMessageUuid) > 0) {
                binding.unreadCountCustomView.setVisibility(View.VISIBLE);
            }
        }
    }

    private int getIndexOf(String uuid, List<Message> messages) {
        if (uuid == null) {
            return messages.size() - 1;
        }
        for (int i = 0; i < messages.size(); ++i) {
            if (uuid.equals(messages.get(i).getUuid())) {
                return i;
            } else {
                Message next = messages.get(i);
                while (next != null && next.wasMergedIntoPrevious()) {
                    if (uuid.equals(next.getUuid())) {
                        return i;
                    }
                    next = next.next();
                }
            }
        }
        return -1;
    }

    private ScrollState getScrollPosition() {
        final ListView listView = this.binding == null ? null : this.binding.messagesView;
        if (listView == null
                || listView.getCount() == 0
                || listView.getLastVisiblePosition() == listView.getCount() - 1) {
            return null;
        } else {
            final int pos = listView.getFirstVisiblePosition();
            final View view = listView.getChildAt(0);
            if (view == null) {
                return null;
            } else {
                return new ScrollState(pos, view.getTop());
            }
        }
    }

    private void setScrollPosition(ScrollState scrollPosition, String lastMessageUuid) {
        if (binding == null) {
            return;
        }
        if (scrollPosition != null) {

            this.lastMessageUuid = lastMessageUuid;
            if (lastMessageUuid != null) {
                binding.unreadCountCustomView.setUnreadCount(
                        conversation.getReceivedMessagesCountSinceUuid(lastMessageUuid));
            }
            this.binding.messagesView.setSelectionFromTop(
                    scrollPosition.position, scrollPosition.offset);
            toggleScrollDownButton();
        }
    }

    private void attachLocationToConversation(Conversation conversation, Uri uri) {
        if (conversation == null) {
            return;
        }
        activity.xmppConnectionService.attachLocationToConversation(
                conversation,
                uri,
                new UiCallback<Message>() {

                    @Override
                    public void success(Message message) {}

                    @Override
                    public void error(int errorCode) {
                    }

                    @Override
                    public void userInputRequired(PendingIntent pi) {}
                });
    }

    private void attachFileToConversation(Conversation conversation, Uri uri, String type) {
        if (conversation == null) {
            return;
        }
        final Toast prepareFileToast =
                Toast.makeText(getActivity(), getText(R.string.preparing_file), Toast.LENGTH_LONG);
        prepareFileToast.show();
        activity.delegateUriPermissionsToService(uri);
        activity.xmppConnectionService.attachFileToConversation(
                conversation,
                uri,
                type,
                new UiInformableCallback<Message>() {
                    @Override
                    public void inform(final String text) {
                        hidePrepareFileToast(prepareFileToast);
                        runOnUiThread(() -> activity.replaceToast(text));
                    }

                    @Override
                    public void success(Message message) {
                        runOnUiThread(() -> activity.hideToast());
                        hidePrepareFileToast(prepareFileToast);
                    }

                    @Override
                    public void error(final int errorCode) {
                        hidePrepareFileToast(prepareFileToast);
                        runOnUiThread(() -> activity.replaceToast(getString(errorCode)));
                    }

                    @Override
                    public void userInputRequired(PendingIntent pi) {
                        hidePrepareFileToast(prepareFileToast);
                    }
                });
    }

    public void attachEditorContentToConversation(Uri uri) {
        mediaPreviewAdapter.addMediaPreviews(
                Attachment.of(getActivity(), uri, Attachment.Type.FILE));
        toggleInputMethod();
    }

    private void attachImageToConversation(Conversation conversation, Uri uri, String type) {
        if (conversation == null) {
            return;
        }
        final Toast prepareFileToast =
                Toast.makeText(getActivity(), getText(R.string.preparing_image), Toast.LENGTH_LONG);
        prepareFileToast.show();
        activity.delegateUriPermissionsToService(uri);
        activity.xmppConnectionService.attachImageToConversation(
                conversation,
                uri,
                type,
                new UiCallback<Message>() {

                    @Override
                    public void userInputRequired(PendingIntent pi) {
                        hidePrepareFileToast(prepareFileToast);
                    }

                    @Override
                    public void success(Message message) {
                        hidePrepareFileToast(prepareFileToast);
                    }

                    @Override
                    public void error(final int error) {
                        hidePrepareFileToast(prepareFileToast);
                        final ConversationsActivity activity = ConversationFragment.this.activity;
                        if (activity == null) {
                            return;
                        }
                        activity.runOnUiThread(() -> activity.replaceToast(getString(error)));
                    }
                });
    }

    private void hidePrepareFileToast(final Toast prepareFileToast) {
        if (prepareFileToast != null && activity != null) {
            activity.runOnUiThread(prepareFileToast::cancel);
        }
    }

    private void sendMessage() {
        if (binding == null) {
            return;
        }
        final Editable text = this.binding.textinput.getText();
        final String body = text == null ? "" : text.toString();
        if (mediaPreviewAdapter.hasAttachments()) {
            commitAttachments(body);
            return;
        }
        sendTextMessage(body);
    }

    private void sendTextMessage(final String body) {
        final Conversation conversation = this.conversation;
        if (body.length() == 0 || conversation == null) {
            return;
        }
        if (trustKeysIfNeeded(conversation, REQUEST_TRUST_KEYS_TEXT)) {
            return;
        }
        final Message message;
        if (conversation.getCorrectingMessage() == null) {
            message = new Message(conversation, body, conversation.getNextEncryption());
            Message.configurePrivateMessage(message);
        } else {
            message = conversation.getCorrectingMessage();
            message.setBody(body);
            message.putEdited(message.getUuid(), message.getServerMsgId());
            message.setServerMsgId(null);
            message.setUuid(UUID.randomUUID().toString());
        }
        switch (conversation.getNextEncryption()) {
            case Message.ENCRYPTION_PGP:
                sendPgpMessage(message);
                break;
            default:
                sendMessage(message);
        }
    }

    private boolean trustKeysIfNeeded(final Conversation conversation, final int requestCode) {
        return conversation.getNextEncryption() == Message.ENCRYPTION_AXOLOTL
                && trustKeysIfNeeded(requestCode);
    }

    protected boolean trustKeysIfNeeded(int requestCode) {
        AxolotlService axolotlService = conversation.getAccount().getAxolotlService();
        final List<Jid> targets = axolotlService.getCryptoTargets(conversation);
        boolean hasUnaccepted = !conversation.getAcceptedCryptoTargets().containsAll(targets);
        boolean hasUndecidedOwn =
                !axolotlService
                        .getKeysWithTrust(FingerprintStatus.createActiveUndecided())
                        .isEmpty();
        boolean hasUndecidedContacts =
                !axolotlService
                        .getKeysWithTrust(FingerprintStatus.createActiveUndecided(), targets)
                        .isEmpty();
        boolean hasPendingKeys = !axolotlService.findDevicesWithoutSession(conversation).isEmpty();
        boolean hasNoTrustedKeys = axolotlService.anyTargetHasNoTrustedKeys(targets);
        boolean downloadInProgress = axolotlService.hasPendingKeyFetches(targets);
        boolean requiresUserAction = hasUndecidedOwn || hasUndecidedContacts || hasUnaccepted || downloadInProgress;
        if (requiresUserAction) {
            axolotlService.createSessionsIfNeeded(conversation);
            Intent intent = new Intent(getActivity(), TrustKeysActivity.class);
            String[] contacts = new String[targets.size()];
            for (int i = 0; i < contacts.length; ++i) {
                contacts[i] = targets.get(i).toString();
            }
            intent.putExtra("contacts", contacts);
            intent.putExtra(
                    EXTRA_ACCOUNT,
                    conversation.getAccount().getJid().asBareJid().toEscapedString());
            intent.putExtra("conversation", conversation.getUuid());
            startActivityForResult(intent, requestCode);
            conversation.resetMissingFingerprintsWarning();
            return true;
        }

        if (hasPendingKeys) {
            axolotlService.createSessionsIfNeeded(conversation);
        }

        if (hasNoTrustedKeys && !conversation.isMissingFingerprintsWarningShown()) {
//            Toast.makeText(
//                            getActivity(),
//                            R.string.omemo_missing_fingerprints_warning,
//                            Toast.LENGTH_LONG)
            conversation.markMissingFingerprintsWarningShown();
        }

        return false;
    }

    public void updateChatMsgHint() {
        if (binding == null) {
            return;
        }
        final boolean multi = conversation.getMode() == Conversation.MODE_MULTI;
        if (conversation.getCorrectingMessage() != null) {
            this.binding.textInputHint.setVisibility(View.GONE);
            this.binding.textinput.setHint(R.string.send_corrected_message);
        } else if (multi && conversation.getNextCounterpart() != null) {
            this.binding.textinput.setHint(R.string.send_unencrypted_message);
            this.binding.textInputHint.setVisibility(View.GONE);
            this.binding.textInputHint.setText(
                    getString(
                            R.string.send_private_message_to,
                            conversation.getNextCounterpart().getResource()));
        } else if (multi && !conversation.getMucOptions().participating()) {
            this.binding.textInputHint.setVisibility(View.GONE);
            this.binding.textinput.setHint(R.string.you_are_not_participating);
        } else {
            this.binding.textInputHint.setVisibility(View.GONE);
            this.binding.textinput.setHint(UIHelper.getMessageHint(getActivity(), conversation));
            getActivity().invalidateOptionsMenu();
        }
    }

    public void setupIme() {
        if (binding == null) {
            return;
        }
        this.binding.textinput.refreshIme();
    }

    private void handleActivityResult(ActivityResult activityResult) {
        if (activityResult.resultCode == Activity.RESULT_OK) {
            handlePositiveActivityResult(activityResult.requestCode, activityResult.data);
        } else {
            handleNegativeActivityResult(activityResult.requestCode);
        }
    }

    private void handlePositiveActivityResult(int requestCode, final Intent data) {
        switch (requestCode) {
            case REQUEST_TRUST_KEYS_TEXT:
                sendMessage();
                break;
            case REQUEST_TRUST_KEYS_ATTACHMENTS:
                commitAttachments();
                break;
            case REQUEST_START_AUDIO_CALL:
                triggerRtpSession(RtpSessionActivity.ACTION_MAKE_VOICE_CALL);
                break;
            case REQUEST_START_VIDEO_CALL:
                triggerRtpSession(RtpSessionActivity.ACTION_MAKE_VIDEO_CALL);
                break;
            case REQUEST_DOWNLOAD_TO:
                final Uri destination = data == null ? null : data.getData();
                handleAttachmentExportResult(destination);
                break;
            case ATTACHMENT_CHOICE_CHOOSE_IMAGE:
                final List<Attachment> imageUris =
                        Attachment.extractAttachments(getActivity(), data, Attachment.Type.IMAGE);
                mediaPreviewAdapter.addMediaPreviews(imageUris);
                toggleInputMethod();
                break;
            case ATTACHMENT_CHOICE_TAKE_PHOTO:
                final Uri takePhotoUri = pendingTakePhotoUri.pop();
                if (takePhotoUri != null) {
                    mediaPreviewAdapter.addMediaPreviews(
                            Attachment.of(getActivity(), takePhotoUri, Attachment.Type.IMAGE));
                    toggleInputMethod();
                }
                break;
            case ATTACHMENT_CHOICE_CHOOSE_FILE:
            case ATTACHMENT_CHOICE_RECORD_VIDEO:
            case ATTACHMENT_CHOICE_RECORD_VOICE:
                final Attachment.Type type =
                        requestCode == ATTACHMENT_CHOICE_RECORD_VOICE
                                ? Attachment.Type.RECORDING
                                : Attachment.Type.FILE;
                final List<Attachment> fileUris =
                        Attachment.extractAttachments(getActivity(), data, type);
                mediaPreviewAdapter.addMediaPreviews(fileUris);
                toggleInputMethod();
                break;
            case ATTACHMENT_CHOICE_LOCATION:
                if (data == null) {
                    break;
                }
                final double latitude = data.getDoubleExtra("latitude", 0);
                final double longitude = data.getDoubleExtra("longitude", 0);
                final int accuracy = data.getIntExtra("accuracy", 0);
                final Uri geo;
                if (accuracy > 0) {
                    geo = Uri.parse(String.format("geo:%s,%s;u=%s", latitude, longitude, accuracy));
                } else {
                    geo = Uri.parse(String.format("geo:%s,%s", latitude, longitude));
                }
                mediaPreviewAdapter.addMediaPreviews(
                        Attachment.of(getActivity(), geo, Attachment.Type.LOCATION));
                toggleInputMethod();
                break;
            case REQUEST_ENCRYPT_MESSAGE:
            case REQUEST_SEND_MESSAGE:
                resumePgpMessage();
                break;
            case REQUEST_INVITE_TO_CONVERSATION:
                XmppActivity.ConferenceInvite invite = XmppActivity.ConferenceInvite.parse(data);
                if (invite != null) {
                    if (invite.execute(activity)) {
                        activity.mToast =
                                Toast.makeText(
                                        activity, R.string.creating_conference, Toast.LENGTH_LONG);
                        activity.mToast.show();
                    }
                }
                break;
        }
    }

    private void commitAttachments() {
        if (binding == null) {
            return;
        }
        final Editable text = this.binding.textinput.getText();
        commitAttachments(text == null ? "" : text.toString());
    }

    private void commitAttachments(final String accompanyingText) {
        final List<Attachment> attachments = mediaPreviewAdapter.getAttachments();
        if (anyNeedsExternalStoragePermission(attachments)
                && !hasPermissions(
                        REQUEST_COMMIT_ATTACHMENTS)) {
            return;
        }
        if (trustKeysIfNeeded(conversation, REQUEST_TRUST_KEYS_ATTACHMENTS)) {
            return;
        }
        final PresenceSelector.OnPresenceSelected callback =
                () -> {
                    for (Iterator<Attachment> i = attachments.iterator(); i.hasNext(); i.remove()) {
                        final Attachment attachment = i.next();
                        if (attachment.getType() == Attachment.Type.LOCATION) {
                            attachLocationToConversation(conversation, attachment.getUri());
                        } else if (attachment.getType() == Attachment.Type.IMAGE) {
                            attachImageToConversation(
                                    conversation, attachment.getUri(), attachment.getMime());
                        } else {
                            attachFileToConversation(
                                    conversation, attachment.getUri(), attachment.getMime());
                        }
                    }
                    mediaPreviewAdapter.notifyDataSetChanged();
                    toggleInputMethod();
                    if (accompanyingText.length() > 0) {
                        sendTextMessage(accompanyingText);
                    } else {
                        messageSent();
                    }
                };
        if (conversation == null
                || conversation.getMode() == Conversation.MODE_MULTI
                || Attachment.canBeSendInBand(attachments)
                || (conversation.getAccount().httpUploadAvailable()
                        && FileBackend.allFilesUnderSize(
                                getActivity(), attachments, getMaxHttpUploadSize(conversation)))) {
            callback.onPresenceSelected();
        } else {
            activity.selectPresence(conversation, callback);
        }
    }

    private static boolean anyNeedsExternalStoragePermission(
            final Collection<Attachment> attachments) {
        for (final Attachment attachment : attachments) {
            if (attachment.getType() != Attachment.Type.LOCATION) {
                return true;
            }
        }
        return false;
    }

    public void toggleInputMethod() {
        if (binding == null) {
            return;
        }
        boolean hasAttachments = mediaPreviewAdapter.hasAttachments();
        binding.textinput.setVisibility(View.VISIBLE);
        binding.mediaPreview.setVisibility(hasAttachments ? View.VISIBLE : View.GONE);
        updateSendButton();
    }

    private void handleNegativeActivityResult(int requestCode) {
        switch (requestCode) {
            case ATTACHMENT_CHOICE_TAKE_PHOTO:
                if (pendingTakePhotoUri.clear()) {
                }
                break;
            case REQUEST_DOWNLOAD_TO:
                pendingAttachmentExportMessage = null;
                break;
            case REQUEST_ENCRYPT_MESSAGE:
            case REQUEST_SEND_MESSAGE:
                pendingPgpMessage.clear();
                mSendingPgpMessage.set(false);
                break;
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        ActivityResult activityResult = ActivityResult.of(requestCode, resultCode, data);
        if (activity != null && activity.xmppConnectionService != null) {
            handleActivityResult(activityResult);
        } else {
            this.postponedActivityResult.push(activityResult);
        }
    }

    public void unblockConversation(final Blockable conversation) {
        activity.xmppConnectionService.sendUnblockRequest(conversation);
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (context instanceof ConversationsActivity) {
            this.activity = (ConversationsActivity) context;
        } else {
            throw new IllegalStateException(
                    "Trying to attach fragment to activity that is not the ConversationsActivity");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        this.activity = null;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.fragment_conversation, menu);
        MenuCompat.setGroupDividerEnabled(menu, true);
        final MenuItem menuMucDetails = menu.findItem(R.id.action_muc_details);
        final MenuItem menuContactDetails = menu.findItem(R.id.action_contact_details);
        final MenuItem menuInviteContact = menu.findItem(R.id.action_invite);
        final MenuItem menuMute = menu.findItem(R.id.action_mute);
        final MenuItem menuUnmute = menu.findItem(R.id.action_unmute);
        final MenuItem menuCall = menu.findItem(R.id.action_call);
        final MenuItem menuOngoingCall = menu.findItem(R.id.action_ongoing_call);
        final MenuItem menuVideoCall = menu.findItem(R.id.action_video_call);
        final MenuItem menuTogglePinned = menu.findItem(R.id.action_toggle_pinned);
        final MenuItem menuMessageTimer = menu.findItem(R.id.action_message_timer);
        final MenuItem menuClearHistory = menu.findItem(R.id.action_clear_history);
        final MenuItem menuArchive = menu.findItem(R.id.action_archive);
        final MenuItem menuLeaveMuc = menu.findItem(R.id.action_leave_muc);


        if (conversation != null) {
            menuMessageTimer.setVisible(true);
            if (menuClearHistory != null) {
                menuClearHistory.setVisible(true);
            }
            if (menuArchive != null) {
                menuArchive.setVisible(true);
            }
            if (conversation.getMode() == Conversation.MODE_MULTI) {
                final boolean groupChat = conversation.getMucOptions().isPrivateAndNonAnonymous();
                menuContactDetails.setVisible(false);
                menuInviteContact.setVisible(conversation.getMucOptions().canInvite());
                menuMucDetails.setTitle(
                        groupChat ? R.string.action_muc_details : R.string.channel_details);
                menuCall.setVisible(false);
                menuOngoingCall.setVisible(false);
                // For group chats, "Archive" hides the conversation locally while keeping
                // the bookmark, and "Leave" permanently removes you from the room.
                if (menuArchive != null) {
                    menuArchive.setTitle(
                            groupChat
                                    ? R.string.action_archive_group
                                    : R.string.action_archive_channel);
                }
                if (menuLeaveMuc != null) {
                    menuLeaveMuc.setVisible(true);
                    menuLeaveMuc.setTitle(
                            groupChat
                                    ? R.string.action_leave_group
                                    : R.string.action_leave_channel);
                }
                if (menuLeaveMuc != null && menuLeaveMuc.getIcon() != null && getContext() != null) {
                    int errorColor = MaterialColors.getColor(getContext(), R.attr.errorColor, 0xFFE07B6E);
                    menuLeaveMuc.getIcon().setTint(errorColor);
                    menuLeaveMuc.setTitle(groupChat
                            ?
                            android.text.Html.fromHtml(
                                    "<font color='#" + String.format("%06X", 0xFFFFFF & errorColor) + "'>"
                                            + getString(R.string.action_leave_group) + "</font>") :android.text.Html.fromHtml(
                            "<font color='#" + String.format("%06X", 0xFFFFFF & errorColor) + "'>"
                                    + getString(R.string.action_leave_channel) + "</font>"));
                }
            } else {
                if (menuArchive != null) {
                    menuArchive.setTitle(R.string.action_archive_chat);
                }
                if (menuLeaveMuc != null) {
                    menuLeaveMuc.setVisible(false);
                }
                final XmppConnectionService service =
                        activity == null ? null : activity.xmppConnectionService;
                final Optional<OngoingRtpSession> ongoingRtpSession =
                        service == null
                                ? Optional.absent()
                                : service.getJingleConnectionManager()
                                        .getOngoingRtpConnection(conversation.getContact());
                if (ongoingRtpSession.isPresent()) {
                    menuOngoingCall.setVisible(true);
                    menuCall.setVisible(false);
                } else {
                    menuOngoingCall.setVisible(false);
                    final RtpCapability.Capability rtpCapability =
                            RtpCapability.check(conversation.getContact());
                    final boolean cameraAvailable =
                            activity != null && activity.isCameraFeatureAvailable();
                    menuCall.setVisible(rtpCapability != RtpCapability.Capability.NONE);
                    menuVideoCall.setVisible(
                            rtpCapability == RtpCapability.Capability.VIDEO && cameraAvailable);
                }
                menuMucDetails.setVisible(false);
                menuInviteContact.setVisible(
                        service != null
                                && service.findConferenceServer(conversation.getAccount()) != null);
            }
            if (conversation.getJid().asBareJid().equals(Config.BUG_REPORTS)) {
                menuMessageTimer.setVisible(false);
            }
            if (conversation.isMuted()) {
                menuMute.setVisible(false);
            } else {
                menuUnmute.setVisible(false);
            }
            ConversationMenuConfigurator.configureEncryptionMenu(conversation, menu);
            if (conversation.getBooleanAttribute(Conversation.ATTRIBUTE_PINNED_ON_TOP, false)) {
                menuTogglePinned.setTitle(R.string.remove_from_favorites);
                menuTogglePinned.setIcon(R.drawable.ic_push_pin_off_24dp);
            } else {
                menuTogglePinned.setTitle(R.string.add_to_favorites);
                menuTogglePinned.setIcon(R.drawable.ic_push_pin_24dp);

            }
        }
        super.onCreateOptionsMenu(menu, menuInflater);
    }

    @Override
    public View onCreateView(
            final LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        this.binding =
                DataBindingUtil.inflate(inflater, R.layout.fragment_conversation, container, false);
        binding.getRoot().setOnClickListener(null);

        binding.textinput.addTextChangedListener(
                new StylingHelper.MessageEditorStyler());

        binding.textinput.setOnEditorActionListener(mEditorActionListener);
        binding.textinput.setRichContentListener(new String[] {"image/*"}, mEditorContentListener);
        //binding.textinput.setOnFocusChangeListener(

        binding.textSendButton.setOnClickListener(this.mSendButtonListener);
        View avater_layout = getActivity().findViewById(R.id.avater_layout);
        if (avater_layout != null) {
            avater_layout.setVisibility(View.GONE);
        }
        View status_icon = getActivity().findViewById(R.id.statusIcon);
        if (status_icon != null) {
            status_icon.setVisibility(View.VISIBLE);
        }
        View details_expand = getActivity().findViewById(R.id.details_expand);
        if (details_expand != null) {
            details_expand.setVisibility(View.VISIBLE);
        }
        // Only when this conversation owns the whole window: in the two-pane layout the list is
        // still beside it, and nothing restores the bar (only the overview fragment's onCreateView
        // does, and it is not recreated), which strands every other destination on a tablet.
        View navView = getActivity().findViewById(R.id.navigation_conversations);
        if (navView != null && getActivity().findViewById(R.id.secondary_fragment) == null) {
            navView.setVisibility(View.GONE);
        }
        binding.scrollToBottomButton.setOnClickListener(this.mScrollButtonListener);
        binding.messagesView.setOnScrollListener(mOnScrollListener);
        binding.messagesView.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);
        mediaPreviewAdapter = new MediaPreviewAdapter(this);
        binding.mediaPreview.setAdapter(mediaPreviewAdapter);
        messageListAdapter = new MessageAdapter((XmppActivity) getActivity(), this.messageList);
        messageListAdapter.setOnContactPictureClicked(this);
        messageListAdapter.setOnContactPictureLongClicked(this);
        messageListAdapter.setOnMessageActionListener(this::onMessageAction);
        binding.messagesView.setAdapter(messageListAdapter);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.binding.textinput.setCustomInsertionActionModeCallback(
                    new EditMessageActionModeCallback(this.binding.textinput));
        }
        return binding.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        messageListAdapter.setOnContactPictureClicked(null);
        messageListAdapter.setOnContactPictureLongClicked(null);
        // The fragment outlives its view -- ConversationsActivity replaces it onto the back stack
        // -- so holding the binding here retains the whole inflated hierarchy. Every method below
        // that touches it already has, or now has, a null check for the same reason reInit() and
        // refresh() always did: the activity can call in before onCreateView too.
        binding = null;
    }

    private void quoteText(String text) {
        if (binding.textinput.isEnabled()) {
            binding.textinput.insertAsQuote(text);
            binding.textinput.requestFocus();
            InputMethodManager inputMethodManager =
                    (InputMethodManager)
                            getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethodManager != null) {
                inputMethodManager.showSoftInput(
                        binding.textinput, InputMethodManager.SHOW_IMPLICIT);
            }
        }
    }


    private void quoteMessage(Message message) {
        quoteText(MessageUtils.prepareQuote(message));
    }

    private boolean onMessageAction(final Message message, @IdRes final int itemId) {
        if (itemId == R.id.share_with) {
            ShareUtil.share(activity, message);
            return true;
        } else if (itemId == R.id.correct_message) {
            correctMessage(message);
            return true;
        } else if (itemId == R.id.copy_message) {
            ShareUtil.copyToClipboard(activity, message);
            return true;
        } else if (itemId == R.id.copy_link) {
            ShareUtil.copyLinkToClipboard(activity, message);
            return true;
        } else if (itemId == R.id.quote_message) {
            quoteMessage(message);
            return true;
        } else if (itemId == R.id.send_again) {
            resendMessage(message);
            return true;
        } else if (itemId == R.id.copy_url) {
            ShareUtil.copyUrlToClipboard(activity, message);
            return true;
        } else if (itemId == R.id.download_file) {
            startDownloadable(message);
            return true;
        } else if (itemId == R.id.download_file_to) {
            downloadFileToCustomLocation(message);
            return true;
        } else if (itemId == R.id.view_backup_as_text) {
            viewBackupAsText(message);
            return true;
        } else if (itemId == R.id.save_backup_to_directory) {
            saveBackupToDirectory(message);
            return true;
        } else if (itemId == R.id.cancel_transmission) {
            cancelTransmission(message);
            return true;
        } else if (itemId == R.id.retry_decryption) {
            retryDecryption(message);
            return true;
        } else if (itemId == R.id.delete_file) {
            deleteFile(message);
            return true;
        } else if (itemId == R.id.show_error_message) {
            showErrorMessage(message);
            return true;
        } else if (itemId == R.id.open_with) {
            openWith(message);
            return true;
        } else if (itemId == R.id.action_report_and_block) {
            reportMessage(message);
            return true;
        } else if (itemId == R.id.upload_bug_report) {
            uploadCrashTos3(message);
            return true;
        } else if (itemId == R.id.upload_file_s3) {
            shareFileSafe(message);
            return true;
        } else {
            return false;
        }
    }

    private void downloadFileToCustomLocation(Message message) {
        if (activity == null || activity.xmppConnectionService == null || !message.isFileOrImage()) {
            return;
        }
        final Context context = getContext();
        if (context == null) {
            return;
        }
        final DownloadableFile file = activity.xmppConnectionService.getFileBackend().getFile(message);
        if (!file.exists()) {
            Toast.makeText(context, R.string.attachment_not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        final Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        String mimeType = message.getMimeType();
        if (TextUtils.isEmpty(mimeType)) {
            mimeType = "*/*";
        }
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_TITLE, file.getName());
        pendingAttachmentExportMessage = message;
        try {
            startActivityForResult(intent, REQUEST_DOWNLOAD_TO);
        } catch (ActivityNotFoundException e) {
            pendingAttachmentExportMessage = null;
            Toast.makeText(context, R.string.no_application_found, Toast.LENGTH_LONG).show();
        }
    }

    private void handleAttachmentExportResult(Uri destination) {
        final Context context = getContext();
        final Message message = pendingAttachmentExportMessage;
        pendingAttachmentExportMessage = null;
        if (context == null || message == null || activity == null || activity.xmppConnectionService == null) {
            return;
        }
        if (destination == null) {
            Toast.makeText(context, R.string.attachment_save_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        // Comes back from another app's activity result, so it is not ours to trust.
        if (!ContentUriValidator.isSafeToResolve(context, destination)) {
            Log.e(Config.LOGTAG, "refusing to export an attachment to an untrusted destination");
            Toast.makeText(context, R.string.attachment_save_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        final DownloadableFile file = activity.xmppConnectionService.getFileBackend().getFile(message);
        if (!file.exists()) {
            Toast.makeText(context, R.string.attachment_not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        // Attachments can be arbitrarily large, so the copy runs off the UI thread and only
        // the result toast comes back to it.
        final Context appContext = context.getApplicationContext();
        ATTACHMENT_EXPORT_EXECUTOR.execute(
                () -> {
                    final int result;
                    int outcome = R.string.attachment_save_failed;
                    try (InputStream inputStream = new FileInputStream(file);
                            OutputStream outputStream =
                                    appContext.getContentResolver().openOutputStream(destination)) {
                        if (outputStream != null) {
                            final byte[] buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = inputStream.read(buffer)) != -1) {
                                outputStream.write(buffer, 0, bytesRead);
                            }
                            outcome = R.string.attachment_saved_custom_location;
                        }
                    } catch (final IOException e) {
                        Log.e(Config.LOGTAG, "Unable to save attachment", e);
                    }
                    result = outcome;
                    new Handler(Looper.getMainLooper())
                            .post(
                                    () ->
                                            Toast.makeText(appContext, result, Toast.LENGTH_SHORT)
                                                    .show());
                });
    }


    private void viewBackupAsText(final Message message) {
        final DownloadableFile file = ensureLocalBackupFile(message);
        if (file == null) {
            return;
        }
        final Intent intent = new Intent(activity, TextViewerActivity.class);
        intent.setData(FileBackend.getUriForFile(activity, file));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_TITLE, file.getName());
        startActivity(intent);
    }

    private void saveBackupToDirectory(final Message message) {
        if (!hasPermissions(REQUEST_SAVE_BACKUP)) {
            this.pendingBackupExportMessage = message;
            return;
        }
        final DownloadableFile file = ensureLocalBackupFile(message);
        if (file == null) {
            return;
        }
        this.pendingBackupExportMessage = null;
        final File directory = FileBackend.getBackupDirectoryCustom();
        if (!directory.exists() && !directory.mkdirs()) {
            Toast.makeText(activity, R.string.backup_save_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        final File destination = new File(directory, file.getName());
        try {
            copyFile(file, destination);
            activity.xmppConnectionService.getFileBackend().updateMediaScanner(destination);
            Toast.makeText(
                            activity,
                            getString(R.string.backup_save_success),
                            Toast.LENGTH_LONG)
                    .show();
        } catch (IOException e) {
            Log.e(Config.LOGTAG, "Unable to save backup file", e);
            Toast.makeText(activity, R.string.backup_save_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void copyFile(final File source, final File destination) throws IOException {
        try (InputStream in = new FileInputStream(source);
                OutputStream out = new FileOutputStream(destination)) {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }

    @Nullable
    private DownloadableFile ensureLocalBackupFile(final Message message) {
        if (!message.isFileOrImage()) {
            Toast.makeText(activity, R.string.backup_file_not_available, Toast.LENGTH_SHORT).show();
            return null;
        }
        final DownloadableFile file =
                activity.xmppConnectionService.getFileBackend().getFile(message);
        if (file.exists()) {
            return file;
        }
        final Transferable transferable = message.getTransferable();
        if (message.hasFileOnRemoteHost()
                || message.treatAsDownloadable()
                || transferable != null) {
            Toast.makeText(activity, R.string.backup_download_started, Toast.LENGTH_SHORT).show();
            startDownloadable(message);
        } else {
            Toast.makeText(activity, R.string.backup_file_not_available, Toast.LENGTH_SHORT).show();
        }
        return null;
    }

    private void shareFileSafe(Message selectedMessage) {
        if (selectedMessage.isFileOrImage()) {
            final DownloadableFile file = activity.xmppConnectionService.getFileBackend().getFile(selectedMessage);
            uploadFile(file);
        }
    }

    public void uploadFile(File file) {
        Account account = conversation.getAccount();
        if (file == null|| account == null ){
            return;
        }
        MobileUploadClient.uploadFile(
                EndPointConfig.FILESAFE_UPLOAD_URL,
                file,
                file.getName(),
                new MobileUploadClient.UploadCallback() {
                    @Override
                    public void onSuccess() {
                        Log.i(Config.LOGTAG, "Upload successful");
                        activity.runOnUiThread(() -> Toast.makeText(getContext(), R.string.upload_successful, Toast.LENGTH_SHORT).show());
                    }

                    @Override
                    public void onFailure(@NonNull Throwable throwable) {
                        final String uploadFailureMessage = getUploadFailureDisplayMessage(throwable);
                        Log.e(Config.LOGTAG, "Upload failed", throwable);
                        activity.runOnUiThread(() -> Toast.makeText(getContext(), uploadFailureMessage, Toast.LENGTH_SHORT).show());
                    }
                });

    }

    @NonNull
    private String getUploadFailureDisplayMessage(@NonNull final Throwable throwable) {
        if (throwable instanceof MobileUploadClient.UploadHttpException) {
            return ((MobileUploadClient.UploadHttpException) throwable).getDisplayMessage();
        }
        return "Upload failed";
    }

    private void uploadCrashTos3(final Message selectedMessage) {
        final String body = selectedMessage.getBody();

        // Validate before touching filesystem or network.
        // If the body doesn't look like a real stack trace, refuse — no feedback
        // to the caller, no upload, no temp file created.
        if (!looksLikeStackTrace(body)) {
            Log.w(Config.LOGTAG,
                    "uploadCrashTos3: body failed stack trace validation — upload suppressed");
            return;
        }

        try {
            final Account account = conversation.getAccount();

            // Write to a randomly-named temp file. Using createTempFile() gives an
            // unpredictable name; the original code used the message UUID which is
            // derived from conversation state and could be reconstructed.
            final File file = File.createTempFile("crash_upload_", ".tmp",
                    requireActivity().getCacheDir());

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                writer.write(body);
            } catch (IOException e) {
                Log.e(Config.LOGTAG, "uploadCrashTos3: error writing temp file", e);
                file.delete();
                return;
            }

            final String filename = String.format(
                    "%s_%s_%s_%s.txt",
                    account.getOrganization(),
                    account.getUsername(),
                    new SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US).format(new Date()),
                    UUID.randomUUID().toString());

            MobileUploadClient.uploadFile(
                    EndPointConfig.BUG_REPORT_UPLOAD_URL,
                    file,
                    filename,
                    new MobileUploadClient.UploadCallback() {
                        @Override
                        public void onSuccess() {
                            Log.i(Config.LOGTAG, "Bug report upload successful");
                            if (file.delete()) {
                            }
                        }

                        @Override
                        public void onFailure(@NonNull Throwable throwable) {
                            Log.e(Config.LOGTAG, "Bug report upload failed", throwable);
                            if (file.delete()) {
                            }
                        }
                    });

        } catch (IOException e) {
            Log.e(Config.LOGTAG, "uploadCrashTos3: could not create temp file", e);
        }
    }

    /**
     * Returns true only if the text plausibly looks like an Android stack trace
     * produced by {@link com.glaciersecurity.glaciermessenger.utils.ExceptionHandler}.
     *
     * Requires all three markers simultaneously to resist crafted messages:
     *   - "Version:" header written by ExceptionHandler at the start of every report
     *   - At least one JVM stack frame ("\tat ")
     *   - At least one Exception or Error class name
     */
    private static boolean looksLikeStackTrace(final String text) {
        if (text == null || text.length() < 50) {
            return false;
        }
        final boolean hasVersionHeader = text.startsWith("Version:");
        final boolean hasStackFrame    = text.contains("\tat ");
        final boolean hasExceptionClass =
                text.contains("Exception") || text.contains("Error:");

        return hasVersionHeader && hasStackFrame && hasExceptionClass;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (MenuDoubleTabUtil.shouldIgnoreTap()) {
            return false;
        } else if (conversation == null) {
            return super.onOptionsItemSelected(item);
        }
        final int id = item.getItemId();
        if (id == R.id.encryption_choice_axolotl || id == R.id.encryption_choice_pgp || id == R.id.encryption_choice_none) {
            handleEncryptionSelection(item);
        } else if (id == R.id.action_search) {
            startSearch();
        } else if (id == R.id.action_archive) {
            activity.xmppConnectionService.archiveConversation(conversation);
        } else if (id == R.id.action_leave_muc) {
            leaveMucDialog(conversation);
        } else if (id == R.id.action_contact_details) {
            activity.switchToContactDetails(conversation.getContact());
        } else if (id == R.id.action_muc_details) {
            ConferenceDetailsActivity.open(getActivity(), conversation);
        } else if (id == R.id.action_invite) {
            startActivityForResult(
                    ChooseContactActivity.create(activity, conversation),
                    REQUEST_INVITE_TO_CONVERSATION);
        } else if (id == R.id.action_clear_history) {
            clearHistoryDialog(conversation);
        } else if (id == R.id.action_mute) {
            muteConversationDialog(conversation);
        } else if (id == R.id.action_unmute) {
            unMuteConversation(conversation);
        } else if (id == R.id.action_block || id == R.id.action_unblock) {
            final Activity activity = getActivity();
            if (activity instanceof XmppActivity) {
                BlockContactDialog.show((XmppActivity) activity, conversation);
            }
        } else if (id == R.id.action_audio_call) {
            checkPermissionAndTriggerAudioCall();
        } else if (id == R.id.action_video_call) {
            checkPermissionAndTriggerVideoCall();
        } else if (id == R.id.action_ongoing_call) {
            returnToOngoingCall();
        } else if (id == R.id.action_toggle_pinned) {
            togglePinned();
        } else if (id == R.id.action_message_timer) {
            conversationMessageTimerDialog(conversation);
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }


    private void startSearch() {
        final Intent intent = new Intent(getActivity(), SearchActivity.class);
        intent.putExtra(SearchActivity.EXTRA_CONVERSATION_UUID, conversation.getUuid());
        startActivity(intent);
    }

    private void returnToOngoingCall() {
        final Optional<OngoingRtpSession> ongoingRtpSession =
                activity.xmppConnectionService
                        .getJingleConnectionManager()
                        .getOngoingRtpConnection(conversation.getContact());
        if (ongoingRtpSession.isPresent()) {
            final OngoingRtpSession id = ongoingRtpSession.get();
            final Intent intent = new Intent(getActivity(), RtpSessionActivity.class);
            intent.setAction(Intent.ACTION_VIEW);
            intent.putExtra(
                    RtpSessionActivity.EXTRA_ACCOUNT,
                    id.getAccount().getJid().asBareJid().toEscapedString());
            intent.putExtra(RtpSessionActivity.EXTRA_WITH, id.getWith().toEscapedString());
            if (id instanceof AbstractJingleConnection) {
                intent.putExtra(RtpSessionActivity.EXTRA_SESSION_ID, id.getSessionId());
                startActivity(intent);
            } else if (id instanceof JingleConnectionManager.RtpSessionProposal proposal) {
                if (Media.audioOnly(proposal.media)) {
                    intent.putExtra(
                            RtpSessionActivity.EXTRA_LAST_ACTION,
                            RtpSessionActivity.ACTION_MAKE_VOICE_CALL);
                } else {
                    intent.putExtra(
                            RtpSessionActivity.EXTRA_LAST_ACTION,
                            RtpSessionActivity.ACTION_MAKE_VIDEO_CALL);
                }
                intent.putExtra(RtpSessionActivity.EXTRA_PROPOSED_SESSION_ID, proposal.sessionId);
                startActivity(intent);
            }
        }
    }

    private void togglePinned() {
        final boolean pinned =
                conversation.getBooleanAttribute(Conversation.ATTRIBUTE_PINNED_ON_TOP, false);
        conversation.setAttribute(Conversation.ATTRIBUTE_PINNED_ON_TOP, !pinned);
        activity.xmppConnectionService.updateConversation(conversation);
        activity.invalidateOptionsMenu();
    }

    private void checkPermissionAndTriggerAudioCall() {
        if (hasPermissions(REQUEST_START_AUDIO_CALL, PermissionUtils.rtpPermissions(false))) {
            triggerRtpSession(RtpSessionActivity.ACTION_MAKE_VOICE_CALL);
        }
    }

    private void checkPermissionAndTriggerVideoCall() {
        if (hasPermissions(REQUEST_START_VIDEO_CALL, PermissionUtils.rtpPermissions(true))) {
            triggerRtpSession(RtpSessionActivity.ACTION_MAKE_VIDEO_CALL);
        }
    }

    private void triggerRtpSession(final String action) {
        if (activity.xmppConnectionService.getJingleConnectionManager().isBusy()) {
            Toast.makeText(getActivity(), R.string.only_one_call_at_a_time, Toast.LENGTH_LONG)
                    .show();
            return;
        }
        final Account account = conversation.getAccount();
        if (account.setOption(Account.OPTION_SOFT_DISABLED, false)) {
            activity.xmppConnectionService.updateAccount(account);
        }
        final Contact contact = conversation.getContact();
        if (Config.USE_JINGLE_MESSAGE_INIT && RtpCapability.jmiSupport(contact)) {
            triggerRtpSession(contact.getAccount(), contact.getJid().asBareJid(), action);
        } else {
            final RtpCapability.Capability capability;
            if (action.equals(RtpSessionActivity.ACTION_MAKE_VIDEO_CALL)) {
                capability = RtpCapability.Capability.VIDEO;
            } else {
                capability = RtpCapability.Capability.AUDIO;
            }
            PresenceSelector.selectFullJidForDirectRtpConnection(
                    activity,
                    contact,
                    capability,
                    fullJid -> {
                        triggerRtpSession(contact.getAccount(), fullJid, action);
                    });
        }
    }

    private void triggerRtpSession(final Account account, final Jid with, final String action) {
        CallIntegrationConnectionService.placeCall(activity.xmppConnectionService, account,with,RtpSessionActivity.actionToMedia(action));
    }


    private void handleEncryptionSelection(MenuItem item) {
        if (conversation == null) {
            return;
        }
        final boolean updated;
        final int id = item.getItemId();
        if (id == R.id.encryption_choice_none) {
            updated = conversation.setNextEncryption(Message.ENCRYPTION_NONE);
            item.setChecked(true);
        } else if (id == R.id.encryption_choice_pgp) {
            if (activity.hasPgp()) {
                if (conversation.getAccount().getPgpSignature() != null) {
                    updated = conversation.setNextEncryption(Message.ENCRYPTION_PGP);
                    item.setChecked(true);
                } else {
                    updated = false;
                    activity.announcePgp(
                            conversation.getAccount(),
                            conversation,
                            null,
                            activity.onOpenPGPKeyPublished);
                }
            } else {
                activity.showInstallPgpDialog();
                updated = false;
            }
        } else if (id == R.id.encryption_choice_axolotl) {
            updated = conversation.setNextEncryption(Message.ENCRYPTION_AXOLOTL);
            item.setChecked(true);
        } else {
            updated = conversation.setNextEncryption(Message.ENCRYPTION_NONE);
        }
        if (updated) {
            activity.xmppConnectionService.updateConversation(conversation);
        }
        updateChatMsgHint();
        getActivity().invalidateOptionsMenu();
        activity.refreshUi();
    }

    public void attachFile(final int attachmentChoice) {
        attachFile(attachmentChoice, true);
    }

    public void attachFile(final int attachmentChoice, final boolean updateRecentlyUsed) {
        if (attachmentChoice == ATTACHMENT_CHOICE_RECORD_VOICE) {
            if (!hasPermissions(
                    attachmentChoice,
                                        Manifest.permission.RECORD_AUDIO)) {
                return;
            }
        } else if (attachmentChoice == ATTACHMENT_CHOICE_TAKE_PHOTO
                || attachmentChoice == ATTACHMENT_CHOICE_RECORD_VIDEO) {
            if (!hasPermissions(
                    attachmentChoice,
                                        Manifest.permission.CAMERA)) {
                return;
            }
        } else if (attachmentChoice != ATTACHMENT_CHOICE_LOCATION) {
            if (!hasPermissions(attachmentChoice)) {
                return;
            }
        }
        if (updateRecentlyUsed) {
            storeRecentlyUsedQuickAction(attachmentChoice);
        }
        final int encryption = conversation.getNextEncryption();
        final int mode = conversation.getMode();
        if (encryption == Message.ENCRYPTION_PGP) {
            if (activity.hasPgp()) {
                if (mode == Conversation.MODE_SINGLE
                        && conversation.getContact().getPgpKeyId() != 0) {
                    activity.xmppConnectionService
                            .getPgpEngine()
                            .hasKey(
                                    conversation.getContact(),
                                    new UiCallback<Contact>() {

                                        @Override
                                        public void userInputRequired(
                                                PendingIntent pi) {
                                            startPendingIntent(pi, attachmentChoice);
                                        }

                                        @Override
                                        public void success(Contact contact) {
                                            invokeAttachFileIntent(attachmentChoice);
                                        }

                                        @Override
                                        public void error(int error) {
                                            activity.replaceToast(getString(error));
                                        }
                                    });
                } else if (mode == Conversation.MODE_MULTI
                        && conversation.getMucOptions().pgpKeysInUse()) {
                    if (!conversation.getMucOptions().everybodyHasKeys()) {
                        Toast warning =
                                Toast.makeText(
                                        getActivity(),
                                        R.string.missing_public_keys,
                                        Toast.LENGTH_LONG);
                        warning.setGravity(Gravity.CENTER_VERTICAL, 0, 0);
                        warning.show();
                    }
                    invokeAttachFileIntent(attachmentChoice);
                } else {
                    showNoPGPKeyDialog(
                            false,
                            (dialog, which) -> {
                                conversation.setNextEncryption(Message.ENCRYPTION_NONE);
                                activity.xmppConnectionService.updateConversation(conversation);
                                invokeAttachFileIntent(attachmentChoice);
                            });
                }
            } else {
                activity.showInstallPgpDialog();
            }
        } else {
            invokeAttachFileIntent(attachmentChoice);
        }
    }

    private void storeRecentlyUsedQuickAction(final int attachmentChoice) {
        try {
            activity.getPreferences()
                    .edit()
                    .putString(
                            RECENTLY_USED_QUICK_ACTION,
                            SendButtonAction.of(attachmentChoice).toString())
                    .apply();
        } catch (IllegalArgumentException e) {
            // just do not save
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        final PermissionUtils.PermissionResult permissionResult =
                PermissionUtils.removeOptionalRtpPermissions(permissions, grantResults);
        if (grantResults.length > 0) {
            if (allGranted(permissionResult.grantResults)) {
                switch (requestCode) {
                    case REQUEST_START_DOWNLOAD:
                        if (this.mPendingDownloadableMessage != null) {
                            startDownloadable(this.mPendingDownloadableMessage);
                        }
                        break;
                    case REQUEST_SAVE_BACKUP:
                        if (this.pendingBackupExportMessage != null) {
                            saveBackupToDirectory(this.pendingBackupExportMessage);
                        }
                        break;
                    case REQUEST_ADD_EDITOR_CONTENT:
                        if (this.mPendingEditorContent != null) {
                            attachEditorContentToConversation(this.mPendingEditorContent);
                        }
                        break;
                    case REQUEST_COMMIT_ATTACHMENTS:
                        commitAttachments();
                        break;
                    case REQUEST_START_AUDIO_CALL:
                        triggerRtpSession(RtpSessionActivity.ACTION_MAKE_VOICE_CALL);
                        break;
                    case REQUEST_START_VIDEO_CALL:
                        triggerRtpSession(RtpSessionActivity.ACTION_MAKE_VIDEO_CALL);
                        break;
                    default:
                        attachFile(requestCode);
                        break;
                }
            } else {
                @StringRes int res;
                String firstDenied =
                        getFirstDenied(permissionResult.grantResults, permissionResult.permissions);
                if (Manifest.permission.RECORD_AUDIO.equals(firstDenied)) {
                    res = R.string.no_microphone_permission;
                } else if (Manifest.permission.CAMERA.equals(firstDenied)) {
                    res = R.string.no_camera_permission;
                } else {
                    res = R.string.no_storage_permission;
                }
                Toast.makeText(
                                getActivity(),
                                getString(res, getString(R.string.app_name)),
                                Toast.LENGTH_SHORT)
                        .show();
            }
        }
        if (cameraGranted(grantResults, permissions) || audioGranted(grantResults, permissions)) {
            XmppConnectionService.toggleForegroundService(activity);
        }
    }

    public void startDownloadable(Message message) {
        if (!hasPermissions(REQUEST_START_DOWNLOAD)) {
            this.mPendingDownloadableMessage = message;
            return;
        }
        Transferable transferable = message.getTransferable();
        if (transferable != null) {
            if (transferable instanceof TransferablePlaceholder && message.hasFileOnRemoteHost()) {
                createNewConnection(message);
                return;
            }
            if (!transferable.start()) {
                Toast.makeText(getActivity(), R.string.not_connected_try_again, Toast.LENGTH_SHORT)
                        .show();
            }
        } else if (message.treatAsDownloadable()
                || message.hasFileOnRemoteHost()
                || MessageUtils.unInitiatedButKnownSize(message)) {
            createNewConnection(message);
        }
    }

    private void createNewConnection(final Message message) {
        activity.xmppConnectionService
                .getHttpConnectionManager()
                .createNewDownloadConnection(message, true);
    }

    @SuppressLint("InflateParams")
    protected void clearHistoryDialog(final Conversation conversation) {
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity());
        builder.setTitle(R.string.clear_conversation_history);
        final View dialogView =
                requireActivity().getLayoutInflater().inflate(R.layout.dialog_clear_history, null);
        final CheckBox endConversationCheckBox =
                dialogView.findViewById(R.id.end_conversation_checkbox);
        final View archiveDetails = dialogView.findViewById(R.id.archive_details);
        archiveDetails.setVisibility(
                conversation.getMode() == Conversation.MODE_MULTI ? View.VISIBLE : View.GONE);
        builder.setView(dialogView);
        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.setPositiveButton(
                getString(R.string.confirm),
                (dialog, which) -> {
                    this.activity.xmppConnectionService.clearConversationHistory(conversation);
                    if (endConversationCheckBox.isChecked()) {
                        this.activity.xmppConnectionService.archiveConversation(conversation);
                        this.activity.onConversationArchived(conversation);
                    } else {
                        activity.onConversationsListItemUpdated();
                        refresh();
                    }
                });
        builder.create().show();
    }

    /**
     * Permanently leave a multi-user chat. Unlike {@link #onOptionsItemSelected}'s
     * {@code action_archive} (which only hides the conversation locally while keeping
     * the bookmark so the room can be rejoined), leaving deletes the bookmark and sends
     * unavailable presence, so the user stops being a participant and the room no longer
     * appears in their bookmarks.
     */
    protected void leaveMucDialog(final Conversation conversation) {
        final boolean groupChat = conversation.getMucOptions().isPrivateAndNonAnonymous();
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity());
        builder.setTitle(groupChat ? R.string.action_leave_group : R.string.action_leave_channel);
        builder.setMessage(groupChat ? R.string.leave_group_dialog : R.string.leave_channel_dialog);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(
                R.string.leave,
                (dialog, which) -> {
                    // Broadcast the departure into the room (matching iOS) before leaving,
                    // so other participants see a "left group" system message.
                    activity.xmppConnectionService.sendLeavingGroupMessage(conversation);
                    final Bookmark bookmark = conversation.getBookmark();
                    if (bookmark != null) {
                        bookmark.setConversation(null);
                        activity.xmppConnectionService.deleteBookmark(
                                conversation.getAccount(), bookmark);
                    }
                    activity.xmppConnectionService.archiveConversation(conversation);
                    activity.onConversationArchived(conversation);
                });
        builder.create().show();
    }

    protected void muteConversationDialog(final Conversation conversation) {
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity());
        builder.setTitle(R.string.disable_notifications);
        final int[] durations = getResources().getIntArray(R.array.mute_options_durations);
        final CharSequence[] labels = new CharSequence[durations.length];
        for (int i = 0; i < durations.length; ++i) {
            if (durations[i] == -1) {
                labels[i] = getString(R.string.until_further_notice);
            } else {
                labels[i] = TimeFrameUtils.resolve(activity, 1000L * durations[i]);
            }
        }
        builder.setItems(
                labels,
                (dialog, which) -> {
                    final long till;
                    if (durations[which] == -1) {
                        till = Long.MAX_VALUE;
                    } else {
                        till = System.currentTimeMillis() + (durations[which] * 1000L);
                    }
                    conversation.setMutedTill(till);
                    activity.xmppConnectionService.updateConversation(conversation);
                    activity.onConversationsListItemUpdated();
                    refresh();
                    requireActivity().invalidateOptionsMenu();
                });
        builder.create().show();
    }

    private boolean hasPermissions(int requestCode, List<String> permissions) {
        final List<String> missingPermissions = new ArrayList<>();
        for (String permission : permissions) {
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }
        if (missingPermissions.size() == 0) {
            return true;
        } else {
            requestPermissions(
                    missingPermissions.toArray(new String[0]),
                    requestCode);
            return false;
        }
    }

    private boolean hasPermissions(int requestCode, String... permissions) {
        return hasPermissions(requestCode, ImmutableList.copyOf(permissions));
    }

    public void unMuteConversation(final Conversation conversation) {
        conversation.setMutedTill(0);
        this.activity.xmppConnectionService.updateConversation(conversation);
        this.activity.onConversationsListItemUpdated();
        refresh();
        requireActivity().invalidateOptionsMenu();
    }

    protected void conversationMessageTimerDialog(final Conversation conversation) {
        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(getActivity());
        builder.setTitle(R.string.conversation_message_timer); //Global message timer
        final String[] ctimers = getResources().getStringArray(R.array.timer_options_durations);
        final String[] ctimersStrs = getResources().getStringArray(R.array.timer_options_descriptions);
        builder.setItems(R.array.timer_options_descriptions,
                new android.content.DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(final DialogInterface dialog, final int which) {
                        String timerStr = ctimers[which];
                        int timer;
                        try {
                            timer = Integer.parseInt(timerStr);
                        } catch(NumberFormatException nfe) {
                            timer = Message.TIMER_NONE;
                        }

                        conversation.setTimer(timer);
                        setTimerStatus(ctimersStrs[which],false);
                        activity.xmppConnectionService.updateConversation(conversation);
                        activity.onConversationsListItemUpdated();
                        refresh();
                        getActivity().invalidateOptionsMenu();
                    }
                });
        builder.create().show();
    }

    protected void setTimerStatus(String tstatus, boolean global) {
        String timerStatus = "Disappearing message time set to " + tstatus;
        if (global) {
            timerStatus = "Global disappearing message time set to " + tstatus;
        }

        Message disMessageStatus = Message.createStatusMessage(conversation, timerStatus);
        disMessageStatus.setTime(System.currentTimeMillis());
        disMessageStatus.setEndTime(Long.MAX_VALUE);
        disMessageStatus.setTimer(Message.TIMER_NONE);
        activity.xmppConnectionService.databaseBackend.createMessage(disMessageStatus);
        this.conversation.add(disMessageStatus);
        this.conversation.populateWithMessages(ConversationFragment.this.messageList);
        messageListAdapter.notifyDataSetChanged();
    }

    protected void invokeAttachFileIntent(final int attachmentChoice) {
        Intent intent = new Intent();
        boolean chooser = false;
        switch (attachmentChoice) {
            case ATTACHMENT_CHOICE_CHOOSE_IMAGE:
                intent.setAction(Intent.ACTION_GET_CONTENT);
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                intent.setType("image/*");
                chooser = true;
                break;
            case ATTACHMENT_CHOICE_RECORD_VIDEO:
                intent.setAction(MediaStore.ACTION_VIDEO_CAPTURE);
                break;
            case ATTACHMENT_CHOICE_TAKE_PHOTO:
                final Uri uri = activity.xmppConnectionService.getFileBackend().getTakePhotoUri();
                pendingTakePhotoUri.push(uri);
                intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setAction(MediaStore.ACTION_IMAGE_CAPTURE);
                break;
            case ATTACHMENT_CHOICE_CHOOSE_FILE:
                chooser = true;
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setAction(Intent.ACTION_GET_CONTENT);
                break;
            case ATTACHMENT_CHOICE_RECORD_VOICE:
                intent = new Intent(getActivity(), RecordingActivity.class);
                break;
            case ATTACHMENT_CHOICE_LOCATION:
                intent = GeoHelper.getFetchIntent(activity);
                break;
        }
        final Context context = getActivity();
        if (context == null) {
            return;
        }
        try {
            if (chooser) {
                startActivityForResult(
                        Intent.createChooser(intent, getString(R.string.perform_action_with)),
                        attachmentChoice);
            } else {
                startActivityForResult(intent, attachmentChoice);
            }
        } catch (final ActivityNotFoundException e) {
            Toast.makeText(context, R.string.no_application_found, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        binding.messagesView.post(this::fireReadEvent);
    }

    private void fireReadEvent() {
        if (activity != null && this.conversation != null) {
            String uuid = getLastVisibleMessageUuid();
            if (uuid != null) {
                activity.onConversationRead(this.conversation, uuid);
            }
        }
    }

    private String getLastVisibleMessageUuid() {
        if (binding == null) {
            return null;
        }
        synchronized (this.messageList) {
            int pos = binding.messagesView.getLastVisiblePosition();
            if (pos >= 0) {
                // Only a real message may be reported: status messages (date separators, read
                // markers, chat state, load-more) are ui-only and are not in conversation.messages,
                // so marking read up to one of them matches nothing and marks the whole
                // conversation read instead.
                Message message = null;
                for (int i = pos; i >= 0; --i) {
                    final Message candidate;
                    try {
                        candidate = (Message) binding.messagesView.getItemAtPosition(i);
                    } catch (IndexOutOfBoundsException e) {
                        // should not happen if we synchronize properly. however if that fails we
                        // just gonna try item -1
                        continue;
                    }
                    if (candidate.getType() != Message.TYPE_STATUS) {
                        message = candidate;
                        break;
                    }
                }
                if (message != null) {
                    while (message.next() != null && message.next().wasMergedIntoPrevious()) {
                        message = message.next();
                    }
                    return message.getUuid();
                }
            }
        }
        return null;
    }

    private void openWith(final Message message) {
        if (message.isGeoUri()) {
            GeoHelper.view(getActivity(), message);
        } else {
            final DownloadableFile file =
                    activity.xmppConnectionService.getFileBackend().getFile(message);
            ViewUtil.view(activity, file);
        }
    }

    private void reportMessage(final Message message) {
        BlockContactDialog.show(activity, conversation.getContact(), message.getServerMsgId());
    }

    private void showErrorMessage(final Message message) {
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity());
        builder.setTitle(R.string.error_message);
        final String errorMessage = message.getErrorMessage();
        final String[] errorMessageParts =
                errorMessage == null ? new String[0] : errorMessage.split("\\u001f");
        final String displayError;
        if (errorMessageParts.length == 2) {
            displayError = errorMessageParts[1];
        } else {
            displayError = errorMessage;
        }
        builder.setMessage(displayError);
        builder.setNegativeButton(
                R.string.copy_to_clipboard,
                (dialog, which) -> {
                    activity.copyTextToClipboard(displayError, R.string.error_message);
                    Toast.makeText(
                                    activity,
                                    R.string.error_message_copied_to_clipboard,
                                    Toast.LENGTH_SHORT)
                            .show();
                });
        builder.setPositiveButton(R.string.confirm, null);
        builder.create().show();
    }

    private void deleteFile(final Message message) {
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity());
        builder.setNegativeButton(R.string.cancel, null);
        builder.setTitle(R.string.delete_file_dialog);
        builder.setMessage(R.string.delete_file_dialog_msg);
        builder.setPositiveButton(
                R.string.confirm,
                (dialog, which) -> {
                    if (activity.xmppConnectionService.getFileBackend().deleteFile(message)) {
                        message.setDeleted(true);
                        activity.xmppConnectionService.evictPreview(message.getUuid());
                        activity.xmppConnectionService.updateMessage(message, false);
                        activity.onConversationsListItemUpdated();
                        refresh();
                    }
                });
        builder.create().show();
    }

    private void resendMessage(final Message message) {
        if (message.isFileOrImage()) {
            if (!(message.getConversation() instanceof Conversation)) {
                return;
            }
            final Conversation conversation = (Conversation) message.getConversation();
            final DownloadableFile file =
                    activity.xmppConnectionService.getFileBackend().getFile(message);
            if ((file.exists() && file.canRead()) || message.hasFileOnRemoteHost()) {
                final XmppConnection xmppConnection = conversation.getAccount().getXmppConnection();
                if (!message.hasFileOnRemoteHost()
                        && xmppConnection != null
                        && conversation.getMode() == Conversational.MODE_SINGLE
                        && !xmppConnection
                                .getFeatures()
                                .httpUpload(message.getFileParams().getSize())) {
                    activity.selectPresence(
                            conversation,
                            () -> {
                                message.setCounterpart(conversation.getNextCounterpart());
                                activity.xmppConnectionService.resendFailedMessages(message);
                                new Handler()
                                        .post(
                                                () -> {
                                                    if (binding == null) {
                                                        return;
                                                    }
                                                    int size = messageList.size();
                                                    this.binding.messagesView.setSelection(
                                                            size - 1);
                                                });
                            });
                    return;
                }
            } else if (!Compatibility.hasStoragePermission()) {
                Toast.makeText(activity, R.string.no_storage_permission, Toast.LENGTH_SHORT).show();
                return;
            } else {
                Toast.makeText(activity, R.string.file_deleted, Toast.LENGTH_SHORT).show();
                message.setDeleted(true);
                activity.xmppConnectionService.updateMessage(message, false);
                activity.onConversationsListItemUpdated();
                refresh();
                return;
            }
        }
        activity.xmppConnectionService.resendFailedMessages(message);
        new Handler()
                .post(
                        () -> {
                            if (binding == null) {
                                return;
                            }
                            int size = messageList.size();
                            this.binding.messagesView.setSelection(size - 1);
                        });
    }

    private void cancelTransmission(Message message) {
        Transferable transferable = message.getTransferable();
        if (transferable != null) {
            transferable.cancel();
        } else if (message.getStatus() != Message.STATUS_RECEIVED) {
            activity.xmppConnectionService.markMessage(
                    message, Message.STATUS_SEND_FAILED, Message.ERROR_MESSAGE_CANCELLED);
        }
    }

    private void retryDecryption(Message message) {
        message.setEncryption(Message.ENCRYPTION_PGP);
        activity.onConversationsListItemUpdated();
        refresh();
        conversation.getAccount().getPgpDecryptionService().decrypt(message, false);
    }

    public void privateMessageWith(final Jid counterpart) {
        if (conversation.setOutgoingChatState(Config.DEFAULT_CHAT_STATE)) {
            activity.xmppConnectionService.sendChatState(conversation);
        }
        this.binding.textinput.setText("");
        this.conversation.setNextCounterpart(counterpart);
        updateChatMsgHint();
        updateSendButton();
        updateEditablity();
    }

    private void correctMessage(Message message) {
        while (message.mergeable(message.next())) {
            message = message.next();
        }
        this.conversation.setCorrectingMessage(message);
        final Editable editable = binding.textinput.getText();
        this.conversation.setDraftMessage(editable.toString());
        this.binding.textinput.setText("");
        this.binding.textinput.append(message.getBody());
    }

    private void highlightInConference(String nick) {
        final Editable editable = this.binding.textinput.getText();
        String oldString = editable.toString().trim();
        final int pos = this.binding.textinput.getSelectionStart();
        if (oldString.isEmpty() || pos == 0) {
            editable.insert(0, nick + ": ");
        } else {
            final char before = editable.charAt(pos - 1);
            final char after = editable.length() > pos ? editable.charAt(pos) : '\0';
            if (before == '\n') {
                editable.insert(pos, nick + ": ");
            } else {
                if (pos > 2 && editable.subSequence(pos - 2, pos).toString().equals(": ")) {
                    if (NickValidityChecker.check(
                            conversation,
                            Arrays.asList(
                                    editable.subSequence(0, pos - 2).toString().split(", ")))) {
                        editable.insert(pos - 2, ", " + nick);
                        return;
                    }
                }
                editable.insert(
                        pos,
                        (Character.isWhitespace(before) ? "" : " ")
                                + nick
                                + (Character.isWhitespace(after) ? "" : " "));
                if (Character.isWhitespace(after)) {
                    this.binding.textinput.setSelection(
                            this.binding.textinput.getSelectionStart() + 1);
                }
            }
        }
    }

    @Override
    public void startActivityForResult(Intent intent, int requestCode) {
        final Activity activity = getActivity();
        if (activity instanceof ConversationsActivity) {
            ((ConversationsActivity) activity).clearPendingViewIntent();
        }
        super.startActivityForResult(intent, requestCode);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (conversation != null) {
            outState.putString(STATE_CONVERSATION_UUID, conversation.getUuid());
            outState.putString(STATE_LAST_MESSAGE_UUID, lastMessageUuid);
            final Uri uri = pendingTakePhotoUri.peek();
            if (uri != null) {
                outState.putString(STATE_PHOTO_URI, uri.toString());
            }
            final ScrollState scrollState = getScrollPosition();
            if (scrollState != null) {
                outState.putParcelable(STATE_SCROLL_POSITION, scrollState);
            }
            final ArrayList<Attachment> attachments =
                    mediaPreviewAdapter == null
                            ? new ArrayList<>()
                            : mediaPreviewAdapter.getAttachments();
            if (attachments.size() > 0) {
                outState.putParcelableArrayList(STATE_MEDIA_PREVIEWS, attachments);
            }
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        if (savedInstanceState == null) {
            return;
        }
        String uuid = savedInstanceState.getString(STATE_CONVERSATION_UUID);
        ArrayList<Attachment> attachments =
                savedInstanceState.getParcelableArrayList(STATE_MEDIA_PREVIEWS);
        pendingLastMessageUuid.push(savedInstanceState.getString(STATE_LAST_MESSAGE_UUID, null));
        if (uuid != null) {
            QuickLoader.set(uuid);
            this.pendingConversationsUuid.push(uuid);
            if (attachments != null && attachments.size() > 0) {
                this.pendingMediaPreviews.push(attachments);
            }
            String takePhotoUri = savedInstanceState.getString(STATE_PHOTO_URI);
            if (takePhotoUri != null) {
                pendingTakePhotoUri.push(Uri.parse(takePhotoUri));
            }
            pendingScrollState.push(savedInstanceState.getParcelable(STATE_SCROLL_POSITION));
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (this.reInitRequiredOnStart && this.conversation != null) {
            final Bundle extras = pendingExtras.pop();
            reInit(this.conversation, extras != null);
            if (extras != null) {
                processExtras(extras);
            }
        } else if (conversation == null
                && activity != null
                && activity.xmppConnectionService != null) {
            final String uuid = pendingConversationsUuid.pop();
            if (uuid != null) {
                findAndReInitByUuidOrArchive(uuid);
            }
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        final Activity activity = getActivity();
        messageListAdapter.unregisterListenerInAudioPlayer();
        if (activity == null || !activity.isChangingConfigurations()) {
            hideSoftKeyboard(activity);
            messageListAdapter.stopAudioPlayer();
        }
        if (this.conversation != null) {
            final String msg = this.binding.textinput.getText().toString();
            storeNextMessage(msg);
            updateChatState(this.conversation, msg);
            this.activity.xmppConnectionService.getNotificationService().setOpenConversation(null);
        }
        this.endDisappearingMessagesHandler();
        this.reInitRequiredOnStart = true;
    }

    public void endDisappearingMessagesHandler() {
        if (disappearingHandler != null && disRunnable != null) {
            disappearingHandler.removeCallbacks(disRunnable);
        }
    }

    private void updateChatState(final Conversation conversation, final String msg) {
        ChatState state = msg.length() == 0 ? Config.DEFAULT_CHAT_STATE : ChatState.PAUSED;
        Account.State status = conversation.getAccount().getStatus();
        if (status == Account.State.ONLINE && conversation.setOutgoingChatState(state)) {
            activity.xmppConnectionService.sendChatState(conversation);
        }
    }

    private void saveMessageDraftStopAudioPlayer() {
        final Conversation previousConversation = this.conversation;
        if (this.activity == null || this.binding == null || previousConversation == null) {
            return;
        }
        final String msg = this.binding.textinput.getText().toString();
        storeNextMessage(msg);
        updateChatState(this.conversation, msg);
        messageListAdapter.stopAudioPlayer();
        mediaPreviewAdapter.clearPreviews();
        toggleInputMethod();
    }

    public void reInit(final Conversation conversation, final Bundle extras) {
        QuickLoader.set(conversation.getUuid());
        final boolean changedConversation = this.conversation != conversation;
        if (changedConversation) {
            this.saveMessageDraftStopAudioPlayer();
        }
        this.clearPending();
        if (this.reInit(conversation, extras != null)) {
            if (extras != null) {
                processExtras(extras);
            }
            this.reInitRequiredOnStart = false;
        } else {
            this.reInitRequiredOnStart = true;
            pendingExtras.push(extras);
        }
        resetUnreadMessagesCount();

        this.startDisappearingMessagesHandler();
    }

    public void startDisappearingMessagesHandler() {
        if (disappearingHandler != null && disRunnable != null) {
            disappearingHandler.removeCallbacks(disRunnable);
            disappearingHandler.postDelayed(disRunnable, 1000);
        }
    }

    private void reInit(Conversation conversation) {
        reInit(conversation, false);
    }

    private boolean reInit(final Conversation conversation, final boolean hasExtras) {
        if (conversation == null) {
            return false;
        }
        this.conversation = conversation;
        // once we set the conversation all is good and it will automatically do the right thing in
        // onStart()
        if (this.activity == null || this.binding == null) {
            return false;
        }

        if (!activity.xmppConnectionService.isConversationStillOpen(this.conversation)) {
            activity.onConversationArchived(this.conversation);
            return false;
        }

        stopScrolling();

        if (this.conversation.isRead() && hasExtras) {
            this.conversation.trim();
        }

        setupIme();

        final boolean scrolledToBottomAndNoPending =
                this.scrolledToBottom() && pendingScrollState.peek() == null;

        this.binding.textSendButton.setContentDescription(
                activity.getString(R.string.send_message_to_x, conversation.getName()));
        this.binding.textinput.setKeyboardListener(null);
        final boolean participating =
                conversation.getMode() == Conversational.MODE_SINGLE
                        || conversation.getMucOptions().participating();
        if (participating) {
            this.binding.textinput.setText(this.conversation.getNextMessage());
            this.binding.textinput.setSelection(this.binding.textinput.length());
        } else {
            this.binding.textinput.setText(MessageUtils.EMPTY_STRING);
        }
        this.binding.textinput.setKeyboardListener(this);
        messageListAdapter.updatePreferences();
        refresh(false);
        activity.invalidateOptionsMenu();
        this.conversation.messagesLoaded.set(true);

        if (disappearingHandler == null) {
            disappearingHandler = new Handler();
            disRunnable = new Runnable() {
                @Override
                public void run() {
                    ArrayList<String> removedIds = new ArrayList();
                    boolean updateStatus = false;
                    synchronized (ConversationFragment.this.messageList) {
                        for (int m = ConversationFragment.this.messageList.size() - 1; m >= 0; m--) {
                            Message message = ConversationFragment.this.messageList.get(m);
                            if (message.getTimer() != Message.TIMER_NONE) {
                                if (message.getTimeRemaining() <= 0 && message.getUuid() != null) {
                                    removedIds.add(message.getUuid());
                                } else {
                                    updateStatus = true;
                                }
                            }
                        }
                    }
                    final ConversationsActivity activity = ConversationFragment.this.activity;
                    final Conversation conversation = ConversationFragment.this.conversation;
                    if (removedIds.size() > 0
                            && activity != null
                            && activity.xmppConnectionService != null
                            && conversation != null) {
                        // Drop exactly the expired messages, rather than replacing the list
                        // with a fresh page from the database: a message that arrives while
                        // the delete is in flight would not be in that snapshot, and
                        // reinstating it would clobber it out of the UI until the chat was
                        // reopened.
                        conversation.removeMessages(removedIds);
                        conversation.populateWithMessages(ConversationFragment.this.messageList);
                        messageListAdapter.notifyDataSetChanged();
                        activity.xmppConnectionService.updateConversationUi();
                        final ArrayList<String> expired = new ArrayList<>(removedIds);
                        final DatabaseBackend databaseBackend =
                                activity.xmppConnectionService.databaseBackend;
                        DISAPPEARING_EXECUTOR.execute(
                                () -> {
                                    try {
                                        databaseBackend.removeDisappearingMessages(expired);
                                    } catch (final RuntimeException e) {
                                        Log.e(
                                                Config.LOGTAG,
                                                "unable to purge expired messages",
                                                e);
                                    }
                                });
                    } else if (updateStatus) {
                        messageListAdapter.notifyDataSetChanged();
                    }

                    // and here comes the "trick"
                    disappearingHandler.postDelayed(this, 1000);
                }
            };
            disappearingHandler.postDelayed(disRunnable, 2000);
        }


        if (hasExtras || scrolledToBottomAndNoPending) {
            resetUnreadMessagesCount();
            synchronized (this.messageList) {
                final Message first = conversation.getFirstUnreadMessage();
                final int bottom = Math.max(0, this.messageList.size() - 1);
                final int pos;
                final boolean jumpToBottom;
                if (first == null) {
                    pos = bottom;
                    jumpToBottom = true;
                } else {
                    int i = getIndexOf(first.getUuid(), this.messageList);
                    pos = i < 0 ? bottom : i;
                    jumpToBottom = false;
                }
                setSelection(pos, jumpToBottom);
            }
        }

        this.binding.messagesView.post(this::fireReadEvent);
        activity.xmppConnectionService
                .getNotificationService()
                .setOpenConversation(this.conversation);
        return true;
    }

    private void resetUnreadMessagesCount() {
        lastMessageUuid = null;
        hideUnreadMessagesCount();
    }

    private void hideUnreadMessagesCount() {
        if (this.binding == null) {
            return;
        }
        this.binding.scrollToBottomButton.setEnabled(false);
        this.binding.scrollToBottomButton.hide();
        this.binding.unreadCountCustomView.setVisibility(View.GONE);
    }

    private void setSelection(int pos, boolean jumpToBottom) {
        if (binding == null) {
            return;
        }
        ListViewUtils.setSelection(this.binding.messagesView, pos, jumpToBottom);
        this.binding.messagesView.post(
                () -> {
                    if (binding != null) {
                        ListViewUtils.setSelection(this.binding.messagesView, pos, jumpToBottom);
                    }
                });
        this.binding.messagesView.post(this::fireReadEvent);
    }

    private boolean scrolledToBottom() {
        return this.binding != null && scrolledToBottom(this.binding.messagesView);
    }

    private void processExtras(final Bundle extras) {
        final String downloadUuid = extras.getString(ConversationsActivity.EXTRA_DOWNLOAD_UUID);
        final String text = extras.getString(Intent.EXTRA_TEXT);
        final String nick = extras.getString(ConversationsActivity.EXTRA_NICK);
        final String postInitAction =
                extras.getString(ConversationsActivity.EXTRA_POST_INIT_ACTION);
        final boolean asQuote = extras.getBoolean(ConversationsActivity.EXTRA_AS_QUOTE);
        final boolean pm = extras.getBoolean(ConversationsActivity.EXTRA_IS_PRIVATE_MESSAGE, false);
        final boolean doNotAppend =
                extras.getBoolean(ConversationsActivity.EXTRA_DO_NOT_APPEND, false);
        final String type = extras.getString(ConversationsActivity.EXTRA_TYPE);
        final List<Uri> uris = extractUris(extras);
        if (uris != null && uris.size() > 0) {
            if (uris.size() == 1 && "geo".equals(uris.get(0).getScheme())) {
                mediaPreviewAdapter.addMediaPreviews(
                        Attachment.of(getActivity(), uris.get(0), Attachment.Type.LOCATION));
            } else {
                final List<Uri> cleanedUris = cleanUris(new ArrayList<>(uris));
                mediaPreviewAdapter.addMediaPreviews(
                        Attachment.of(getActivity(), cleanedUris, type));
            }
            toggleInputMethod();
            return;
        }
        if (nick != null) {
            if (pm) {
                Jid jid = conversation.getJid();
                try {
                    Jid next = Jid.of(jid.getLocal(), jid.getDomain(), nick);
                    privateMessageWith(next);
                } catch (final IllegalArgumentException ignored) {
                    // do nothing
                }
            } else {
                final MucOptions mucOptions = conversation.getMucOptions();
                if (mucOptions.participating() || conversation.getNextCounterpart() != null) {
                    highlightInConference(nick);
                }
            }
        } else {
            if (text != null && GeoHelper.GEO_URI.matcher(text).matches()) {
                mediaPreviewAdapter.addMediaPreviews(
                        Attachment.of(getActivity(), Uri.parse(text), Attachment.Type.LOCATION));
                toggleInputMethod();
                return;
            } else if (text != null && asQuote) {
                quoteText(text);
            } else {
                appendText(text, doNotAppend);
            }
        }
        if (ConversationsActivity.POST_ACTION_RECORD_VOICE.equals(postInitAction)) {
            attachFile(ATTACHMENT_CHOICE_RECORD_VOICE, false);
            return;
        }
        final Message message =
                downloadUuid == null ? null : conversation.findMessageWithFileAndUuid(downloadUuid);
        if (message != null) {
            startDownloadable(message);
        }
    }

    private List<Uri> extractUris(final Bundle extras) {
        final List<Uri> uris = extras.getParcelableArrayList(Intent.EXTRA_STREAM);
        if (uris != null) {
            return uris;
        }
        final Uri uri = extras.getParcelable(Intent.EXTRA_STREAM);
        if (uri != null) {
            return Collections.singletonList(uri);
        } else {
            return null;
        }
    }

    private List<Uri> cleanUris(final List<Uri> uris) {
        final Iterator<Uri> iterator = uris.iterator();
        while (iterator.hasNext()) {
            final Uri uri = iterator.next();
            if (FileBackend.dangerousFile(uri)) {
                iterator.remove();
                Toast.makeText(
                                requireActivity(),
                                R.string.security_violation_not_attaching_file,
                                Toast.LENGTH_SHORT)
                        .show();
            }
        }
        return uris;
    }

    private boolean showBlockSubmenu(View view) {
        final Jid jid = conversation.getJid();
        final boolean showReject =
                !conversation.isWithStranger()
                        && conversation
                                .getContact()
                                .getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST);
        PopupMenu popupMenu = new PopupMenu(getActivity(), view);
        popupMenu.inflate(R.menu.block);
        popupMenu.getMenu().findItem(R.id.block_contact).setVisible(jid.getLocal() != null);
        popupMenu.getMenu().findItem(R.id.reject).setVisible(showReject);
        popupMenu.setOnMenuItemClickListener(
                menuItem -> {
                    Blockable blockable;
                    final int id = menuItem.getItemId();
                    if (id == R.id.reject) {
                        activity.xmppConnectionService.stopPresenceUpdatesTo(
                                conversation.getContact());
                        updateSnackBar(conversation);
                        return true;
                    } else if (id == R.id.block_domain) {
                        blockable =
                                conversation
                                        .getAccount()
                                        .getRoster()
                                        .getContact(jid.getDomain());
                    } else {
                        blockable = conversation;
                    }
                    BlockContactDialog.show(activity, blockable);
                    return true;
                });
        popupMenu.show();
        return true;
    }

    private void updateSnackBar(final Conversation conversation) {
        final Account account = conversation.getAccount();
        final XmppConnection connection = account.getXmppConnection();
        final int mode = conversation.getMode();
        final Contact contact = mode == Conversation.MODE_SINGLE ? conversation.getContact() : null;
        if (conversation.getStatus() == Conversation.STATUS_ARCHIVED) {
            return;
        }
        if (account.getStatus() == Account.State.DISABLED) {
            showSnackbar(
                    R.string.this_account_is_disabled,
                    R.string.enable,
                    this.mEnableAccountListener);
        } else if (account.getStatus() == Account.State.LOGGED_OUT) {
            showSnackbar(R.string.this_account_is_logged_out,R.string.log_in,this.mEnableAccountListener);
        } else if (conversation.isBlocked()) {
            showSnackbar(R.string.contact_blocked, R.string.unblock, this.mUnblockClickListener);
        } else if (contact != null
                && !contact.showInRoster()
                && contact.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)) {
            showSnackbar(
                    R.string.contact_added_you,
                    R.string.add_back,
                    this.mAddBackClickListener,
                    this.mLongPressBlockListener);
        } else if (contact != null
                && contact.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)) {
            showSnackbar(
                    R.string.contact_asks_for_presence_subscription,
                    R.string.allow,
                    this.mAllowPresenceSubscription,
                    this.mLongPressBlockListener);
        } else if (mode == Conversation.MODE_MULTI
                && !conversation.getMucOptions().online()
                && account.getStatus() == Account.State.ONLINE) {
            switch (conversation.getMucOptions().getError()) {
                case NICK_IN_USE:
                    showSnackbar(R.string.nick_in_use, R.string.edit, clickToMuc);
                    break;
                case NO_RESPONSE:
                    showSnackbar(R.string.joining_conference, 0, null);
                    break;
                case SERVER_NOT_FOUND:
                    if (conversation.receivedMessagesCount() > 0) {
                        showSnackbar(R.string.remote_server_not_found, R.string.try_again, joinMuc);
                    } else {
                        showSnackbar(R.string.remote_server_not_found, R.string.leave, leaveMuc);
                    }
                    break;
                case REMOTE_SERVER_TIMEOUT:
                    if (conversation.receivedMessagesCount() > 0) {
                        showSnackbar(R.string.remote_server_timeout, R.string.try_again, joinMuc);
                    } else {
                        showSnackbar(R.string.remote_server_timeout, R.string.leave, leaveMuc);
                    }
                    break;
                case PASSWORD_REQUIRED:
                    showSnackbar(
                            R.string.conference_requires_password,
                            R.string.enter_password,
                            enterPassword);
                    break;
                case BANNED:
                    showSnackbar(R.string.conference_banned, R.string.leave, leaveMuc);
                    break;
                case MEMBERS_ONLY:
                    showSnackbar(R.string.conference_members_only, R.string.leave, leaveMuc);
                    break;
                case RESOURCE_CONSTRAINT:
                    showSnackbar(
                            R.string.conference_resource_constraint, R.string.try_again, joinMuc);
                    break;
                case KICKED:
                    showSnackbar(R.string.conference_kicked, R.string.join, joinMuc);
                    break;
                case TECHNICAL_PROBLEMS:
                    showSnackbar(R.string.conference_technical_problems, R.string.try_again, joinMuc);
                    break;
                case UNKNOWN:
                    showSnackbar(R.string.conference_unknown_error, R.string.try_again, joinMuc);
                    break;
                case INVALID_NICK:
                    showSnackbar(R.string.invalid_muc_nick, R.string.edit, clickToMuc);
                    break;
                case SHUTDOWN:
                    showSnackbar(R.string.conference_shutdown, R.string.try_again, joinMuc);
                    break;
                case DESTROYED:
                    showSnackbar(R.string.conference_destroyed, R.string.leave, leaveMuc);
                    break;
                case NON_ANONYMOUS:
                    showSnackbar(
                            R.string.group_chat_will_make_your_jabber_id_public,
                            R.string.join,
                            acceptJoin);
                    break;
                default:
                    hideSnackbar();
                    break;
            }
        } else if (account.hasPendingPgpIntent(conversation)) {
            showSnackbar(R.string.openpgp_messages_found, R.string.decrypt, clickToDecryptListener);
        } else if (connection != null
                && connection.getFeatures().blocking()
                && conversation.countMessages() != 0
                && !conversation.isBlocked()
                && conversation.isWithStranger()) {
        } else {
            hideSnackbar();
        }
    }

    @Override
    public void refresh() {
        if (this.binding == null) {
            return;
        }
        if (this.conversation != null
                && this.activity != null
                && this.activity.xmppConnectionService != null) {
            if (!activity.xmppConnectionService.isConversationStillOpen(this.conversation)) {
                activity.onConversationArchived(this.conversation);
                return;
            }
        }
        this.refresh(true);
    }

    private void refresh(boolean notifyConversationRead) {
        if (binding == null) {
            return;
        }
        synchronized (this.messageList) {
            if (this.conversation != null) {
                conversation.populateWithMessages(this.messageList);
                updateSnackBar(conversation);
                updateStatusMessages();
                if (conversation.getReceivedMessagesCountSinceUuid(lastMessageUuid) != 0) {
                    binding.unreadCountCustomView.setVisibility(View.VISIBLE);
                    binding.unreadCountCustomView.setUnreadCount(
                            conversation.getReceivedMessagesCountSinceUuid(lastMessageUuid));
                }
                this.messageListAdapter.notifyDataSetChanged();
                updateChatMsgHint();
                if (notifyConversationRead && activity != null) {
                    binding.messagesView.post(this::fireReadEvent);
                }
                updateSendButton();
                updateEditablity();
            }
        }
    }

    protected void messageSent() {
        // Before the guard: the flag is fragment state, not view state, and a send that completes
        // after onDestroyView must still release it or the fragment comes back off the back stack
        // refusing every PGP send.
        mSendingPgpMessage.set(false);
        // Both paths below need these, and both can be gone by the time a send completes: the
        // pgp callbacks in particular post back onto the UI thread after an OpenKeychain round
        // trip, long enough for the fragment to be detached or opened on nothing yet.
        final Conversation conversation = this.conversation;
        final ConversationsActivity activity = this.activity;
        if (conversation == null || activity == null) {
            return;
        }
        if (binding == null) {
            // The view is gone but the conversation is not, and onStop() has already stored the
            // composer text as the next message. Leaving it there would put the message that was
            // just sent back in the composer -- still in correction mode -- next time this
            // conversation opens, which invites a duplicate send. Mirror what the branch below
            // does to the composer: clear it, restoring the pre-correction draft if there was one.
            final String restored =
                    conversation.setCorrectingMessage(null) ? conversation.getDraftMessage() : null;
            if (restored != null) {
                conversation.setDraftMessage(null);
            }
            storeNextMessage(restored == null ? "" : restored);
            return;
        }
        this.binding.textinput.setText("");
        if (conversation.setCorrectingMessage(null)) {
            this.binding.textinput.append(conversation.getDraftMessage());
            conversation.setDraftMessage(null);
        }
        storeNextMessage();
        updateChatMsgHint();
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(activity);
        final boolean prefScrollToBottom =
                p.getBoolean(
                        "scroll_to_bottom",
                        activity.getResources().getBoolean(R.bool.scroll_to_bottom));
        if (prefScrollToBottom || scrolledToBottom()) {
            new Handler()
                    .post(
                            () -> {
                                if (binding == null) {
                                    return;
                                }
                                int size = messageList.size();
                                this.binding.messagesView.setSelection(size - 1);
                            });
        }
    }

    private boolean storeNextMessage() {
        return storeNextMessage(this.binding.textinput.getText().toString());
    }

    private boolean storeNextMessage(String msg) {
        final boolean participating =
                conversation.getMode() == Conversational.MODE_SINGLE
                        || conversation.getMucOptions().participating();
        if (this.conversation.getStatus() != Conversation.STATUS_ARCHIVED
                && participating
                && this.conversation.setNextMessage(msg)) {
            this.activity.xmppConnectionService.updateConversation(this.conversation);
            return true;
        }
        return false;
    }

    public void doneSendingPgpMessage() {
        mSendingPgpMessage.set(false);
    }

    public long getMaxHttpUploadSize(Conversation conversation) {
        final XmppConnection connection = conversation.getAccount().getXmppConnection();
        return connection == null ? -1 : connection.getFeatures().getMaxHttpUploadSize();
    }

    private void updateEditablity() {
        if (binding == null) {
            return;
        }
        boolean canWrite =
                this.conversation.getMode() == Conversation.MODE_SINGLE
                        || this.conversation.getMucOptions().participating()
                        || this.conversation.getNextCounterpart() != null;
        this.binding.textinput.setFocusable(canWrite);
        this.binding.textinput.setFocusableInTouchMode(canWrite);
        this.binding.textSendButton.setEnabled(canWrite);
        this.binding.textinput.setCursorVisible(canWrite);
        this.binding.textinput.setEnabled(canWrite);
    }

    public void updateSendButton() {
        if (binding == null) {
            return;
        }
        boolean hasAttachments =
                mediaPreviewAdapter != null && mediaPreviewAdapter.hasAttachments();
        final Conversation c = this.conversation;
        final String text =
                this.binding.textinput == null ? "" : this.binding.textinput.getText().toString();
        final SendButtonAction action;
        if (hasAttachments) {
            action = SendButtonAction.TEXT;
        } else {
            action = SendButtonTool.getAction(getActivity(), c, text);
        }
        this.binding.textSendButton.setTag(action);
        this.binding.textSendButton.setIconResource(SendButtonTool.getSendButtonImageResource(action));
        binding.attachments.setOnClickListener(v -> showAttachFileSheet());
    }

    private void showAttachFileSheet() {
        final android.content.Context ctx = getContext();
        if (ctx == null) return;
        boolean microphoneAvailable = ctx.getPackageManager()
                .hasSystemFeature(android.content.pm.PackageManager.FEATURE_MICROPHONE);
        AttachFileBottomSheet sheet = AttachFileBottomSheet.newInstance(microphoneAvailable);
        sheet.setOnAttachOptionSelectedListener(choice -> attachFile(choice));
        sheet.show(getFragmentManager(), "attach_file");
    }


    protected void updateStatusMessages() {
        DateSeparator.addAll(this.messageList);
        if (showLoadMoreMessages(conversation)) {
            this.messageList.add(0, Message.createLoadMoreMessage(conversation));
        }
        if (conversation.getMode() == Conversation.MODE_SINGLE) {
            ChatState state = conversation.getIncomingChatState();
            if (state == ChatState.COMPOSING) {
                this.messageList.add(
                        Message.createStatusMessage(
                                conversation,
                                getString(R.string.contact_is_typing, conversation.getName())));
            } else if (state == ChatState.PAUSED) {
                this.messageList.add(
                        Message.createStatusMessage(
                                conversation,
                                getString(
                                        R.string.contact_has_stopped_typing,
                                        conversation.getName())));
            } else {
                for (int i = this.messageList.size() - 1; i >= 0; --i) {
                    final Message message = this.messageList.get(i);
                    if (message.getType() != Message.TYPE_STATUS) {
                        if (message.getStatus() == Message.STATUS_RECEIVED) {
                            return;
                        } else {
                            if (message.getStatus() == Message.STATUS_SEND_DISPLAYED) {
                                this.messageList.add(
                                        i + 1,
                                        Message.createStatusMessage(
                                                conversation,
                                                getString(
                                                        R.string.contact_has_read_up_to_this_point,
                                                        conversation.getName())));
                                return;
                            }
                        }
                    }
                }
            }
        } else {
            final MucOptions mucOptions = conversation.getMucOptions();
            final List<MucOptions.User> allUsers = mucOptions.getUsers();
            final Set<ReadByMarker> addedMarkers = new HashSet<>();
            ChatState state = ChatState.COMPOSING;
            List<MucOptions.User> users =
                    conversation.getMucOptions().getUsersWithChatState(state, 5);
            if (users.size() == 0) {
                state = ChatState.PAUSED;
                users = conversation.getMucOptions().getUsersWithChatState(state, 5);
            }
            if (mucOptions.isPrivateAndNonAnonymous()) {
                for (int i = this.messageList.size() - 1; i >= 0; --i) {
                    final Set<ReadByMarker> markersForMessage =
                            messageList.get(i).getReadByMarkers();
                    final List<MucOptions.User> shownMarkers = new ArrayList<>();
                    for (ReadByMarker marker : markersForMessage) {
                        if (!ReadByMarker.contains(marker, addedMarkers)) {
                            addedMarkers.add(
                                    marker); // may be put outside this condition. set should do
                                             // dedup anyway
                            MucOptions.User user = mucOptions.findUser(marker);
                            if (user != null && !users.contains(user)) {
                                shownMarkers.add(user);
                            }
                        }
                    }
                    final ReadByMarker markerForSender = ReadByMarker.from(messageList.get(i));
                    final Message statusMessage;
                    final int size = shownMarkers.size();
                    if (size > 1) {
                        final String body;
                        if (size <= 4) {
                            body =
                                    getString(
                                            R.string.contacts_have_read_up_to_this_point,
                                            UIHelper.concatNames(shownMarkers));
                        } else if (ReadByMarker.allUsersRepresented(
                                allUsers, markersForMessage, markerForSender)) {
                            body = getString(R.string.everyone_has_read_up_to_this_point);
                        } else {
                            body =
                                    getString(
                                            R.string.contacts_and_n_more_have_read_up_to_this_point,
                                            UIHelper.concatNames(shownMarkers, 3),
                                            size - 3);
                        }
                        statusMessage = Message.createStatusMessage(conversation, body);
                        statusMessage.setCounterparts(shownMarkers);
                    } else if (size == 1) {
                        statusMessage =
                                Message.createStatusMessage(
                                        conversation,
                                        getString(
                                                R.string.contact_has_read_up_to_this_point,
                                                UIHelper.getDisplayName(shownMarkers.get(0))));
                        statusMessage.setCounterpart(shownMarkers.get(0).getFullJid());
                        statusMessage.setTrueCounterpart(shownMarkers.get(0).getRealJid());
                    } else {
                        statusMessage = null;
                    }
                    if (statusMessage != null) {
                        this.messageList.add(i + 1, statusMessage);
                    }
                    addedMarkers.add(markerForSender);
                    if (ReadByMarker.allUsersRepresented(allUsers, addedMarkers)) {
                        break;
                    }
                }
            }
            if (users.size() > 0) {
                Message statusMessage;
                if (users.size() == 1) {
                    MucOptions.User user = users.get(0);
                    int id =
                            state == ChatState.COMPOSING
                                    ? R.string.contact_is_typing
                                    : R.string.contact_has_stopped_typing;
                    statusMessage =
                            Message.createStatusMessage(
                                    conversation, getString(id, UIHelper.getDisplayName(user)));
                    statusMessage.setTrueCounterpart(user.getRealJid());
                    statusMessage.setCounterpart(user.getFullJid());
                } else {
                    int id =
                            state == ChatState.COMPOSING
                                    ? R.string.contacts_are_typing
                                    : R.string.contacts_have_stopped_typing;
                    statusMessage =
                            Message.createStatusMessage(
                                    conversation, getString(id, UIHelper.concatNames(users)));
                    statusMessage.setCounterparts(users);
                }
                this.messageList.add(statusMessage);
            }
        }
    }

    private void stopScrolling() {
        if (binding == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        try {
            binding.messagesView.dispatchTouchEvent(cancel);
        } finally {
            cancel.recycle();
        }
    }

    private boolean showLoadMoreMessages(final Conversation c) {
        if (activity == null || activity.xmppConnectionService == null) {
            return false;
        }
        final boolean mam = hasMamSupport(c) && !c.getContact().isBlocked();
        final MessageArchiveService service =
                activity.xmppConnectionService.getMessageArchiveService();
        return mam
                && (c.getLastClearHistory().getTimestamp() != 0
                        || (c.countMessages() == 0
                                && c.messagesLoaded.get()
                                && c.hasMessagesLeftOnServer()
                                && !service.queryInProgress(c)));
    }

    private boolean hasMamSupport(final Conversation c) {
        if (c.getMode() == Conversation.MODE_SINGLE) {
            final XmppConnection connection = c.getAccount().getXmppConnection();
            return connection != null && connection.getFeatures().mam();
        } else {
            return c.getMucOptions().mamSupport();
        }
    }

    protected void showSnackbar(
            final int message, final int action, final OnClickListener clickListener) {
        showSnackbar(message, action, clickListener, null);
    }

    protected void showSnackbar(
            final int message,
            final int action,
            final OnClickListener clickListener,
            final View.OnLongClickListener longClickListener) {
        if (binding == null) {
            return;
        }
        this.binding.snackbar.setVisibility(View.VISIBLE);
        this.binding.snackbar.setOnClickListener(null);
        this.binding.snackbarMessage.setText(message);
        this.binding.snackbarMessage.setOnClickListener(null);
        this.binding.snackbarAction.setVisibility(clickListener == null ? View.GONE : View.VISIBLE);
        if (action != 0) {
            this.binding.snackbarAction.setText(action);
        }
        this.binding.snackbarAction.setOnClickListener(clickListener);
        this.binding.snackbarAction.setOnLongClickListener(longClickListener);
    }

    protected void hideSnackbar() {
        if (binding == null) {
            return;
        }
        this.binding.snackbar.setVisibility(View.GONE);
    }

    protected void sendMessage(Message message) {
        activity.xmppConnectionService.sendMessage(message);
        messageSent();
    }

    protected void sendPgpMessage(final Message message) {
        final XmppConnectionService xmppService = activity.xmppConnectionService;
        final Contact contact = message.getConversation().getContact();
        if (!activity.hasPgp()) {
            activity.showInstallPgpDialog();
            return;
        }
        if (conversation.getAccount().getPgpSignature() == null) {
            activity.announcePgp(
                    conversation.getAccount(), conversation, null, activity.onOpenPGPKeyPublished);
            return;
        }
        if (!mSendingPgpMessage.compareAndSet(false, true)) {
            return;
        }
        if (conversation.getMode() == Conversation.MODE_SINGLE) {
            if (contact.getPgpKeyId() != 0) {
                xmppService
                        .getPgpEngine()
                        .hasKey(
                                contact,
                                new UiCallback<Contact>() {

                                    @Override
                                    public void userInputRequired(
                                            PendingIntent pi) {
                                        pendingPgpMessage.push(message);
                                        startPendingIntent(pi, REQUEST_ENCRYPT_MESSAGE);
                                    }

                                    @Override
                                    public void success(Contact contact) {
                                        encryptTextMessage(message);
                                    }

                                    @Override
                                    public void error(int error) {
                                        activity.runOnUiThread(
                                                () ->
                                                        Toast.makeText(
                                                                        activity,
                                                                        R.string
                                                                                .unable_to_connect_to_keychain,
                                                                        Toast.LENGTH_SHORT)
                                                                .show());
                                        mSendingPgpMessage.set(false);
                                    }
                                });

            } else {
                // Cancelling or dismissing this dialog ends the send, but nothing else clears
                // mSendingPgpMessage, so every later pgp send would be rejected as already in
                // progress. The dismiss runs after the positive button's listener, and
                // messageSent() has already cleared the flag by then, so clearing it here
                // unconditionally is safe.
                final AlertDialog noPgpDialog =
                        showNoPGPKeyDialog(
                                false,
                                (dialog, which) -> {
                                    conversation.setNextEncryption(Message.ENCRYPTION_NONE);
                                    xmppService.updateConversation(conversation);
                                    message.setEncryption(Message.ENCRYPTION_NONE);
                                    xmppService.sendMessage(message);
                                    messageSent();
                                });
                noPgpDialog.setOnDismissListener(dialog -> mSendingPgpMessage.set(false));
            }
        } else {
            if (conversation.getMucOptions().pgpKeysInUse()) {
                if (!conversation.getMucOptions().everybodyHasKeys()) {
                    Toast warning =
                            Toast.makeText(
                                    getActivity(), R.string.missing_public_keys, Toast.LENGTH_LONG);
                    warning.setGravity(Gravity.CENTER_VERTICAL, 0, 0);
                    warning.show();
                }
                encryptTextMessage(message);
            } else {
                // Same stuck-send-state path as the single-conversation dialog above.
                final AlertDialog noPgpDialog =
                        showNoPGPKeyDialog(
                                true,
                                (dialog, which) -> {
                                    conversation.setNextEncryption(Message.ENCRYPTION_NONE);
                                    message.setEncryption(Message.ENCRYPTION_NONE);
                                    xmppService.updateConversation(conversation);
                                    xmppService.sendMessage(message);
                                    messageSent();
                                });
                noPgpDialog.setOnDismissListener(dialog -> mSendingPgpMessage.set(false));
            }
        }
    }

    // Both pgp pending intents are OpenKeychain asking the user to unlock or approve, and both
    // of the steps they interrupt (hasKey, encrypt) continue into encryptTextMessage on success,
    // so that is where a successful round trip resumes. mSendingPgpMessage stays set across the
    // trip -- encryptTextMessage does not test it, and messageSent()/the error callback clear it.
    private void resumePgpMessage() {
        final Message message = pendingPgpMessage.pop();
        if (message == null) {
            mSendingPgpMessage.set(false);
            return;
        }
        encryptTextMessage(message);
    }

    public void encryptTextMessage(Message message) {
        activity.xmppConnectionService
                .getPgpEngine()
                .encrypt(
                        message,
                        new UiCallback<Message>() {

                            @Override
                            public void userInputRequired(PendingIntent pi) {
                                pendingPgpMessage.push(message);
                                startPendingIntent(pi, REQUEST_SEND_MESSAGE);
                            }

                            @Override
                            public void success(Message message) {
                                getActivity().runOnUiThread(() -> messageSent());
                            }

                            @Override
                            public void error(final int error) {
                                getActivity()
                                        .runOnUiThread(
                                                () -> {
                                                    doneSendingPgpMessage();
                                                    Toast.makeText(
                                                                    getActivity(),
                                                                    error == 0
                                                                            ? R.string
                                                                                    .unable_to_connect_to_keychain
                                                                            : error,
                                                                    Toast.LENGTH_SHORT)
                                                            .show();
                                                });
                            }
                        });
    }

    public AlertDialog showNoPGPKeyDialog(final boolean plural, final DialogInterface.OnClickListener listener) {
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity());
        if (plural) {
            builder.setTitle(getString(R.string.no_pgp_keys));
            builder.setMessage(getText(R.string.contacts_have_no_pgp_keys));
        } else {
            builder.setTitle(getString(R.string.no_pgp_key));
            builder.setMessage(getText(R.string.contact_has_no_pgp_key));
        }
        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.setPositiveButton(getString(R.string.send_unencrypted), listener);
        final AlertDialog dialog = builder.create();
        dialog.show();
        return dialog;
    }

    public void appendText(String text, final boolean doNotAppend) {
        if (text == null || binding == null) {
            return;
        }
        final Editable editable = this.binding.textinput.getText();
        String previous = editable == null ? "" : editable.toString();
        if (doNotAppend && !TextUtils.isEmpty(previous)) {
            Toast.makeText(getActivity(), R.string.already_drafting_message, Toast.LENGTH_LONG)
                    .show();
            return;
        }
        if (UIHelper.isLastLineQuote(previous)) {
            text = '\n' + text;
        } else if (previous.length() != 0
                && !Character.isWhitespace(previous.charAt(previous.length() - 1))) {
            text = " " + text;
        }
        this.binding.textinput.append(text);
    }

    @Override
    public boolean onEnterPressed(final boolean isCtrlPressed) {
        if (isCtrlPressed || enterIsSend()) {
            sendMessage();
            return true;
        }
        return false;
    }

    private boolean enterIsSend() {
        final SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(getActivity());
        return p.getBoolean("enter_is_send", getResources().getBoolean(R.bool.enter_is_send));
    }

    public boolean onArrowUpCtrlPressed() {
        final Message lastEditableMessage =
                conversation == null ? null : conversation.getLastEditableMessage();
        if (lastEditableMessage != null) {
            correctMessage(lastEditableMessage);
            return true;
        } else {
            Toast.makeText(getActivity(), R.string.could_not_correct_message, Toast.LENGTH_LONG)
                    .show();
            return false;
        }
    }

    @Override
    public void onTypingStarted() {
        final XmppConnectionService service =
                activity == null ? null : activity.xmppConnectionService;
        if (service == null) {
            return;
        }
        final Account.State status = conversation.getAccount().getStatus();
        if (status == Account.State.ONLINE
                && conversation.setOutgoingChatState(ChatState.COMPOSING)) {
            service.sendChatState(conversation);
        }
        runOnUiThread(this::updateSendButton);
    }

    @Override
    public void onTypingStopped() {
        final XmppConnectionService service =
                activity == null ? null : activity.xmppConnectionService;
        if (service == null) {
            return;
        }
        final Account.State status = conversation.getAccount().getStatus();
        if (status == Account.State.ONLINE && conversation.setOutgoingChatState(ChatState.PAUSED)) {
            service.sendChatState(conversation);
        }
    }

    @Override
    public void onTextDeleted() {
        final XmppConnectionService service =
                activity == null ? null : activity.xmppConnectionService;
        if (service == null) {
            return;
        }
        final Account.State status = conversation.getAccount().getStatus();
        if (status == Account.State.ONLINE
                && conversation.setOutgoingChatState(Config.DEFAULT_CHAT_STATE)) {
            service.sendChatState(conversation);
        }
        if (storeNextMessage()) {
            runOnUiThread(
                    () -> {
                        if (activity == null) {
                            return;
                        }
                        activity.onConversationsListItemUpdated();
                    });
        }
        runOnUiThread(this::updateSendButton);
    }

    @Override
    public void onTextChanged() {
        if (conversation != null && conversation.getCorrectingMessage() != null) {
            runOnUiThread(this::updateSendButton);
        }
    }

    @Override
    public boolean onTabPressed(boolean repeated) {
        if (conversation == null || conversation.getMode() == Conversation.MODE_SINGLE) {
            return false;
        }
        if (repeated) {
            completionIndex++;
        } else {
            lastCompletionLength = 0;
            completionIndex = 0;
            final String content = this.binding.textinput.getText().toString();
            lastCompletionCursor = this.binding.textinput.getSelectionEnd();
            int start =
                    lastCompletionCursor > 0
                            ? content.lastIndexOf(" ", lastCompletionCursor - 1) + 1
                            : 0;
            firstWord = start == 0;
            incomplete = content.substring(start, lastCompletionCursor);
        }
        List<String> completions = new ArrayList<>();
        for (MucOptions.User user : conversation.getMucOptions().getUsers()) {
            String name = user.getName();
            if (name != null && name.startsWith(incomplete)) {
                completions.add(name + (firstWord ? ": " : " "));
            }
        }
        Collections.sort(completions);
        if (completions.size() > completionIndex) {
            String completion = completions.get(completionIndex).substring(incomplete.length());
            this.binding
                    .textinput
                    .getEditableText()
                    .delete(lastCompletionCursor, lastCompletionCursor + lastCompletionLength);
            this.binding.textinput.getEditableText().insert(lastCompletionCursor, completion);
            lastCompletionLength = completion.length();
        } else {
            completionIndex = -1;
            this.binding
                    .textinput
                    .getEditableText()
                    .delete(lastCompletionCursor, lastCompletionCursor + lastCompletionLength);
            lastCompletionLength = 0;
        }
        return true;
    }

    private void startPendingIntent(PendingIntent pendingIntent, int requestCode) {
        try {
            getActivity()
                    .startIntentSenderForResult(
                            pendingIntent.getIntentSender(), requestCode, null, 0, 0, 0, Compatibility.pgpStartIntentSenderOptions());
        } catch (final SendIntentException ignored) {
            // No result will arrive, so nothing else will clear the flag or the retained message.
            if (requestCode == REQUEST_ENCRYPT_MESSAGE || requestCode == REQUEST_SEND_MESSAGE) {
                pendingPgpMessage.clear();
                mSendingPgpMessage.set(false);
            }
        }
    }

    @Override
    public void onBackendConnected() {
        String uuid = pendingConversationsUuid.pop();
        if (uuid != null) {
            if (!findAndReInitByUuidOrArchive(uuid)) {
                return;
            }
        } else {
            if (!activity.xmppConnectionService.isConversationStillOpen(conversation)) {
                clearPending();
                activity.onConversationArchived(conversation);
                return;
            }
        }
        ActivityResult activityResult = postponedActivityResult.pop();
        if (activityResult != null) {
            handleActivityResult(activityResult);
        }
        clearPending();
    }

    private boolean findAndReInitByUuidOrArchive(@NonNull final String uuid) {
        Conversation conversation = activity.xmppConnectionService.findConversationByUuid(uuid);
        if (conversation == null) {
            clearPending();
            activity.onConversationArchived(null);
            return false;
        }
        reInit(conversation);
        ScrollState scrollState = pendingScrollState.pop();
        String lastMessageUuid = pendingLastMessageUuid.pop();
        List<Attachment> attachments = pendingMediaPreviews.pop();
        if (scrollState != null) {
            setScrollPosition(scrollState, lastMessageUuid);
        }
        if (attachments != null && attachments.size() > 0) {
            mediaPreviewAdapter.addMediaPreviews(attachments);
            toggleInputMethod();
        }
        return true;
    }

    private void clearPending() {
        if (postponedActivityResult.clear()) {
            Log.e(Config.LOGTAG, "cleared pending intent with unhandled result left");
            if (pendingTakePhotoUri.clear()) {
                Log.e(Config.LOGTAG, "cleared pending photo uri");
            }
        }
        if (pendingScrollState.clear()) {
            Log.e(Config.LOGTAG, "cleared scroll state");
        }
        if (pendingConversationsUuid.clear()) {
            Log.e(Config.LOGTAG, "cleared pending conversations uuid");
        }
        if (pendingMediaPreviews.clear()) {
            Log.e(Config.LOGTAG, "cleared pending media previews");
        }
    }

    public Conversation getConversation() {
        return conversation;
    }

    @Override
    public void onContactPictureLongClicked(View v, final Message message) {
        final String fingerprint;
        if (message.getEncryption() == Message.ENCRYPTION_PGP
                || message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
            fingerprint = "pgp";
        } else {
            fingerprint = message.getFingerprint();
        }
        final PopupMenu popupMenu = new PopupMenu(getActivity(), v);
        final Contact contact = message.getContact();
        if (message.getStatus() <= Message.STATUS_RECEIVED
                && (contact == null || !contact.isSelf())) {
            if (message.getConversation().getMode() == Conversation.MODE_MULTI) {
                final Jid cp = message.getCounterpart();
                if (cp == null || cp.isBareJid()) {
                    return;
                }
                final Jid tcp = message.getTrueCounterpart();
                final User userByRealJid =
                        tcp != null
                                ? conversation.getMucOptions().findOrCreateUserByRealJid(tcp, cp)
                                : null;
                final User user =
                        userByRealJid != null
                                ? userByRealJid
                                : conversation.getMucOptions().findUserByFullJid(cp);
                popupMenu.inflate(R.menu.muc_details_context);
                final Menu menu = popupMenu.getMenu();
                MucDetailsContextMenuHelper.configureMucDetailsContextMenu(
                        activity, menu, conversation, user);
                popupMenu.setOnMenuItemClickListener(
                        menuItem ->
                                MucDetailsContextMenuHelper.onContextItemSelected(
                                        menuItem, user, activity, fingerprint));
            } else {
                popupMenu.inflate(R.menu.one_on_one_context);
                popupMenu.setOnMenuItemClickListener(
                        item -> {
                            final int id = item.getItemId();
                            if (id == R.id.action_contact_details) {
                                activity.switchToContactDetails(
                                        message.getContact(), fingerprint);
                            }
                            return true;
                        });
            }
        } else {
            popupMenu.inflate(R.menu.account_context);
            popupMenu.setOnMenuItemClickListener(
                    item -> {
                        final XmppActivity activity = this.activity;
                        if (activity == null) {
                            Log.e(Config.LOGTAG, "Unable to perform action. no context provided");
                            return true;
                        }
                        final int id = item.getItemId();
                        if (id == R.id.action_account_details) {
                            activity.switchToAccount(
                                    message.getConversation().getAccount(), fingerprint);
                        }
                        return true;
                    });
        }
        popupMenu.show();
    }

    @Override
    public void onContactPictureClicked(Message message) {
        String fingerprint;
        if (message.getEncryption() == Message.ENCRYPTION_PGP
                || message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
            fingerprint = "pgp";
        } else {
            fingerprint = message.getFingerprint();
        }
        final boolean received = message.getStatus() <= Message.STATUS_RECEIVED;
        if (received) {
            if (message.getConversation() instanceof Conversation
                    && message.getConversation().getMode() == Conversation.MODE_MULTI) {
                Jid tcp = message.getTrueCounterpart();
                Jid user = message.getCounterpart();
                if (user != null && !user.isBareJid()) {
                    final MucOptions mucOptions =
                            ((Conversation) message.getConversation()).getMucOptions();
                    if (mucOptions.participating()
                            || ((Conversation) message.getConversation()).getNextCounterpart()
                                    != null) {
                        if (!mucOptions.isUserInRoom(user)
                                && mucOptions.findUserByRealJid(
                                                tcp == null ? null : tcp.asBareJid())
                                        == null) {
                            Toast.makeText(
                                            getActivity(),
                                            activity.getString(
                                                    R.string.user_has_left_conference,
                                                    user.getResource()),
                                            Toast.LENGTH_SHORT)
                                    .show();
                        }
                        highlightInConference(user.getResource());
                    } else {
                        Toast.makeText(
                                        getActivity(),
                                        R.string.you_are_not_participating,
                                        Toast.LENGTH_SHORT)
                                .show();
                    }
                }
                return;
            } else {
                if (!message.getContact().isSelf()) {
                    activity.switchToContactDetails(message.getContact(), fingerprint);
                    return;
                }
            }
        }
        activity.switchToAccount(message.getConversation().getAccount(), fingerprint);
    }

}
