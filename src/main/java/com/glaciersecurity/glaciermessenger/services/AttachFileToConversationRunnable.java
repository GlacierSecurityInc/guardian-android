package com.glaciersecurity.glaciermessenger.services;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;

import androidx.annotation.NonNull;

import com.otaliastudios.transcoder.Transcoder;
import com.otaliastudios.transcoder.TranscoderListener;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.crypto.PgpEngine;
import com.glaciersecurity.glaciermessenger.entities.DownloadableFile;
import com.glaciersecurity.glaciermessenger.entities.Message;
import com.glaciersecurity.glaciermessenger.persistance.FileBackend;
import com.glaciersecurity.glaciermessenger.ui.UiCallback;
import com.glaciersecurity.glaciermessenger.utils.MimeUtils;
import com.glaciersecurity.glaciermessenger.utils.TranscoderStrategies;

public class AttachFileToConversationRunnable implements Runnable, TranscoderListener {

    private final XmppConnectionService mXmppConnectionService;
    private final Message message;
    private final Uri uri;
    private final String type;
    private final UiCallback<Message> callback;
    private final boolean isVideoMessage;
    private final long originalFileSize;
    private int currentProgress = -1;

    AttachFileToConversationRunnable(XmppConnectionService xmppConnectionService, Uri uri, String type, Message message, UiCallback<Message> callback) {
        this.uri = uri;
        this.type = type;
        this.mXmppConnectionService = xmppConnectionService;
        this.message = message;
        this.callback = callback;
        final String mimeType = MimeUtils.guessMimeTypeFromUriAndMime(mXmppConnectionService, uri, type);
        final int autoAcceptFileSize = mXmppConnectionService.getResources().getInteger(R.integer.auto_accept_filesize);
        this.originalFileSize = FileBackend.getFileSize(mXmppConnectionService, uri);
        this.isVideoMessage = (mimeType != null && mimeType.startsWith("video/")) && originalFileSize > autoAcceptFileSize && !"uncompressed".equals(getVideoCompression());
    }

    boolean isVideoMessage() {
        return this.isVideoMessage;
    }

    private void processAsFile() {
        final String path = mXmppConnectionService.getFileBackend().getOriginalPath(uri);
        if (path != null && !FileBackend.isPathBlacklisted(path)) {
            message.setRelativeFilePath(path);
            mXmppConnectionService.getFileBackend().updateFileParams(message);
            if (message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
                mXmppConnectionService.getPgpEngine().encrypt(message, callback);
            } else {
                mXmppConnectionService.sendMessage(message);
                if (callback != null) {
                    callback.success(message);
                }
            }
        } else {
            try {
                mXmppConnectionService.getFileBackend().copyFileToPrivateStorage(message, uri, type);
                mXmppConnectionService.getFileBackend().updateFileParams(message);
                if (message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
                    final PgpEngine pgpEngine = mXmppConnectionService.getPgpEngine();
                    if (pgpEngine != null) {
                        pgpEngine.encrypt(message, callback);
                    } else if (callback != null) {
                        callback.error(R.string.unable_to_connect_to_keychain);
                    }
                } else {
                    mXmppConnectionService.sendMessage(message);
                    if (callback != null) {
                        callback.success(message);
                    }
                }
            } catch (FileBackend.FileCopyException e) {
                if (callback != null) {
                    callback.error(e.getResId());
                }
            }
        }
    }

    private void processAsVideo() throws FileNotFoundException {
        mXmppConnectionService.startOngoingVideoTranscodingForegroundNotification();
        mXmppConnectionService.getFileBackend().setupRelativeFilePath(message, String.format("%s.%s", message.getUuid(), "mp4"));
        final DownloadableFile file = mXmppConnectionService.getFileBackend().getFile(message);
        if (Objects.requireNonNull(file.getParentFile()).mkdirs()) {
        }

        final boolean highQuality = "720".equals(getVideoCompression());

        final Future<Void> future;
        try {
            future = Transcoder.into(file.getAbsolutePath()).
                addDataSource(mXmppConnectionService, uri)
                .setVideoTrackStrategy(highQuality ? TranscoderStrategies.VIDEO_720P : TranscoderStrategies.VIDEO_360P)
                .setAudioTrackStrategy(highQuality ? TranscoderStrategies.AUDIO_HQ : TranscoderStrategies.AUDIO_MQ)
                .setListener(this)
                .transcode();
        } catch (final RuntimeException e) {
            // transcode can already throw if there is an invalid file format or a platform bug
            mXmppConnectionService.stopOngoingVideoTranscodingForegroundNotification();
            processAsFile();
            return;
        }
        try {
            future.get();
        } catch (final InterruptedException e) {
            throw new AssertionError(e);
        } catch (final ExecutionException e) {
            if (e.getCause() instanceof Error) {
                mXmppConnectionService.stopOngoingVideoTranscodingForegroundNotification();
                processAsFile();
            }
        }
    }

    @Override
    public void onTranscodeProgress(double progress) {
        final int p = (int) Math.round(progress * 100);
        if (p > currentProgress) {
            currentProgress = p;
            mXmppConnectionService.getNotificationService().updateFileAddingNotification(p, message);
        }
    }

    @Override
    public void onTranscodeCompleted(int successCode) {
        mXmppConnectionService.stopOngoingVideoTranscodingForegroundNotification();
        final File file = mXmppConnectionService.getFileBackend().getFile(message);
        long convertedFileSize = mXmppConnectionService.getFileBackend().getFile(message).getSize();
        if (originalFileSize != 0 && convertedFileSize >= originalFileSize) {
            if (file.delete()) {
                processAsFile();
                return;
            }
        }
        mXmppConnectionService.getFileBackend().updateFileParams(message);
        if (message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
            mXmppConnectionService.getPgpEngine().encrypt(message, callback);
        } else {
            mXmppConnectionService.sendMessage(message);
            callback.success(message);
        }
    }

    @Override
    public void onTranscodeCanceled() {
        mXmppConnectionService.stopOngoingVideoTranscodingForegroundNotification();
        processAsFile();
    }

    @Override
    public void onTranscodeFailed(@NonNull final Throwable exception) {
        mXmppConnectionService.stopOngoingVideoTranscodingForegroundNotification();
        processAsFile();
    }

    @Override
    public void run() {
        if (this.isVideoMessage()) {
            try {
                processAsVideo();
            } catch (FileNotFoundException e) {
                processAsFile();
            }
        } else {
            processAsFile();
        }
    }

    private String getVideoCompression() {
        return getVideoCompression(mXmppConnectionService);
    }

    public static String getVideoCompression(final Context context) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        return preferences.getString("video_compression", context.getResources().getString(R.string.video_compression));
    }
}
