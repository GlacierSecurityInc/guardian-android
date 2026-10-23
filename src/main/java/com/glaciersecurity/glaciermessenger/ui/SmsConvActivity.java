package com.glaciersecurity.glaciermessenger.ui;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.content.ActivityNotFoundException;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.databinding.SmsConvBinding;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.SmsProfile;
import com.glaciersecurity.glaciermessenger.entities.TwilioConversationEntity;
import com.glaciersecurity.glaciermessenger.entities.TwilioMessageEntity;
import com.glaciersecurity.glaciermessenger.services.ConnectivityReceiver;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.ui.adapter.ConnectionToolbarAdapter;
import com.glaciersecurity.glaciermessenger.ui.adapter.MMSmediaAdapter;
import com.glaciersecurity.glaciermessenger.ui.util.SmsUnavailableDialogHelper;
import com.glaciersecurity.glaciermessenger.ui.util.ViewUtil;
import com.glaciersecurity.glaciermessenger.utils.BitmapUtils;
import com.glaciersecurity.glaciermessenger.utils.ContentUriValidator;
import com.glaciersecurity.glaciermessenger.utils.DateParser;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.utils.LogRedactor;

import android.text.format.DateFormat;
import android.text.util.Linkify;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.utils.NativeContactsUtils;
import com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider;
import com.glaciersecurity.glaciermessenger.utils.PhoneUtils;
import com.twilio.conversations.CallbackListener;
import com.twilio.util.ErrorInfo;
import com.twilio.conversations.Media;
import com.twilio.conversations.Message;
import com.twilio.conversations.ConversationsClient;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.text.ParseException;
import java.nio.charset.StandardCharsets;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static com.glaciersecurity.glaciermessenger.ui.ConversationFragment.ATTACHMENT_CHOICE_CHOOSE_IMAGE;


public class SmsConvActivity extends XmppActivity implements ConversationsManagerListener, Serializable, ToggleInputMethodListener, ConnectivityReceiver.ConnectivityReceiverListener, XmppConnectionService.OnConsoleStatus, XmppConnectionService.OnPhoneUpdate {

    private Account identity;
    private SmsProfile selectedProfile;

    private MessagesAdapter messagesAdapter;
    private SmsConvBinding binding;

    private final DateFormat df = new DateFormat();
    private ConversationsManager mConversationsManager;
    Map<String, String> convContList;
    Map<String, String> cList;
    String cameraPermission[];
    /**
     * The MediaStore row this activity created for a camera capture -- never a URI another app
     * handed us. Keeping the picker's URIs out of this field is what makes the delete in
     * {@link #discardCameraImage()} safe.
     */
    private Uri cameraImageUri = null;

    AlertDialog startCall = null;
    private static final int IMAGEPICK_GALLERY_REQUEST = 300;
    private static final int IMAGE_PICKCAMERA_REQUEST = 400;
    private static final String STATE_CAMERA_IMAGE_URI = "camera_image_uri";
    private static final int CAMERA_REQUEST = 100;
    String uid, myuid, image;
    boolean notify = false;
    boolean isBlocked = false;
    private MMSmediaAdapter mediaPreviewAdapter;
    private ConnectivityReceiver connectivityReceiver;

    private String convSidExtra;
    private String friendlyNameExtra;
    private String proxyNumberExtra;

    private List<TwilioMessageEntity> cachedMessages;

    private final List<PendingMessage> pendingMessages = new ArrayList<>();

    private static final int HISTORY_PAGE_SIZE = 50;
    private boolean isLoadingMoreHistory = false;
    private boolean hasMoreHistory = true;
    private int pendingHistoryAnchorPosition = RecyclerView.NO_POSITION;
    private int pendingHistoryAnchorOffset = 0;


    @Override
    protected void refreshUiReal() {

    }

    private ConnectionToolbarAdapter connectionToolbarAdapter;

    private LinearProgressIndicator twilioProgressIndicator;
    private TextView twilioStatusTextView;
    private ConversationsClient.ConnectionState conversationsClientConnectionState;
    private boolean isTwilioLoading = false;
    private boolean conversationSyncFailed = false;

    @Override
    protected void onBackendConnected() {
        mConversationsManager = xmppConnectionService.getConversationsManager();
        if (binding.consoleError.getVisibility() == View.VISIBLE) {
            binding.consoleError.setVisibility(View.GONE);
        }
        SmsProfile proxyProfile = null;
        if (proxyNumberExtra != null) {
            proxyProfile = xmppConnectionService.getSmsInfo().getSMSProfilefromNumber(proxyNumberExtra);
        }

        if (proxyProfile != null) {
            selectedProfile = proxyProfile;
        } else {
            selectedProfile = xmppConnectionService.getSmsInfo().getSelectedProfile();
        }

        if (selectedProfile == null){
            finish();
            return;
        }
        setToolbarColorForProxy(selectedProfile);
        identity = xmppConnectionService.getFirstAccount();

        clearSmsNotificationIfNeeded();
        clearSmsUnreadState();

        hasMoreHistory = true;
        isLoadingMoreHistory = false;
        pendingHistoryAnchorPosition = RecyclerView.NO_POSITION;
        pendingHistoryAnchorOffset = 0;

        if (mConversationsManager != null) {
            mConversationsManager.setOnConversationsManagerListener(this);
            conversationsClientConnectionState = mConversationsManager.getConnectionState();
            if (mConversationsManager.isConversationsClientNull()) {
                if (convSidExtra != null) {
                    cachedMessages = xmppConnectionService.getSmsInfo().conv_messages.get(convSidExtra);
                    if (messagesAdapter != null) {
                        messagesAdapter.notifyDataSetChanged();
                    }
                }
                xmppConnectionService.initializeConversationsClient(xmppConnectionService.getFirstAccount(), false);
            } else {
                if (convSidExtra != null || friendlyNameExtra != null) {
                    String friendly = friendlyNameExtra;
                    if (convSidExtra == null && friendly != null) {
                        friendly = PhoneUtils.getStripPhoneNumberWithCountryCode(friendly);
                    }
                    mConversationsManager.findOrCreateConversation(convSidExtra, friendly,
                            selectedProfile.getUnformattedNumber());
                }
            }
        } else {
            conversationsClientConnectionState = null;
        }

        TwilioConversationEntity cachedConv = null;
        if (convSidExtra != null) {
            cachedConv = xmppConnectionService.getSmsInfo().getConversationBySid(convSidExtra);
            if (cachedConv != null && friendlyNameExtra == null) {
                friendlyNameExtra = cachedConv.getFriendlyName();
            }
        }

        if (friendlyNameExtra != null) {
            setTitle("SMS with " + NativeContactsUtils.getContactNameIfExists(this, friendlyNameExtra));
        } else if (cachedConv != null) {
            setTitle("SMS with " + NativeContactsUtils.getContactNameIfExists(this, cachedConv.getFriendlyName()));
        }

        updateLoadingIndicator();
    }

    private void clearSmsNotificationIfNeeded() {
        if (xmppConnectionService != null && selectedProfile != null) {
            xmppConnectionService.getNotificationService()
                    .clearSmsNotificationForProfile(selectedProfile.getUnformattedNumber());
        }
    }

    private void clearSmsUnreadState() {
        if (xmppConnectionService != null && convSidExtra != null) {
            xmppConnectionService.getSmsInfo().clearConversationUnread(convSidExtra);
            if (mConversationsManager != null && !mConversationsManager.isConversationsClientNull()) {
                mConversationsManager.markConversationRead(convSidExtra);
            } else {
                String profileNumber = selectedProfile != null ? selectedProfile.getUnformattedNumber() : null;
                xmppConnectionService.updateSmsConversationUnreadCount(profileNumber, convSidExtra, 0);
            }
            xmppConnectionService.updatePhoneUi();
        }
    }

    private void setToolbarColorForProxy(SmsProfile sp) {
        if (xmppConnectionServiceBound && sp != null) {
            xmppConnectionService.getSmsInfo().setSelectedSmsProfile(sp);
            selectedProfile = sp;
            binding.toolbar.setBackgroundColor(sp.getColor());
        }
    }

    private void updateLoadingIndicator() {
        boolean loading = true;
        if (xmppConnectionServiceBound && xmppConnectionService != null) {
            loading = xmppConnectionService.isPhoneSyncInProgress()
                    || mConversationsManager == null
                    || mConversationsManager.isConversationsClientNull();
        }
        if (loading) {
            conversationSyncFailed = false;
        }
        isTwilioLoading = loading;
        updateTwilioStatusBar();
    }

    private void updateTwilioStatusBar() {
        runOnUiThread(() -> {
            if (binding == null || binding.twilioError == null || twilioStatusTextView == null || twilioProgressIndicator == null) {
                return;
            }

            CharSequence statusText = null;
            boolean showProgress = false;

            if (isTwilioLoading) {
                statusText = getString(R.string.twilio_sync_in_progress);
                showProgress = true;
            } else if (conversationSyncFailed) {
                statusText = getString(R.string.twilio_sync_failed);
            } else if (mConversationsManager == null || mConversationsManager.isConversationsClientNull()) {
                statusText = getString(R.string.twilio_connecting);
                showProgress = true;
            } else if (conversationsClientConnectionState != null
                    && conversationsClientConnectionState != ConversationsClient.ConnectionState.CONNECTED) {
                switch (conversationsClientConnectionState) {
                    case CONNECTING:
                        statusText = getString(R.string.twilio_connecting);
                        showProgress = true;
                        break;
                    case DISCONNECTED:
                        statusText = getString(R.string.twilio_disconnected);
                        break;
                    case DENIED, ERROR, FATAL_ERROR:
                        statusText = getString(R.string.twilio_failed);
                        break;
                    default:
                        statusText = getString(R.string.twilio_connecting);
                        showProgress = true;
                        break;
                }
            }

            final boolean show = statusText != null;
            binding.twilioError.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                twilioStatusTextView.setText(statusText);
                twilioStatusTextView.setVisibility(View.VISIBLE);
            } else {
                twilioStatusTextView.setVisibility(View.GONE);
            }
            if (show && showProgress) {
                twilioProgressIndicator.setVisibility(View.VISIBLE);
                twilioProgressIndicator.setIndeterminate(true);
            } else {
                twilioProgressIndicator.setVisibility(View.GONE);
            }
        });
    }

    private void retryConversationsClientConnection() {
        if (!xmppConnectionServiceBound || xmppConnectionService == null) {
            return;
        }
        if (mConversationsManager == null) {
            mConversationsManager = xmppConnectionService.getConversationsManager();
        }
        if (mConversationsManager == null) {
            return;
        }
        boolean shouldRetry = conversationSyncFailed
                || mConversationsManager.isConversationsClientNull()
                || conversationsClientConnectionState == ConversationsClient.ConnectionState.DISCONNECTED
                || conversationsClientConnectionState == ConversationsClient.ConnectionState.DENIED
                || conversationsClientConnectionState == ConversationsClient.ConnectionState.ERROR
                || conversationsClientConnectionState == ConversationsClient.ConnectionState.FATAL_ERROR;

        if (!shouldRetry) {
            return;
        }
        Account firstAccount = xmppConnectionService.getFirstAccount();
        if (firstAccount != null) {
            isTwilioLoading = true;
            conversationSyncFailed = false;
            updateTwilioStatusBar();
            xmppConnectionService.initializeConversationsClient(firstAccount, true);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, connectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED);
        if (connectionToolbarAdapter != null) {
            connectionToolbarAdapter.checkNetworkConnection();
        }
    }
    @Override
    protected void onStop() {
        clearSmsUnreadState();
        super.onStop();
        try {
            unregisterReceiver(connectivityReceiver);
        } catch(Exception ignored){
        }
    }

    @Override
    public void onNetworkConnectionChanged() {
        if (connectionToolbarAdapter != null) {
            connectionToolbarAdapter.checkNetworkConnection();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (cameraImageUri != null) {
            outState.putParcelable(STATE_CAMERA_IMAGE_URI, cameraImageUri);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            // The camera can outlive this activity; without the URI the capture cannot be
            // published, attached or cleaned up, and the pending row would be stranded.
            cameraImageUri = savedInstanceState.getParcelable(STATE_CAMERA_IMAGE_URI);
        }
        this.binding = DataBindingUtil.setContentView(this, R.layout.sms_conv);

        if (getIntent().hasExtra("conv_sid")) {
            convSidExtra = getIntent().getStringExtra("conv_sid");
        }
        if (getIntent().hasExtra("friendly_name")) {
            friendlyNameExtra = getIntent().getStringExtra("friendly_name");
        }
        if (getIntent().hasExtra("proxy_number")) {
            proxyNumberExtra = getIntent().getStringExtra("proxy_number");
        }

        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());
        binding.toolbar.setNavigationOnClickListener(view -> getOnBackPressedDispatcher().onBackPressed());
        binding.toolbar.setOnClickListener(v -> {
            if (getIntent().hasExtra("friendly_name")) {
                String number = PhoneUtils.getStripPhoneNumberWithCountryCode(
                        getIntent().getStringExtra("friendly_name"));
                Intent contactDetails = new Intent(SmsConvActivity.this,
                        ExternalContactDetailsActivity.class);
                contactDetails.putExtra("toNumber", number);
                startActivity(contactDetails);
            }
        });

        getOnBackPressedDispatcher().addCallback(this,
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        Intent intent = new Intent(SmsConvActivity.this, PhoneModuleActivity.class);
                        intent.putExtra(
                                PhoneModuleActivity.PHONE_MOD_INTENT,
                                PhoneModuleActivity.SMS_FRAG
                        );
                        startActivity(intent);
                        finish();
                    }
                }
        );


        twilioProgressIndicator = binding.twilioError.findViewById(R.id.twilio_progress);
        twilioStatusTextView = binding.twilioError.findViewById(R.id.twilio_status);
        binding.twilioError.setOnClickListener(v -> retryConversationsClientConnection());

        cameraPermission = new String[]{Manifest.permission.CAMERA};
        mediaPreviewAdapter = new MMSmediaAdapter(this,(ToggleInputMethodListener) this);
        binding.mediaPreview.setAdapter(mediaPreviewAdapter);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        binding.recyclerGchat.setLayoutManager(layoutManager);
        messagesAdapter = new MessagesAdapter();
        binding.recyclerGchat.setAdapter(messagesAdapter);

        binding.recyclerGchat.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (lm != null) {
                    int lastVisible = lm.findLastCompletelyVisibleItemPosition();
                    if (lastVisible >= messagesAdapter.getItemCount() - 1) {
                        binding.scrollToBottomButton.hide();
                    } else {
                        binding.scrollToBottomButton.show();
                    }
                    if (dy < 0 && !recyclerView.canScrollVertically(-1)) {
                        requestOlderMessages();
                    }
                }
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == RecyclerView.SCROLL_STATE_IDLE && !recyclerView.canScrollVertically(-1)) {
                    requestOlderMessages();
                }
            }
        });

        binding.scrollToBottomButton.setOnClickListener(v ->
                binding.recyclerGchat.smoothScrollToPosition(messagesAdapter.getItemCount() - 1));

        binding.attachbtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showImagePicDialog();
            }
        });
        binding.buttonGchatSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                SmsUnavailableDialogHelper.show(SmsConvActivity.this);
            }
        });

        connectionToolbarAdapter = new ConnectionToolbarAdapter(this, binding.offlineLayout, xmppConnectionService);
        binding.offlineLayout.setOnClickListener(mRefreshNetworkClickListener);
        binding.consoleError.setOnClickListener(v -> {
            if (xmppConnectionServiceBound) {
                xmppConnectionService.checkConsoleStatus();
            }
        });
        connectivityReceiver = new ConnectivityReceiver(this);
        connectionToolbarAdapter.updateOfflineStatusBar();

    }

    private final View.OnClickListener mRefreshNetworkClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            connectionToolbarAdapter.onClick();
        }
    };


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_sms_call, menu);
        final MenuItem menuPhoneCall = menu.findItem(R.id.action_call);
        menuPhoneCall.setOnMenuItemClickListener(menuItem -> {
            AlertDialog.Builder builder = new MaterialAlertDialogBuilder(this);
            builder.setTitle(R.string.start_call);
            builder.setNegativeButton(R.string.cancel, null);
            builder.setPositiveButton(getString(R.string.call),
                    (dialog, which) -> {
                        if (startCall != null) {
                            startCall.dismiss();
                        }
                        String toPhoneNum = PhoneUtils.getStripPhoneNumber(getIntent().getStringExtra("friendly_name"));
                        if (xmppConnectionService != null){
                            if (xmppConnectionService.getSmsInfo().containsProxy(toPhoneNum)){
                                Toast.makeText(SmsConvActivity.this, R.string.no_self_calls, Toast.LENGTH_SHORT).show();
                                return;

                            }
                        }
                        if (selectedProfile == null) {
                            Toast.makeText(SmsConvActivity.this, R.string.no_sms_numbers_assigned, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Intent callActivity = new Intent(SmsConvActivity.this, ExternalCallActivity.class);
                        callActivity.setAction(Config.ACTION_OUTGOING_CALL);
                        callActivity.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                        callActivity.putExtra("toNumber", toPhoneNum);
                        callActivity.putExtra(Config.TO_PROXY, selectedProfile.getUnformattedNumber());

                        startActivity(callActivity);
                    });
            startCall = builder.create();
            startCall.show();

            return true;

        });
        return super.onCreateOptionsMenu(menu);
    }

    public void showImagePicDialog(){
        String options[] = {"Camera", "Gallery"};
        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(SmsConvActivity.this);
        builder.setTitle(R.string.sms_pick_image_from);
        builder.setItems(options, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {

                if (which == 0) {
                    if (!checkCameraPermission()) { // if permission is not given
                        requestCameraPermission(); // request for permission
                    } else {
                        pickFromCamera(); // if already access granted then click
                    }
                } else if (which == 1) {
                    // No permission gate: minSdk is 30, so scoped storage applies throughout and
                    // ACTION_PICK hands back a per-uri read grant for the one image the user chose.
                    pickFromGallery();
                }
            }
        });
        builder.create().show();
    }
    public boolean checkCameraPermission(){
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == (PackageManager.PERMISSION_GRANTED);
    }
    private void requestCameraPermission() {
        requestPermissions(cameraPermission, CAMERA_REQUEST);
    }

    /** Without this the grant is recorded but nothing happens, and the user has to tap again. */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_REQUEST
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            pickFromCamera();
        }
    }
    private void pickFromCamera() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.Images.Media.TITLE, "Temp_pic");
        contentValues.put(MediaStore.Images.Media.DESCRIPTION, "Temp Description");
        // Hidden from the gallery until the capture actually lands.
        contentValues.put(MediaStore.Images.Media.IS_PENDING, 1);
        cameraImageUri = this.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);
        if (cameraImageUri == null) {
            Log.w(Config.LOGTAG, "MediaStore insert returned null; cannot start camera capture");
            Toast.makeText(this, R.string.image_save_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent camerIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        camerIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri);
        camerIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        camerIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(camerIntent, IMAGE_PICKCAMERA_REQUEST);
        } catch (ActivityNotFoundException e) {
            discardCameraImage();
            Toast.makeText(this, R.string.no_camera_app, Toast.LENGTH_SHORT).show();
        }
    }

    /** Drops the placeholder row so a capture that never happened leaves nothing behind. */
    private void discardCameraImage() {
        if (cameraImageUri == null) {
            return;
        }
        try {
            getContentResolver().delete(cameraImageUri, null, null);
        } catch (Exception e) {
            Log.w(Config.LOGTAG, "Failed to delete unused camera MediaStore entry", e);
        }
        cameraImageUri = null;
    }

    private boolean capturedImageIsEmpty() {
        return cameraImageUri == null || mediaStoreEntryIsEmpty(getContentResolver(), cameraImageUri);
    }

    /**
     * Whether a MediaStore row holds no bytes. Some camera apps return RESULT_OK without ever
     * writing to EXTRA_OUTPUT, which is what leaves an empty picture in the gallery.
     *
     * <p>Deliberately asymmetric: only a row we could open and found empty, or one that is gone,
     * counts as empty. Any other failure keeps the row, so a real capture we merely could not read
     * is never deleted. Package-private so the device tier can reach it without an SMS thread.
     */
    static boolean mediaStoreEntryIsEmpty(ContentResolver resolver, Uri uri) {
        try (InputStream in = resolver.openInputStream(uri)) {
            return in == null || in.read() == -1;
        } catch (FileNotFoundException e) {
            return true;
        } catch (Exception e) {
            Log.w(Config.LOGTAG, "Could not verify captured camera image; keeping it", e);
            return false;
        }
    }

    /** Clears IS_PENDING so the captured photo becomes visible to other apps. */
    private void publishCameraImage() {
        if (cameraImageUri == null) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        try {
            getContentResolver().update(cameraImageUri, values, null, null);
        } catch (Exception e) {
            Log.w(Config.LOGTAG, "Failed to publish captured camera image", e);
        }
    }
    private void pickFromGallery() {
        Intent galleryIntent = new Intent(Intent.ACTION_PICK);
        galleryIntent.setType("image/*");
        try {
            startActivityForResult(galleryIntent, IMAGEPICK_GALLERY_REQUEST);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_gallery_app, Toast.LENGTH_SHORT).show();
            attachFile(ATTACHMENT_CHOICE_CHOOSE_IMAGE);
        }
    }

    private void attachFile(final int attachmentChoice) {
        if (attachmentChoice == ATTACHMENT_CHOICE_CHOOSE_IMAGE) {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            try {
                startActivityForResult(intent, attachmentChoice);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, R.string.no_gallery_app, Toast.LENGTH_SHORT).show();
            }
        }
    }
    @Override
    public void showList() {

    }

    @Override
    protected void onResume() {
        super.onResume();
        if (xmppConnectionServiceBound && binding.consoleError.getVisibility() == View.VISIBLE) {
            binding.consoleError.setVisibility(View.GONE);
            xmppConnectionService.checkConsoleStatus();
        }
    }

    @Override
    public void notifyMessages() {
        int lastPosition = messagesAdapter.getItemCount() - 1;
        if (lastPosition >= 0) {
            binding.recyclerGchat.smoothScrollToPosition(lastPosition);
        }

    }

    @Override
    public void onConversationsClientConnectionStateChange(ConversationsClient.ConnectionState state) {
        conversationsClientConnectionState = state;
        if (state == ConversationsClient.ConnectionState.CONNECTED && xmppConnectionService != null) {
            isTwilioLoading = xmppConnectionService.isPhoneSyncInProgress();
            conversationSyncFailed = false;
        }
        updateTwilioStatusBar();
    }


    @Override
    public void onConsoleUp() {
        runOnUiThread(() -> {

            binding.consoleError.setVisibility(View.GONE);
        });
    }

    @Override
    public void onConsoleDown() {
        runOnUiThread(() -> {

            binding.consoleError.setVisibility(View.VISIBLE);
        });
    }


    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (resultCode == RESULT_OK) {
            if (requestCode == IMAGEPICK_GALLERY_REQUEST
                    || requestCode == ATTACHMENT_CHOICE_CHOOSE_IMAGE) {
                attachPickedImage(data);
            }
            if (requestCode == IMAGE_PICKCAMERA_REQUEST && cameraImageUri != null) {
                if (capturedImageIsEmpty()) {
                    discardCameraImage();
                    Toast.makeText(this, R.string.image_save_failed, Toast.LENGTH_SHORT).show();
                } else {
                    publishCameraImage();
                    try {
                        sendImageMessage(cameraImageUri);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        } else if (requestCode == IMAGE_PICKCAMERA_REQUEST) {
            // Cancelled or failed capture: the row we pre-inserted holds no image.
            discardCameraImage();
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
    @Override
    public void toggleInputMethod() {
        boolean hasAttachments = mediaPreviewAdapter.hasAttachments();
        binding.editGchatMessage.setVisibility(hasAttachments ? View.GONE : View.VISIBLE);
        binding.mediaPreview.setVisibility(hasAttachments ? View.VISIBLE : View.GONE);
    }

    private void requestOlderMessages() {
        if (isLoadingMoreHistory || !hasMoreHistory) {
            return;
        }
        if (mConversationsManager == null || mConversationsManager.isConversationsClientNull()) {
            return;
        }
        List<Message> currentMessages = mConversationsManager.getMessagesForCurrentConversation();
        if (currentMessages == null || currentMessages.isEmpty()) {
            return;
        }
        LinearLayoutManager layoutManager = (LinearLayoutManager) binding.recyclerGchat.getLayoutManager();
        if (layoutManager == null) {
            return;
        }
        int firstVisible = layoutManager.findFirstVisibleItemPosition();
        if (firstVisible == RecyclerView.NO_POSITION) {
            return;
        }
        if (firstVisible > 1 && binding.recyclerGchat.canScrollVertically(-1)) {
            return;
        }
        if (binding.recyclerGchat.getChildCount() == 0) {
            return;
        }
        View firstChild = binding.recyclerGchat.getChildAt(0);
        pendingHistoryAnchorOffset = firstChild != null ? firstChild.getTop() : 0;
        pendingHistoryAnchorPosition = firstVisible;
        isLoadingMoreHistory = true;
        mConversationsManager.loadMoreMessages(HISTORY_PAGE_SIZE);
    }

    /**
     * Both pickers are implicit intents, so any installed app can answer them and choose the URI
     * we are handed. Resolving it runs as us, which is CodeQL's
     * java/android/unsafe-content-uri-resolution -- so it goes through the same validator the
     * backup import and media viewer use before anything reads it.
     */
    private void attachPickedImage(@Nullable Intent data) {
        final Uri pickedUri = data == null ? null : data.getData();
        if (!ContentUriValidator.isSafeToResolve(this, pickedUri)) {
            Toast.makeText(this, R.string.security_violation_not_attaching_file, Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        try {
            sendImageMessage(pickedUri);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendImageMessage(Uri uri) throws IOException {
        mediaPreviewAdapter.addMediaPreviews(com.glaciersecurity.glaciermessenger.ui.util.Attachment.of(this, uri, com.glaciersecurity.glaciermessenger.ui.util.Attachment.Type.FILE));
        toggleInputMethod();
    }

    @Override
    public void reloadMessages() {
        cachedMessages = null;
        isLoadingMoreHistory = false;
        pendingHistoryAnchorPosition = RecyclerView.NO_POSITION;
        pendingHistoryAnchorOffset = 0;
        messagesAdapter.notifyDataSetChanged();
        binding.recyclerGchat.scrollToPosition(messagesAdapter.getItemCount()-1);
    }

    @Override
    public void onMoreMessagesLoaded(int newMessageCount, boolean hasMore) {
        runOnUiThread(() -> {
            isLoadingMoreHistory = false;
            hasMoreHistory = hasMore;
            if (newMessageCount > 0 && messagesAdapter != null) {
                messagesAdapter.notifyItemRangeInserted(0, newMessageCount);
                LinearLayoutManager layoutManager = (LinearLayoutManager) binding.recyclerGchat.getLayoutManager();
                if (layoutManager != null && pendingHistoryAnchorPosition != RecyclerView.NO_POSITION) {
                    layoutManager.scrollToPositionWithOffset(
                            pendingHistoryAnchorPosition + newMessageCount,
                            pendingHistoryAnchorOffset);
                }
            }
            pendingHistoryAnchorPosition = RecyclerView.NO_POSITION;
            pendingHistoryAnchorOffset = 0;
        });
    }

    @Override
    public void receivedNewMessage(Message message) {
        if (message != null) {
            if (message.getConversation() != null
                    && convSidExtra != null
                    && convSidExtra.equals(message.getConversation().getSid())) {
                clearSmsUnreadState();
            }
        }
        messagesAdapter.notifyDataSetChanged();
        binding.recyclerGchat.scrollToPosition(messagesAdapter.getItemCount()-1);
    }

    @Override
    public void messageSentCallback(String sid) {
        updatePendingStatus(sid, PendingMessage.Status.SENT);
    }

    @Override
    public void messageSendFailed(String sid) {
        updatePendingStatus(sid, PendingMessage.Status.FAILED);
    }

    private void updatePendingStatus(String sid, PendingMessage.Status status) {
        int messageCount = (mConversationsManager != null && !mConversationsManager.isConversationsClientNull()
                && mConversationsManager.getMessagesForCurrentConversation() != null)
                ? mConversationsManager.getMessagesForCurrentConversation().size() : 0;
        PendingMessage target = null;
        if (sid != null) {
            for (PendingMessage p : pendingMessages) {
                if (sid.equals(p.sid)) {
                    target = p;
                    break;
                }
            }
            if (target == null) {
                for (PendingMessage p : pendingMessages) {
                    if (p.sid == null && p.status == PendingMessage.Status.PENDING) {
                        p.sid = sid;
                        target = p;
                        break;
                    }
                }
            }
        } else {
            for (PendingMessage p : pendingMessages) {
                if (p.status == PendingMessage.Status.PENDING) {
                    target = p;
                    break;
                }
            }
        }
        if (target != null) {
            int index = pendingMessages.indexOf(target);
            if (status == PendingMessage.Status.SENT) {
                pendingMessages.remove(index);
                messagesAdapter.notifyItemRemoved(messageCount + index);
            } else {
                target.status = status;
                messagesAdapter.notifyItemChanged(messageCount + index);
            }
            binding.recyclerGchat.scrollToPosition(messagesAdapter.getItemCount() - 1);
        }
    }

    private void retrySend(PendingMessage pending) {
        pending.status = PendingMessage.Status.PENDING;
        int messageCount = (mConversationsManager != null && !mConversationsManager.isConversationsClientNull()
                && mConversationsManager.getMessagesForCurrentConversation() != null)
                ? mConversationsManager.getMessagesForCurrentConversation().size() : 0;
        messagesAdapter.notifyItemChanged(messageCount + pendingMessages.indexOf(pending));
        if (mConversationsManager != null) {
            mConversationsManager.sendMessage(pending.body);
        }
    }


    private void saveImage(final File sourceFile) {
        new Thread(() -> {
            try {
                final String displayName = sourceFile.getName();
                final ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");

                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/Glacier/Media");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);

                final Uri uri = getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("MediaStore insert returned null URI");

                try (OutputStream out = getContentResolver().openOutputStream(uri);
                     FileInputStream in = new FileInputStream(sourceFile)) {
                    if (out == null) throw new IOException("Could not open output stream");
                    final byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                }

                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(uri, values, null, null);

                runOnUiThread(() -> Toast.makeText(
                        SmsConvActivity.this, R.string.image_saved, Toast.LENGTH_SHORT).show());

            } catch (final IOException e) {
                Log.e(Config.LOGTAG, "Failed to save image to gallery: " + sourceFile.getName(), e);
                runOnUiThread(() -> Toast.makeText(
                        SmsConvActivity.this, R.string.save_failed, Toast.LENGTH_SHORT).show());
            }
        }).start();
    }
    @Override
    public void onPhoneUpdate() {
        updateLoadingIndicator();
    }

    @Override
    public void onCallHistoryUpdate() {

    }

    @Override
    public void onVoicemailUpdate() {

    }

    @Override
    public void onAccessToken() {

    }

    @Override
    public void onConversationClientSync(boolean success) {
        runOnUiThread(() -> {
            conversationSyncFailed = !success;
            if (success && xmppConnectionServiceBound) {
                if (convSidExtra != null && friendlyNameExtra != null) {
                    setTitle("SMS with " + NativeContactsUtils.getContactNameIfExists(this, friendlyNameExtra));
                    mConversationsManager.findOrCreateConversation(convSidExtra, friendlyNameExtra, selectedProfile.getUnformattedNumber());
                } else if (friendlyNameExtra != null) {
                    String friendlyName = PhoneUtils.getStripPhoneNumberWithCountryCode(friendlyNameExtra);
                    setTitle("SMS with " + NativeContactsUtils.getContactNameIfExists(this, friendlyName));
                    mConversationsManager.findOrCreateConversation(null, friendlyName, selectedProfile.getUnformattedNumber());
                }
            }

            updateLoadingIndicator();
        });
    }

    private static class PendingMessage {
        enum Status { PENDING, FAILED, SENT }

        final String body;
        String sid;
        Status status = Status.PENDING;

        PendingMessage(String body) {
            this.body = body;
        }
    }

    class MessagesAdapter extends RecyclerView.Adapter{

        private static final int VIEW_TYPE_MESSAGE_SENT = 1;
        private static final int VIEW_TYPE_MESSAGE_RECEIVED = 2;
        // Every MMS image ends up scaled to this width in renderBitmap, so there is nothing to
        // gain from decoding one at full camera resolution first.
        private static final int RENDERED_IMAGE_WIDTH = 512;

        MessagesAdapter() {

        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent,
                                                          int viewType) {
            View view;
            if (viewType == VIEW_TYPE_MESSAGE_SENT) {
                view = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.sms_chat_me, parent, false);
                return new SentMessageHolder(view);
            } else if (viewType == VIEW_TYPE_MESSAGE_RECEIVED) {
                view = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.sms_chat_other, parent, false);
                return new MessagesAdapter.ReceivedMessageHolder(view);
            }

            return null;
        }
        @Override
        public int getItemViewType(int position) {
            int messageCount = 0;
            if (mConversationsManager != null && !mConversationsManager.isConversationsClientNull()
                    && mConversationsManager.getMessagesForCurrentConversation() != null
                    && !mConversationsManager.getMessagesForCurrentConversation().isEmpty()) {
                messageCount = mConversationsManager.getMessagesForCurrentConversation().size();
            }

            int cachedCount = cachedMessages != null ? cachedMessages.size() : 0;

            if (messageCount == 0 && cachedCount > 0) {
                if (position >= 0 && position < cachedMessages.size()) {
                    TwilioMessageEntity entity = cachedMessages.get(position);
                    if ((identity != null && entity.author.equals(identity.getUsername()))
                            || PhoneUtils.areSameNumber(entity.author, selectedProfile.getUnformattedNumber())) {
                        return VIEW_TYPE_MESSAGE_SENT;
                    } else {
                        return VIEW_TYPE_MESSAGE_RECEIVED;
                    }
                } else {
                    return VIEW_TYPE_MESSAGE_SENT;
                }
            }

            if (position >= 0 && position < messageCount) {
                List<Message> currentMessages = mConversationsManager.getMessagesForCurrentConversation();
                if (currentMessages == null || position >= currentMessages.size()) {
                    return VIEW_TYPE_MESSAGE_SENT;
                }
                Message message = currentMessages.get(position);

                if ((identity != null && message.getAuthor().equals(identity.getUsername()))
                        || PhoneUtils.areSameNumber(message.getAuthor(), selectedProfile.getUnformattedNumber())) {
                    return VIEW_TYPE_MESSAGE_SENT;
                } else {
                    return VIEW_TYPE_MESSAGE_RECEIVED;
                }
            }

            return VIEW_TYPE_MESSAGE_SENT;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int messageCount = (mConversationsManager != null && !mConversationsManager.isConversationsClientNull()
                    && mConversationsManager.getMessagesForCurrentConversation() != null
                    && !mConversationsManager.getMessagesForCurrentConversation().isEmpty())
                    ? mConversationsManager.getMessagesForCurrentConversation().size() : 0;
            int cachedCount = cachedMessages != null ? cachedMessages.size() : 0;
            if (messageCount == 0 && cachedMessages != null && cachedCount > 0) {
                if (position >= 0 && position < cachedMessages.size()) {
                    TwilioMessageEntity message = cachedMessages.get(position);
                    TwilioMessageEntity oldMessage = position == 0 ? message
                            : cachedMessages.get(position - 1);
                    boolean new_one = false;
                    try {
                        Date newDate = DateParser.parse(message.messageDate);
                        Date oldDate = DateParser.parse(oldMessage.messageDate);
                        String new_data = df.format("dd/MM/yyyy", newDate).toString();
                        String old_data = df.format("dd/MM/yyyy", oldDate).toString();
                        if(!new_data.equals(old_data) || 0 == position){
                            new_one = true;
                        }
                        switch (holder.getItemViewType()) {
                            case VIEW_TYPE_MESSAGE_SENT:
                                ((SentMessageHolder) holder).bindOffline(message, new_one);
                                break;
                            case VIEW_TYPE_MESSAGE_RECEIVED:
                                ((ReceivedMessageHolder) holder).bindOffline(message, new_one);
                                break;
                            default:
                                ((SentMessageHolder) holder).bindOffline(message, new_one);
                        }
                    } catch (ParseException e) {
                        switch (holder.getItemViewType()) {
                            case VIEW_TYPE_MESSAGE_SENT:
                                ((SentMessageHolder) holder).bindOffline(message, false);
                                break;
                            case VIEW_TYPE_MESSAGE_RECEIVED:
                                ((ReceivedMessageHolder) holder).bindOffline(message, false);
                                break;
                            default:
                                ((SentMessageHolder) holder).bindOffline(message, false);
                        }
                    }
                    return;
                } else {
                    int pendingIndex = position - cachedCount;
                    if (pendingIndex >= 0 && pendingIndex < pendingMessages.size()) {
                        PendingMessage pending = pendingMessages.get(pendingIndex);
                        ((SentMessageHolder) holder).bindPending(pending);
                    }
                    return;
                }
            }
            if (position >= messageCount) {
                int pendingIndex = position - messageCount;
                if (pendingIndex >= 0 && pendingIndex < pendingMessages.size()) {
                    PendingMessage pending = pendingMessages.get(pendingIndex);
                    ((SentMessageHolder) holder).bindPending(pending);
                }
                return;
            }

            List<Message> currentMessages = mConversationsManager == null
                    ? null : mConversationsManager.getMessagesForCurrentConversation();
            if (currentMessages == null || position < 0 || position >= currentMessages.size()) {
                return;
            }

            Message message = currentMessages.get(position);
            Message old_message = position == 0 ? message : currentMessages.get(position - 1);

            boolean new_one = false;
            String new_data = df.format("dd/MM/yyyy", message.getDateCreatedAsDate()).toString();
            String old_data = df.format("dd/MM/yyyy", old_message.getDateCreatedAsDate()).toString();

            if(!new_data.equals(old_data) || 0 == position){
                new_one = true;
            }
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_MESSAGE_SENT:
                    ((SentMessageHolder) holder).bind(message,new_one);
                    break;
                case VIEW_TYPE_MESSAGE_RECEIVED:
                    ((ReceivedMessageHolder) holder).bind(message,new_one);
                    break;
                default:
                    ((SentMessageHolder) holder).bind(message,new_one);
            }
        }

        @Override
        public int getItemCount() {
            int count = 0;
            if (mConversationsManager != null && !mConversationsManager.isConversationsClientNull()
                    && mConversationsManager.getMessagesForCurrentConversation() != null
                    && !mConversationsManager.getMessagesForCurrentConversation().isEmpty()) {
                count += mConversationsManager.getMessagesForCurrentConversation().size();
            } else if (cachedMessages != null) {
                count += cachedMessages.size();
            }
            count += pendingMessages.size();
            return count;
        }

        class SentMessageHolder extends RecyclerView.ViewHolder implements Runnable{
            TextView messageText, timeText, dateText, statusText;
            ImageView sentImg, statusIcon;
            LinearLayout sentBubble;

            SentMessageHolder(View itemView) {
                super(itemView);
                messageText = (TextView) itemView.findViewById(R.id.text_gchat_message_me);
                timeText = (TextView) itemView.findViewById(R.id.text_gchat_timestamp_me);
                dateText = (TextView) itemView.findViewById(R.id.text_gchat_date_me);
                sentImg = itemView.findViewById(R.id.text_gchat_media_me);
                statusIcon = itemView.findViewById(R.id.image_gchat_status_me);
                statusText = itemView.findViewById(R.id.text_gchat_status_me);
                sentBubble = itemView.findViewById(R.id.layout_gchat_container_me);
            }

            void bind(Message message, boolean new_date) {
                statusText.setVisibility(View.GONE);
                statusIcon.setVisibility(View.VISIBLE);
                sentBubble.setOnClickListener(null);
                sentImg.setTag(message.getSid());
                sentImg.setImageDrawable(null);

                final List<Media> attachedMedia = message.getAttachedMedia();
                if (attachedMedia != null && !attachedMedia.isEmpty()) {
                    loadSmsMedia(message, sentImg, messageText);
                } else {
                    resetSmsMediaView(sentImg);
                    messageText.setVisibility(View.VISIBLE);
                    messageText.setText(message.getBody());
                    Linkify.addLinks(messageText, Linkify.ALL);
                }
                // Format the stored timestamp into a readable String using method.

                timeText.setVisibility(View.VISIBLE);
                timeText.setText(df.format("hh:mm", message.getDateCreatedAsDate()).toString());
                if(new_date) {
                    dateText.setVisibility(View.VISIBLE);
                    dateText.setText(df.format("MMM d", message.getDateCreatedAsDate()).toString());
                } else {
                    dateText.setVisibility(View.GONE);
                }
            }

            void bindOffline(TwilioMessageEntity message, boolean new_date) {
                statusText.setVisibility(View.GONE);
                statusIcon.setVisibility(View.VISIBLE);
                sentImg.setVisibility(View.GONE);
                sentImg.setOnClickListener(null);
                sentImg.setOnLongClickListener(null);
                messageText.setVisibility(View.VISIBLE);
                messageText.setText(message.body);
                Linkify.addLinks(messageText, Linkify.ALL);

                Date msgDate = null;
                try {
                    msgDate = DateParser.parse(message.messageDate);
                } catch (ParseException ignore) {}
                if (msgDate != null) {
                    timeText.setVisibility(View.VISIBLE);
                    timeText.setText(df.format("hh:mm", msgDate).toString());
                    if (new_date) {
                        dateText.setVisibility(View.VISIBLE);
                        dateText.setText(df.format("MMM d", msgDate).toString());
                    } else {
                        dateText.setVisibility(View.GONE);
                    }
                } else {
                    timeText.setVisibility(View.GONE);
                    dateText.setVisibility(View.GONE);
                }
            }

            void bindPending(PendingMessage pending) {
                sentImg.setVisibility(View.GONE);
                sentImg.setOnClickListener(null);
                sentImg.setOnLongClickListener(null);
                dateText.setVisibility(View.GONE);
                timeText.setVisibility(View.GONE);
                messageText.setVisibility(View.VISIBLE);
                messageText.setText(pending.body);
                switch (pending.status) {
                    case PENDING:
                        statusIcon.setVisibility(View.GONE);
                        statusText.setVisibility(View.VISIBLE);
                        statusText.setText(itemView.getContext().getString(R.string.message_pending));
                        sentBubble.setOnClickListener(null);
                        break;
                    case SENT:
                        statusText.setVisibility(View.GONE);
                        statusIcon.setVisibility(View.VISIBLE);
                        sentBubble.setOnClickListener(null);
                        break;
                    case FAILED:
                    default:
                        statusIcon.setVisibility(View.GONE);
                        statusText.setVisibility(View.VISIBLE);
                        statusText.setText(itemView.getContext().getString(R.string.message_failed_tap_to_retry));
                        sentBubble.setOnClickListener(v -> retrySend(pending));
                        break;
                }
            }


            @Override
            public void run() {

            }
        }

        class ReceivedMessageHolder extends RecyclerView.ViewHolder {
            TextView messageText, timeText, nameText,dateText;
            ImageView profileImage;
            ImageView sentImg;

            ReceivedMessageHolder(View itemView) {
                super(itemView);

                messageText = (TextView) itemView.findViewById(R.id.text_gchat_message_other);
                timeText = (TextView) itemView.findViewById(R.id.text_gchat_timestamp_other);
                nameText = (TextView) itemView.findViewById(R.id.text_gchat_user_other);
                dateText = (TextView) itemView.findViewById(R.id.text_gchat_date_other);
                sentImg = itemView.findViewById(R.id.text_gchat_media_other);
            }

            void bind(Message message,boolean new_date) {
                final List<Media> attachedMedia = message.getAttachedMedia();
                final boolean hasMedia = attachedMedia != null && !attachedMedia.isEmpty();
                sentImg.setTag(message.getSid());
                sentImg.setImageDrawable(null);
                if (hasMedia) {
                    loadSmsMedia(message, sentImg, messageText);
                } else {
                    resetSmsMediaView(sentImg);
                    messageText.setVisibility(View.VISIBLE);
                    messageText.setText(message.getBody());
                }
                // Format the stored timestamp into a readable String using method.
                timeText.setText(df.format("hh:mm", message.getDateCreatedAsDate()).toString());
                if(new_date) {
                    dateText.setText(df.format("MMM d", message.getDateCreatedAsDate()).toString());
                    dateText.setVisibility(View.VISIBLE);
                } else {
                    dateText.setVisibility(View.GONE);
                }
                nameText.setText(message.getAuthor());

                // Insert the profile image from the URL into the ImageView.
            }

            void bindOffline(TwilioMessageEntity message, boolean new_date) {
                messageText.setText(message.body);
                sentImg.setVisibility(View.GONE);
                sentImg.setOnClickListener(null);
                sentImg.setOnLongClickListener(null);
                messageText.setVisibility(View.VISIBLE);
                Linkify.addLinks(messageText, Linkify.ALL);
                Date msgDate = null;
                try {
                    msgDate = DateParser.parse(message.messageDate);
                } catch (ParseException ignore) {}
                if (msgDate != null) {
                    timeText.setText(df.format("hh:mm", msgDate).toString());
                    if (new_date) {
                        dateText.setText(df.format("MMM d", msgDate).toString());
                        dateText.setVisibility(View.VISIBLE);
                    } else {
                        dateText.setVisibility(View.GONE);
                    }
                } else {
                    timeText.setText("");
                    dateText.setVisibility(View.GONE);
                }
                nameText.setText(message.author);
            }
        }
        private void fetchAndDownloadMedia(Media media, ImgResponseListener imgResponseListener) {
            fetchAndDownloadMedia(media, imgResponseListener, true);
        }

        private void fetchAndDownloadMedia(Media media, ImgResponseListener imgResponseListener, boolean allowExpiredRefresh) {
            media.getTemporaryContentUrl(new CallbackListener<String>() {
                @Override
                public void onSuccess(String result) {
                    if (result == null) {
                        Log.w(Config.LOGTAG, "Failed to obtain temporary content URL for media sid=" + media.getSid());
                        SmsConvActivity.this.runOnUiThread(() -> imgResponseListener.receivedImgResponse(null));
                        return;
                    }
                    downloadMediaWithRefresh(media, result, imgResponseListener, true, allowExpiredRefresh);
                }

                @Override
                public void onError(ErrorInfo errorInfo) {
                    Log.w(Config.LOGTAG, "Failed to obtain temporary content URL for media sid=" + media.getSid() + ": " + errorInfo.getMessage());
                    SmsConvActivity.this.runOnUiThread(() -> imgResponseListener.receivedImgResponse(null));
                }
            });
        }

        private void downloadMediaWithRefresh(Media media, String mediaURL, ImgResponseListener imgResponseListener, boolean allowRefresh, boolean allowExpiredRefresh) {
            new Thread(() -> {
                final Request request = new Request.Builder().url(mediaURL).get().build();
                try (Response response = OkHttpClientProvider.getOkHttpClient().newCall(request).execute()) {
                    final int responseCode = response.code();
                    if (!response.isSuccessful()) {
                        final String responseText = readErrorBody(response.body());
                        if (isExpiredMediaResponse(responseCode, responseText)) {
                            handleExpiredMediaRefresh(media, imgResponseListener, allowExpiredRefresh);
                            return;
                        }
                        if (allowRefresh) {
                            Log.w(Config.LOGTAG, "Media download failed (" + responseCode + "); re-fetching via media SID " + media.getSid());
                            refetchTemporaryMedia(media, imgResponseListener, allowExpiredRefresh);
                        } else {
                            SmsConvActivity.this.runOnUiThread(() -> imgResponseListener.receivedImgResponse(null));
                        }
                        return;
                    }
                    final ResponseBody body = response.body();
                    if (body == null) {
                        if (allowRefresh) {
                            Log.w(Config.LOGTAG, "Empty media response body; re-fetching via media SID " + media.getSid());
                            refetchTemporaryMedia(media, imgResponseListener, allowExpiredRefresh);
                            return;
                        }
                        SmsConvActivity.this.runOnUiThread(() -> imgResponseListener.receivedImgResponse(null));
                        return;
                    }
                    final Bitmap bMap;
                    try (final BufferedInputStream buf =
                                 new BufferedInputStream(body.byteStream())) {
                        bMap = BitmapUtils.decodeSampledStream(buf, RENDERED_IMAGE_WIDTH);
                    }
                    if (bMap == null) {
                        if (allowRefresh) {
                            Log.w(Config.LOGTAG, "Empty media response; re-fetching via media SID " + media.getSid());
                            refetchTemporaryMedia(media, imgResponseListener, allowExpiredRefresh);
                            return;
                        }
                    }
                    Bitmap finalMap = bMap;
                    SmsConvActivity.this.runOnUiThread(() -> imgResponseListener.receivedImgResponse(finalMap));
                } catch (Exception e) {
                    Log.e(Config.LOGTAG, "Exception " + e.getMessage(), e);
                    if (allowRefresh) {
                        Log.w(Config.LOGTAG, "Retrying media download via media SID " + media.getSid());
                        refetchTemporaryMedia(media, imgResponseListener, allowExpiredRefresh);
                    } else {
                        SmsConvActivity.this.runOnUiThread(() -> imgResponseListener.receivedImgResponse(null));
                    }
                }
            }).start();
        }

        private void refetchTemporaryMedia(Media media, ImgResponseListener imgResponseListener, boolean allowExpiredRefresh) {
            media.getTemporaryContentUrl(new CallbackListener<String>() {
                @Override
                public void onSuccess(String result) {
                    if (result == null) {
                        Log.w(Config.LOGTAG, "Failed to re-fetch temporary content URL for media sid=" + media.getSid());
                        SmsConvActivity.this.runOnUiThread(() -> imgResponseListener.receivedImgResponse(null));
                        return;
                    }
                    downloadMediaWithRefresh(media, result, imgResponseListener, false, allowExpiredRefresh);
                }

                @Override
                public void onError(ErrorInfo errorInfo) {
                    Log.w(Config.LOGTAG, "Failed to re-fetch temporary content URL for media sid=" + media.getSid() + ": " + errorInfo.getMessage());
                    SmsConvActivity.this.runOnUiThread(() -> imgResponseListener.receivedImgResponse(null));
                }
            });
        }

        private void handleExpiredMediaRefresh(Media media, ImgResponseListener imgResponseListener, boolean allowExpiredRefresh) {
            if (!allowExpiredRefresh) {
                SmsConvActivity.this.runOnUiThread(() -> imgResponseListener.receivedImgResponse(null));
                return;
            }
            if (mConversationsManager == null) {
                SmsConvActivity.this.runOnUiThread(() -> imgResponseListener.receivedImgResponse(null));
                return;
            }
            SmsConvActivity.this.runOnUiThread(() -> mConversationsManager.refreshCurrentConversationMessages(
                    HISTORY_PAGE_SIZE,
                    () -> fetchAndDownloadMedia(media, imgResponseListener, false)
            ));
        }

        private boolean isExpiredMediaResponse(int responseCode, @Nullable String responseText) {
            if (responseCode == 404 || responseCode == 410) {
                return true;
            }
            if (responseText == null) {
                return false;
            }
            String lower = responseText.toLowerCase();
            return lower.contains("media expired") || lower.contains("link not found");
        }

        private String readErrorBody(@Nullable ResponseBody responseBody) throws IOException {
            if (responseBody == null) {
                return null;
            }
            try (BufferedInputStream bufferedInputStream = new BufferedInputStream(responseBody.byteStream());
                 java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int totalRead = 0;
                int read;
                while ((read = bufferedInputStream.read(buffer)) != -1) {
                    int toWrite = Math.min(read, 8192 - totalRead);
                    outputStream.write(buffer, 0, toWrite);
                    totalRead += toWrite;
                    if (totalRead >= 8192) {
                        break;
                    }
                }
                byte[] bytes = outputStream.toByteArray();
                return bytes.length > 0 ? new String(bytes, StandardCharsets.UTF_8) : null;
            }
        }

        private void showExpiredMediaPlaceholder(ImageView imageView, TextView messageText, Runnable retryAction) {
            messageText.setVisibility(View.VISIBLE);
            messageText.setText(R.string.sms_media_message);
            imageView.setVisibility(View.VISIBLE);
            imageView.setImageResource(R.drawable.ic_broken_image_24dp);
            imageView.setContentDescription(getString(R.string.sms_media_message));
            imageView.setOnClickListener(v -> retryAction.run());
        }
        private File getSmsMediaFile(File directory, String messageSid, int mediaIndex) {
            return new File(directory, messageSid + "_" + mediaIndex + ".PNG");
        }

        private File getLegacySmsMediaFile(File directory, String messageSid) {
            return new File(directory, messageSid + ".PNG");
        }

        private File resolveSmsMediaFile(File directory, String messageSid, int mediaIndex) {
            // Check primary (private) location first
            File file = getSmsMediaFile(directory, messageSid, mediaIndex);
            if (file.exists()) {
                return file;
            }
            // The index-less legacy name only ever held the first attachment.
            if (mediaIndex == 0) {
                File legacyFile = getLegacySmsMediaFile(directory, messageSid);
                if (legacyFile.exists()) {
                    return legacyFile;
                }
            }
            // Check the legacy PUBLIC directory.
            // Read-only backwards compat — new files are never written here.
            File legacyDirectory = getLegacySmsMediaDirectory();
            if (!legacyDirectory.equals(directory)) {
                File legacyMultiFile = getSmsMediaFile(legacyDirectory, messageSid, mediaIndex);
                if (legacyMultiFile.exists()) {
                    return legacyMultiFile;
                }
                if (mediaIndex == 0) {
                    File legacySingleFile = getLegacySmsMediaFile(legacyDirectory, messageSid);
                    if (legacySingleFile.exists()) {
                        return legacySingleFile;
                    }
                }
            }
            // File not found anywhere — return the private-directory path as the write destination
            return file;
        }
        private void loadSmsMedia(Message message, ImageView imageView, TextView messageText) {
            messageText.setVisibility(View.GONE);
            imageView.setScaleType(ImageView.ScaleType.FIT_XY);
            resetSmsMediaView(imageView);
            final List<Media> attachedMedia = message.getAttachedMedia();
            if (attachedMedia == null || attachedMedia.isEmpty()) {
                showExpiredMediaPlaceholder(imageView, messageText,
                        () -> loadSmsMedia(message, imageView, messageText));
                return;
            }
            loadSmsMediaAtIndex(message, imageView, messageText, attachedMedia, 0);
        }

        /**
         * Renders one attachment of an MMS. A message row has a single image view, so the other
         * attachments are reached through the long-press menu rather than stacked into the bubble.
         */
        private void loadSmsMediaAtIndex(Message message, ImageView imageView, TextView messageText,
                                         List<Media> attachedMedia, int mediaIndex) {
            if (mediaIndex < 0 || mediaIndex >= attachedMedia.size()) {
                return;
            }
            imageView.setTag(R.id.sms_media_index, mediaIndex);
            File directory = getSmsMediaDirectory();
            File file = resolveSmsMediaFile(directory, message.getSid(), mediaIndex);
            if (renderFromFile(file, imageView, messageText)) {
                setupSmsMediaMenu(imageView, messageText, message, attachedMedia, mediaIndex, file);
                return;
            }
            Media media = attachedMedia.get(mediaIndex);
            fetchAndDownloadMedia(media, new ImgResponseListener() {
                @Override
                public void receivedImgResponse(Bitmap bmpImg) {
                    if (!isCurrentSmsMedia(imageView, message, mediaIndex)) {
                        return;
                    }
                    if (bmpImg == null) {
                        showExpiredMediaPlaceholder(imageView, messageText,
                                () -> loadSmsMediaAtIndex(message, imageView, messageText, attachedMedia, mediaIndex));
                        setupSmsMediaMenu(imageView, messageText, message, attachedMedia, mediaIndex, null);
                        return;
                    }
                    File savedFile = saveBitmapToFile(file, bmpImg);
                    setupSmsMediaMenu(imageView, messageText, message, attachedMedia, mediaIndex, savedFile);
                    renderBitmap(imageView, messageText, bmpImg, savedFile);
                }
            });
        }

        /** Guards against a recycled row, and against a download a later pick has superseded. */
        private boolean isCurrentSmsMedia(ImageView imageView, Message message, int mediaIndex) {
            return message.getSid().equals(imageView.getTag())
                    && Integer.valueOf(mediaIndex).equals(imageView.getTag(R.id.sms_media_index));
        }

        private void resetSmsMediaView(ImageView imageView) {
            imageView.setVisibility(View.GONE);
            imageView.setOnClickListener(null);
            imageView.setOnLongClickListener(null);
            imageView.setContentDescription(null);
            imageView.setTag(R.id.sms_media_index, null);
        }

        private File getSmsMediaDirectory() {
            // Prefer app-scoped external storage (not readable by other apps)
            File directory = getAppSpecificSmsMediaDirectory();
            if (directory != null) {
                return directory;
            }
            // External storage unavailable — fall back to internal private storage.
            // Never fall back to the public Pictures directory.
            File internalDirectory = new File(getFilesDir(), "sms_media");
            if (!internalDirectory.exists() && !internalDirectory.mkdirs()) {
                Log.w(Config.LOGTAG, "Failed to create internal SMS media directory at "
                        + internalDirectory.getAbsolutePath());
            }
            return internalDirectory;
        }
        private File getAppSpecificSmsMediaDirectory() {
            File baseDirectory = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (baseDirectory == null) {
                Log.w(Config.LOGTAG, "External files directory unavailable for SMS media.");
                return null;
            }
            File directory = new File(baseDirectory, getString(R.string.app_name));
            if (!directory.exists() && !directory.mkdirs()) {
                Log.w(Config.LOGTAG, "Failed to create SMS image directory at " + directory.getAbsolutePath());
            }
            return directory;
        }

        private File getLegacySmsMediaDirectory() {
            return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), getString(R.string.app_name));
        }

        /** Download plus, when the message carries more than one attachment, a picker for the rest. */
        private void setupSmsMediaMenu(ImageView imageView, TextView messageText, Message message,
                                       List<Media> attachedMedia, int mediaIndex, File file) {
            final int count = attachedMedia.size();
            if (count > 1 && file != null) {
                // Not on the expired placeholder, whose own description says why there is no image.
                imageView.setContentDescription(
                        getString(R.string.sms_media_attachment_position, mediaIndex + 1, count));
            }
            if (file == null && count <= 1) {
                imageView.setOnLongClickListener(null);
                return;
            }
            imageView.setOnLongClickListener(v -> {
                PopupMenu popup = new PopupMenu(SmsConvActivity.this, v);
                if (file != null) {
                    popup.getMenu().add(Menu.NONE, R.id.download_file, Menu.NONE, R.string.download);
                }
                if (count > 1) {
                    for (int i = 0; i < count; i++) {
                        popup.getMenu()
                                .add(Menu.NONE, i, Menu.NONE, getString(R.string.sms_media_attachment_number, i + 1))
                                .setEnabled(i != mediaIndex);
                    }
                }
                popup.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == R.id.download_file) {
                        saveImage(file);
                        return true;
                    }
                    loadSmsMediaAtIndex(message, imageView, messageText, attachedMedia, item.getItemId());
                    return true;
                });
                popup.show();
                return true;
            });
        }

        private boolean renderFromFile(File file, ImageView imageView, TextView messageText) {
            if (!file.exists()) {
                return false;
            }
            Bitmap myBitmap = BitmapUtils.decodeSampledFile(file.getAbsolutePath(), RENDERED_IMAGE_WIDTH);
            if (myBitmap == null || myBitmap.getWidth() <= 0) {
                Log.w(Config.LOGTAG, "Failed to decode SMS media image at " + file.getAbsolutePath());
                return false;
            }
            renderBitmap(imageView, messageText, myBitmap, file);
            return true;
        }

        private void renderBitmap(ImageView imageView, TextView messageText, Bitmap bitmap, File file) {
            int nh = (int) (bitmap.getHeight() * ((double) RENDERED_IMAGE_WIDTH / bitmap.getWidth()));
            Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, RENDERED_IMAGE_WIDTH, nh, true);
            messageText.setText("");
            messageText.setVisibility(View.GONE);
            imageView.setVisibility(View.VISIBLE);
            imageView.setImageBitmap(scaledBitmap);
            imageView.setOnClickListener(v -> ViewUtil.view(SmsConvActivity.this, file, "image/png"));
        }

        private File saveBitmapToFile(File file, Bitmap bitmap) {
            // file always points to private storage (getAppSpecificSmsMediaDirectory
            // or internal getFilesDir) — never to the public Pictures directory.
            if (writeBitmapToFile(file, bitmap)) {
                return file;
            }
            Log.e(Config.LOGTAG, "Failed to save SMS media image — no writable location for "
                    + file.getName());
            return file;
        }
        private boolean writeBitmapToFile(File file, Bitmap bitmap) {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Log.w(Config.LOGTAG, "Failed to create SMS image directory at " + parent.getAbsolutePath());
            }
            try (FileOutputStream fos = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
                return true;
            } catch (IOException e) {
                Log.e(Config.LOGTAG, "Failed to save SMS media image at " + file.getAbsolutePath(), e);
                return false;
            }
        }
    }
}

interface ImgResponseListener {
    void receivedImgResponse(Bitmap bmpImg);
}
