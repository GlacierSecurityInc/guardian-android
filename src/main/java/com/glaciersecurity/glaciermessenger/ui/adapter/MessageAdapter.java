package com.glaciersecurity.glaciermessenger.ui.adapter;

import static com.glaciersecurity.glaciermessenger.utils.Compatibility.getPreferences;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.ListPopupWindow;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.glaciersecurity.glaciermessenger.http.HttpDownloadConnection;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.JingleFileTransferConnection;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;

import com.glaciersecurity.glaciermessenger.AppSettings;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.FingerprintStatus;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Conversation;
import com.glaciersecurity.glaciermessenger.entities.Conversational;
import com.glaciersecurity.glaciermessenger.databinding.DialogAddReactionBinding;
import com.glaciersecurity.glaciermessenger.entities.DownloadableFile;
import com.glaciersecurity.glaciermessenger.entities.Message;
import com.glaciersecurity.glaciermessenger.entities.Message.FileParams;
import com.glaciersecurity.glaciermessenger.entities.RtpSessionStatus;
import com.glaciersecurity.glaciermessenger.entities.Transferable;
import com.glaciersecurity.glaciermessenger.persistance.FileBackend;
import com.glaciersecurity.glaciermessenger.services.MessageArchiveService;
import com.glaciersecurity.glaciermessenger.services.NotificationService;
import com.glaciersecurity.glaciermessenger.ui.Activities;
import com.glaciersecurity.glaciermessenger.ui.BindingAdapters;
import com.glaciersecurity.glaciermessenger.ui.ConversationFragment;
import com.glaciersecurity.glaciermessenger.ui.XmppActivity;
import com.glaciersecurity.glaciermessenger.ui.service.AudioPlayer;
import com.glaciersecurity.glaciermessenger.ui.text.DividerSpan;
import com.glaciersecurity.glaciermessenger.ui.text.QuoteSpan;
import com.glaciersecurity.glaciermessenger.geo.GeoPoint;
import com.glaciersecurity.glaciermessenger.ui.util.Attachment;
import com.glaciersecurity.glaciermessenger.ui.util.AvatarWorkerTask;
import com.glaciersecurity.glaciermessenger.ui.util.LocationLabelResolver;
import com.glaciersecurity.glaciermessenger.ui.util.LocationMapThumbnailLoader;
import com.glaciersecurity.glaciermessenger.ui.util.MyLinkify;
import com.glaciersecurity.glaciermessenger.ui.util.QuoteHelper;
import com.glaciersecurity.glaciermessenger.ui.util.ShareUtil;
import com.glaciersecurity.glaciermessenger.ui.util.ViewUtil;
import com.glaciersecurity.glaciermessenger.ui.widget.ClickableMovementMethod;
import com.glaciersecurity.glaciermessenger.utils.CryptoHelper;
import com.glaciersecurity.glaciermessenger.utils.Emoticons;
import com.glaciersecurity.glaciermessenger.utils.GeoHelper;
import com.glaciersecurity.glaciermessenger.utils.MessageUtils;
import com.glaciersecurity.glaciermessenger.utils.StylingHelper;
import com.glaciersecurity.glaciermessenger.utils.TimeFrameUtils;
import com.glaciersecurity.glaciermessenger.utils.UIHelper;
import com.glaciersecurity.glaciermessenger.worker.ExportBackupWorker;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.glaciersecurity.glaciermessenger.xmpp.XmppConnection;
import com.glaciersecurity.glaciermessenger.xmpp.mam.MamReference;

import java.net.URI;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageAdapter extends ArrayAdapter<Message> {

    public static final String DATE_SEPARATOR_BODY = "DATE_SEPARATOR";
    private static final int SENT = 0;
    private static final int RECEIVED = 1;
    private static final int STATUS = 2;
    private static final int DATE_SEPARATOR = 3;
    private static final int RTP_SESSION = 4;
    private final XmppActivity activity;
    private final AudioPlayer audioPlayer;
    private List<String> highlightedTerm = null;
    private final DisplayMetrics metrics;
    private OnContactPictureClicked mOnContactPictureClickedListener;
    private OnContactPictureLongClicked mOnContactPictureLongClickedListener;
    private OnMessageActionListener messageActionListener;
    private BubbleDesign bubbleDesign = new BubbleDesign(false, false);
    private final boolean mForceNames;

    private static final float LONG_PRESS_DIM_ALPHA = 0.4f;
    private static final float LONG_PRESS_SCALE = 1.04f;
    private static final long LONG_PRESS_ANIMATION_DURATION = 150L;
    private View highlightedItemView;

    public MessageAdapter(
            final XmppActivity activity, final List<Message> messages, final boolean forceNames) {
        super(activity, 0, messages);
        this.audioPlayer = new AudioPlayer(this);
        this.activity = activity;
        metrics = getContext().getResources().getDisplayMetrics();
        updatePreferences();
        this.mForceNames = forceNames;
    }

    public MessageAdapter(final XmppActivity activity, final List<Message> messages) {
        this(activity, messages, false);
    }

    private static void resetClickListener(View... views) {
        for (View view : views) {
            view.setOnClickListener(null);
        }
    }

    public void flagScreenOn() {
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    public void flagScreenOff() {
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    public void setVolumeControl(final int stream) {
        activity.setVolumeControlStream(stream);
    }

    public void setOnContactPictureClicked(OnContactPictureClicked listener) {
        this.mOnContactPictureClickedListener = listener;
    }

    public Activity getActivity() {
        return activity;
    }

    public void setOnContactPictureLongClicked(OnContactPictureLongClicked listener) {
        this.mOnContactPictureLongClickedListener = listener;
    }

    public void setOnMessageActionListener(OnMessageActionListener listener) {
        this.messageActionListener = listener;
    }

    @Override
    public int getViewTypeCount() {
        return 5;
    }

    private int getItemViewType(Message message) {
        if (message.getType() == Message.TYPE_STATUS) {
            if (DATE_SEPARATOR_BODY.equals(message.getBody())) {
                return DATE_SEPARATOR;
            } else {
                return STATUS;
            }
        } else if (message.getType() == Message.TYPE_RTP_SESSION) {
            return RTP_SESSION;
        } else if (message.getStatus() <= Message.STATUS_RECEIVED) {
            return RECEIVED;
        } else {
            return SENT;
        }
    }

    @Override
    public int getItemViewType(int position) {
        return this.getItemViewType(getItem(position));
    }

    private void displayStatus(
            final ViewHolder viewHolder,
            final Message message,
            final int type,
            final BubbleColor bubbleColor) {
        final int mergedStatus = message.getMergedStatus();
        final boolean error;
        if (viewHolder.indicatorReceived != null) {
            viewHolder.indicatorReceived.setVisibility(View.GONE);
        }
        final Transferable transferable = message.getTransferable();
        final boolean multiReceived =
                message.getConversation().getMode() == Conversation.MODE_MULTI
                        && mergedStatus <= Message.STATUS_RECEIVED;
        final String fileSize;
        if (message.isFileOrImage()
                || transferable != null
                || MessageUtils.unInitiatedButKnownSize(message)) {
            final FileParams params = message.getFileParams();
            fileSize = params.size != null ? UIHelper.filesizeToString(params.size) : null;
            if (message.getStatus() == Message.STATUS_SEND_FAILED
                    || (transferable != null
                            && (transferable.getStatus() == Transferable.STATUS_FAILED
                                    || transferable.getStatus()
                                            == Transferable.STATUS_CANCELLED))) {
                error = true;
            } else {
                error = message.getStatus() == Message.STATUS_SEND_FAILED;
            }
        } else {
            fileSize = null;
            error = message.getStatus() == Message.STATUS_SEND_FAILED;
        }
        if (type == SENT) {
            final @DrawableRes Integer receivedIndicator =
                    getMessageStatusAsDrawable(message, mergedStatus);
            if (receivedIndicator == null) {
                if (viewHolder.indicatorReceived != null) {
                    viewHolder.indicatorReceived.setVisibility(View.INVISIBLE);
                }
            } else {
                if (viewHolder.indicatorReceived != null) {
                    viewHolder.indicatorReceived.setImageResource(receivedIndicator);
                    if (mergedStatus == Message.STATUS_SEND_FAILED) {
                        setImageTintError(viewHolder.indicatorReceived);
                    } else {
                        setImageTint(viewHolder.indicatorReceived, bubbleColor);
                    }
                    viewHolder.indicatorReceived.setVisibility(View.VISIBLE);
                }
            }
        }
        final var additionalStatusInfo = getAdditionalStatusInfo(message, mergedStatus);

        if (error && type == SENT) {
            viewHolder.time.setTextColor(
                    MaterialColors.getColor(
                            viewHolder.time, android.R.attr.colorError));
        } else {
            setTextColor(viewHolder.time, bubbleColor);
        }
        if (message.getEncryption() == Message.ENCRYPTION_NONE) {
            viewHolder.indicator.setVisibility(View.GONE);
        } else {
            boolean verified = false;
            if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL) {
                final FingerprintStatus status =
                        message.getConversation()
                                .getAccount()
                                .getAxolotlService()
                                .getFingerprintTrust(message.getFingerprint());
                if (status != null && status.isVerified()) {
                    verified = true;
                }
            }
            if (verified) {
                viewHolder.indicator.setImageResource(R.drawable.ic_verified_user_24dp);
            } else {
                viewHolder.indicator.setImageResource(R.drawable.ic_lock_24dp);
            }
            if (error && type == SENT) {
                setImageTintError(viewHolder.indicator);
            } else {
                setImageTint(viewHolder.indicator, bubbleColor);
            }
            viewHolder.indicator.setVisibility(View.VISIBLE);
        }

        if (viewHolder.edit_indicator != null) {
            if (message.edited()) {
                viewHolder.edit_indicator.setVisibility(View.VISIBLE);
                if (error && type == SENT) {
                    setImageTintError(viewHolder.edit_indicator);
                } else {
                    setImageTint(viewHolder.edit_indicator, bubbleColor);
                }
            } else {
                viewHolder.edit_indicator.setVisibility(View.GONE);
            }
        }

        String formattedTime =
                UIHelper.readableTimeDifferenceFull(getContext(), message.getMergedTimeSent());
        if (message.getTimer() != Message.TIMER_NONE) {
            formattedTime = formattedTime + ", " + UIHelper.readableTimeRemaining(
                    message.getTimeRemaining());
        }
        final String bodyLanguage = message.getBodyLanguage();
        final ImmutableList.Builder<String> timeInfoBuilder = new ImmutableList.Builder<>();
        if (message.getStatus() <= Message.STATUS_RECEIVED) {
            timeInfoBuilder.add(formattedTime);
            if (fileSize != null) {
                timeInfoBuilder.add(fileSize);
            }
            if (mForceNames || multiReceived) {
                final String displayName = UIHelper.getMessageDisplayName(message);
                if (displayName != null) {
                    timeInfoBuilder.add(displayName);
                }
            }
            if (bodyLanguage != null) {
                timeInfoBuilder.add(bodyLanguage.toUpperCase(Locale.US));
            }
        } else {
            if (bodyLanguage != null) {
                timeInfoBuilder.add(bodyLanguage.toUpperCase(Locale.US));
            }
            if (fileSize != null) {
                timeInfoBuilder.add(fileSize);
            }
            // for space reasons we display only 'additional status info' (send progress or concrete
            // failure reason) or the time
            if (additionalStatusInfo != null) {
                timeInfoBuilder.add(additionalStatusInfo);
            } else {
                timeInfoBuilder.add(formattedTime);
            }
        }
        final var timeInfo = timeInfoBuilder.build();
        viewHolder.time.setText(Joiner.on(" \u00B7 ").join(timeInfo));
    }

    public static @DrawableRes Integer getMessageStatusAsDrawable(
            final Message message, final int status) {
        final var transferable = message.getTransferable();
        return switch (status) {
            case Message.STATUS_WAITING -> R.drawable.ic_more_horiz_24dp;
            case Message.STATUS_UNSEND -> transferable == null ? null : R.drawable.ic_upload_24dp;
            case Message.STATUS_SEND -> R.drawable.ic_done_24dp;
            case Message.STATUS_SEND_RECEIVED, Message.STATUS_SEND_DISPLAYED -> R.drawable
                    .ic_done_all_24dp;
            case Message.STATUS_SEND_FAILED -> {
                final String errorMessage = message.getErrorMessage();
                if (Message.ERROR_MESSAGE_CANCELLED.equals(errorMessage)) {
                    yield R.drawable.ic_cancel_24dp;
                } else {
                    yield R.drawable.ic_error_24dp;
                }
            }
            case Message.STATUS_OFFERED -> R.drawable.ic_p2p_24dp;
            default -> null;
        };
    }

    @Nullable
    private String getAdditionalStatusInfo(final Message message, final int mergedStatus) {
        final String additionalStatusInfo;
        if (mergedStatus == Message.STATUS_SEND_FAILED) {
            final String errorMessage = Strings.nullToEmpty(message.getErrorMessage());
            final String[] errorParts = errorMessage.split("\\u001f", 2);
            if (errorParts.length == 2 && errorParts[0].equals("file-too-large")) {
                additionalStatusInfo = getContext().getString(R.string.file_too_large);
            } else {
                additionalStatusInfo = null;
            }
        } else if (mergedStatus == Message.STATUS_UNSEND) {
            final var transferable = message.getTransferable();
            if (transferable == null) {
                return null;
            }
            return getContext().getString(R.string.sending_file, transferable.getProgress());
        } else {
            additionalStatusInfo = null;
        }
        return additionalStatusInfo;
    }

    private void displayInfoMessage(
            ViewHolder viewHolder, CharSequence text, final BubbleColor bubbleColor) {
        viewHolder.download_button.setVisibility(View.GONE);
        viewHolder.locationCard.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.image.setVisibility(View.GONE);
        viewHolder.messageBody.setVisibility(View.VISIBLE);
        viewHolder.messageBody.setText(text);
        viewHolder.messageBody.setTextColor(
                bubbleToOnSurfaceVariant(viewHolder.messageBody, bubbleColor));
        viewHolder.messageBody.setTextIsSelectable(false);
    }

    private void displayEmojiMessage(
            final ViewHolder viewHolder, final String body, final BubbleColor bubbleColor) {
        viewHolder.download_button.setVisibility(View.GONE);
        viewHolder.locationCard.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.image.setVisibility(View.GONE);
        viewHolder.messageBody.setVisibility(View.VISIBLE);
        setTextColor(viewHolder.messageBody, bubbleColor);
        final Spannable span = new SpannableString(body);
        float size = Emoticons.isEmoji(body) ? 3.0f : 2.0f;
        span.setSpan(
                new RelativeSizeSpan(size), 0, body.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        viewHolder.messageBody.setText(span);
    }

    private void applyQuoteSpan(
            final TextView textView,
            SpannableStringBuilder body,
            int start,
            int end,
            final BubbleColor bubbleColor) {
        if (start > 1 && !"\n\n".equals(body.subSequence(start - 2, start).toString())) {
            body.insert(start++, "\n");
            body.setSpan(
                    new DividerSpan(false), start - 2, start, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            end++;
        }
        if (end < body.length() - 1 && !"\n\n".equals(body.subSequence(end, end + 2).toString())) {
            body.insert(end, "\n");
            body.setSpan(new DividerSpan(false), end, end + 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        final DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        body.setSpan(
                new QuoteSpan(bubbleToOnSurfaceVariant(textView, bubbleColor), metrics),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /**
     * Applies QuoteSpan to group of lines which starts with > or » characters. Appends likebreaks
     * and applies DividerSpan to them to show a padding between quote and text.
     */
    private boolean handleTextQuotes(
            final TextView textView,
            final SpannableStringBuilder body,
            final BubbleColor bubbleColor) {
        boolean startsWithQuote = false;
        int quoteDepth = 1;
        while (QuoteHelper.bodyContainsQuoteStart(body) && quoteDepth <= Config.QUOTE_MAX_DEPTH) {
            char previous = '\n';
            int lineStart = -1;
            int lineTextStart = -1;
            int quoteStart = -1;
            for (int i = 0; i <= body.length(); i++) {
                char current = body.length() > i ? body.charAt(i) : '\n';
                if (lineStart == -1) {
                    if (previous == '\n') {
                        if (i < body.length() && QuoteHelper.isPositionQuoteStart(body, i)) {
                            // Line start with quote
                            lineStart = i;
                            if (quoteStart == -1) quoteStart = i;
                            if (i == 0) startsWithQuote = true;
                        } else if (quoteStart >= 0) {
                            // Line start without quote, apply spans there
                            applyQuoteSpan(textView, body, quoteStart, i - 1, bubbleColor);
                            quoteStart = -1;
                        }
                    }
                } else {
                    // Remove extra spaces between > and first character in the line
                    // > character will be removed too
                    if (current != ' ' && lineTextStart == -1) {
                        lineTextStart = i;
                    }
                    if (current == '\n') {
                        body.delete(lineStart, lineTextStart);
                        i -= lineTextStart - lineStart;
                        if (i == lineStart) {
                            // Avoid empty lines because span over empty line can be hidden
                            body.insert(i++, " ");
                        }
                        lineStart = -1;
                        lineTextStart = -1;
                    }
                }
                previous = current;
            }
            if (quoteStart >= 0) {
                // Apply spans to finishing open quote
                applyQuoteSpan(textView, body, quoteStart, body.length(), bubbleColor);
            }
            quoteDepth++;
        }
        return startsWithQuote;
    }

    private void displayTextMessage(
            final ViewHolder viewHolder, final Message message, final BubbleColor bubbleColor) {
        viewHolder.download_button.setVisibility(View.GONE);
        viewHolder.locationCard.setVisibility(View.GONE);
        viewHolder.image.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.messageBody.setVisibility(View.VISIBLE);
        setTextColor(viewHolder.messageBody, bubbleColor);
        setTextSize(viewHolder.messageBody, this.bubbleDesign.largeFont);
        viewHolder.messageBody.setTypeface(null, Typeface.NORMAL);

        if (message.getBody() != null) {
            final String nick = UIHelper.getMessageDisplayName(message);
            SpannableStringBuilder body = message.getMergedBody();
            boolean hasMeCommand = message.hasMeCommand();
            if (hasMeCommand) {
                body = body.replace(0, Message.ME_COMMAND.length(), nick + " ");
            }
            if (body.length() > Config.MAX_DISPLAY_MESSAGE_CHARS) {
                body = new SpannableStringBuilder(body, 0, Config.MAX_DISPLAY_MESSAGE_CHARS);
                body.append("\u2026");
            }
            Message.MergeSeparator[] mergeSeparators =
                    body.getSpans(0, body.length(), Message.MergeSeparator.class);
            for (Message.MergeSeparator mergeSeparator : mergeSeparators) {
                int start = body.getSpanStart(mergeSeparator);
                int end = body.getSpanEnd(mergeSeparator);
                body.setSpan(new DividerSpan(true), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            boolean startsWithQuote = handleTextQuotes(viewHolder.messageBody, body, bubbleColor);
            if (!message.isPrivateMessage()) {
                if (hasMeCommand) {
                    body.setSpan(
                            new StyleSpan(Typeface.BOLD_ITALIC),
                            0,
                            nick.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } else {
                String privateMarker;
                if (message.getStatus() <= Message.STATUS_RECEIVED) {
                    privateMarker = activity.getString(R.string.private_message);
                } else {
                    Jid cp = message.getCounterpart();
                    privateMarker =
                            activity.getString(
                                    R.string.private_message_to,
                                    Strings.nullToEmpty(cp == null ? null : cp.getResource()));
                }
                body.insert(0, privateMarker);
                int privateMarkerIndex = privateMarker.length();
                if (startsWithQuote) {
                    body.insert(privateMarkerIndex, "\n\n");
                    body.setSpan(
                            new DividerSpan(false),
                            privateMarkerIndex,
                            privateMarkerIndex + 2,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else {
                    body.insert(privateMarkerIndex, " ");
                }
                body.setSpan(
                        new ForegroundColorSpan(
                                bubbleToOnSurfaceVariant(viewHolder.messageBody, bubbleColor)),
                        0,
                        privateMarkerIndex,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                body.setSpan(
                        new StyleSpan(Typeface.BOLD),
                        0,
                        privateMarkerIndex,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (hasMeCommand) {
                    body.setSpan(
                            new StyleSpan(Typeface.BOLD_ITALIC),
                            privateMarkerIndex + 1,
                            privateMarkerIndex + 1 + nick.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            if (message.getConversation().getMode() == Conversation.MODE_MULTI
                    && message.getStatus() == Message.STATUS_RECEIVED) {
                if (message.getConversation() instanceof Conversation conversation) {
                    Pattern pattern =
                            NotificationService.generateNickHighlightPattern(
                                    conversation.getMucOptions().getActualNick());
                    Matcher matcher = pattern.matcher(body);
                    while (matcher.find()) {
                        body.setSpan(
                                new StyleSpan(Typeface.BOLD),
                                matcher.start(),
                                matcher.end(),
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
            }
            Matcher matcher = Emoticons.getEmojiPattern(body).matcher(body);
            while (matcher.find()) {
                if (matcher.start() < matcher.end()) {
                    body.setSpan(
                            new RelativeSizeSpan(1.2f),
                            matcher.start(),
                            matcher.end(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }

            StylingHelper.format(body);
            MyLinkify.addLinks(body, true);
            if (highlightedTerm != null) {
                StylingHelper.highlight(viewHolder.messageBody, body, highlightedTerm);
            }
            viewHolder.messageBody.setAutoLinkMask(0);
            viewHolder.messageBody.setText(body);
            viewHolder.messageBody.setMovementMethod(ClickableMovementMethod.getInstance());
        } else {
            viewHolder.messageBody.setText("");
            viewHolder.messageBody.setTextIsSelectable(false);
        }
    }

    private void displayDownloadableMessage(
            ViewHolder viewHolder,
            final Message message,
            String text,
            final BubbleColor bubbleColor) {
        toggleWhisperInfo(viewHolder, message, bubbleColor);
        viewHolder.image.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.locationCard.setVisibility(View.GONE);
        viewHolder.download_button.setVisibility(View.VISIBLE);
        viewHolder.download_button.setText(text);
        final var attachment = Attachment.of(message);
        final @DrawableRes int imageResource = MediaAdapter.getImageDrawable(attachment);
        viewHolder.download_button.setIconResource(imageResource);
        viewHolder.download_button.setOnClickListener(
                v -> ConversationFragment.downloadFile(activity, message));
    }

    private void displayOpenableMessage(
            ViewHolder viewHolder, final Message message, final BubbleColor bubbleColor) {
        toggleWhisperInfo(viewHolder, message, bubbleColor);
        viewHolder.image.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.locationCard.setVisibility(View.GONE);
        viewHolder.download_button.setVisibility(View.VISIBLE);
        viewHolder.download_button.setText(
                activity.getString(
                        R.string.open_x_file,
                        UIHelper.getFileDescriptionString(activity, message)));
        final var attachment = Attachment.of(message);
        final @DrawableRes int imageResource = MediaAdapter.getImageDrawable(attachment);
        viewHolder.download_button.setIconResource(imageResource);
        viewHolder.download_button.setOnClickListener(v -> openDownloadable(message));
    }

    private void displayLocationMessage(
            ViewHolder viewHolder, final Message message, final BubbleColor bubbleColor) {
        toggleWhisperInfo(viewHolder, message, bubbleColor);
        viewHolder.image.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.download_button.setVisibility(View.GONE);
        viewHolder.locationCard.setVisibility(View.VISIBLE);
        viewHolder.locationCard.setOnClickListener(v -> showLocation(message));
        viewHolder.locationMapThumbnail.setImageDrawable(null);
        try {
            final GeoPoint geoPoint = GeoHelper.parseGeoPoint(Uri.parse(message.getBody()));
            LocationLabelResolver.resolveInto(activity, geoPoint, viewHolder.locationAddress);
            final int widthPx = (int) activity.getResources().getDimension(R.dimen.location_card_width);
            final int heightPx = (int) activity.getResources().getDimension(R.dimen.location_map_height);
            LocationMapThumbnailLoader.loadInto(
                    activity, geoPoint, widthPx, heightPx, viewHolder.locationMapThumbnail);
        } catch (final IllegalArgumentException e) {
            viewHolder.locationAddress.setText(R.string.tap_to_view_location);
        }
    }

    private void displayAudioMessage(
            ViewHolder viewHolder, Message message, final BubbleColor bubbleColor) {
        toggleWhisperInfo(viewHolder, message, bubbleColor);
        viewHolder.image.setVisibility(View.GONE);
        viewHolder.download_button.setVisibility(View.GONE);
        viewHolder.locationCard.setVisibility(View.GONE);
        final RelativeLayout audioPlayer = viewHolder.audioPlayer;
        audioPlayer.setVisibility(View.VISIBLE);
        AudioPlayer.ViewHolder.get(audioPlayer).setBubbleColor(bubbleColor);
        this.audioPlayer.init(audioPlayer, message);
    }

    private void displayMediaPreviewMessage(
            ViewHolder viewHolder, final Message message, final BubbleColor bubbleColor) {
        toggleWhisperInfo(viewHolder, message, bubbleColor);
        viewHolder.download_button.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.locationCard.setVisibility(View.GONE);
        viewHolder.image.setVisibility(View.VISIBLE);
        final FileParams params = message.getFileParams();
        final float target = activity.getResources().getDimension(R.dimen.image_preview_width);
        final int scaledW;
        final int scaledH;
        if (Math.max(params.height, params.width) * metrics.density <= target) {
            scaledW = (int) (params.width * metrics.density);
            scaledH = (int) (params.height * metrics.density);
        } else if (Math.max(params.height, params.width) <= target) {
            scaledW = params.width;
            scaledH = params.height;
        } else if (params.width <= params.height) {
            scaledW = (int) (params.width / ((double) params.height / target));
            scaledH = (int) target;
        } else {
            scaledW = (int) target;
            scaledH = (int) (params.height / ((double) params.width / target));
        }
        final LinearLayout.LayoutParams layoutParams =
                new LinearLayout.LayoutParams(scaledW, scaledH);
        viewHolder.image.setLayoutParams(layoutParams);
        activity.loadBitmap(message, viewHolder.image);
        viewHolder.image.setOnClickListener(v -> openDownloadable(message));
    }

    private void toggleWhisperInfo(
            ViewHolder viewHolder, final Message message, final BubbleColor bubbleColor) {
        if (message.isPrivateMessage()) {
            final String privateMarker;
            if (message.getStatus() <= Message.STATUS_RECEIVED) {
                privateMarker = activity.getString(R.string.private_message);
            } else {
                Jid cp = message.getCounterpart();
                privateMarker =
                        activity.getString(
                                R.string.private_message_to,
                                Strings.nullToEmpty(cp == null ? null : cp.getResource()));
            }
            final SpannableString body = new SpannableString(privateMarker);
            body.setSpan(
                    new ForegroundColorSpan(
                            bubbleToOnSurfaceVariant(viewHolder.messageBody, bubbleColor)),
                    0,
                    privateMarker.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            body.setSpan(
                    new StyleSpan(Typeface.BOLD),
                    0,
                    privateMarker.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            viewHolder.messageBody.setText(body);
            viewHolder.messageBody.setVisibility(View.VISIBLE);
        } else {
            viewHolder.messageBody.setVisibility(View.GONE);
        }
    }

    private void loadMoreMessages(Conversation conversation) {
        conversation.setLastClearHistory(0, null);
        activity.xmppConnectionService.updateConversation(conversation);
        conversation.setHasMessagesLeftOnServer(true);
        conversation.setFirstMamReference(null);
        long timestamp = conversation.getLastMessageTransmitted().getTimestamp();
        if (timestamp == 0) {
            timestamp = System.currentTimeMillis();
        }
        conversation.messagesLoaded.set(true);
        MessageArchiveService.Query query =
                activity.xmppConnectionService
                        .getMessageArchiveService()
                        .query(conversation, new MamReference(0), timestamp, false);
        if (query != null) {
            Toast.makeText(activity, R.string.fetching_history_from_server, Toast.LENGTH_LONG)
                    .show();
        } else {
            Toast.makeText(
                            activity,
                            R.string.not_fetching_history_retention_period,
                            Toast.LENGTH_SHORT)
                    .show();
        }
    }

    @Override
    public View getView(final int position, View view, final @NonNull ViewGroup parent) {
        final Message message = getItem(position);
        final boolean omemoEncryption = message.getEncryption() == Message.ENCRYPTION_AXOLOTL;
        final boolean isInValidSession = message.isValidInSession();
        final Conversational conversation = message.getConversation();
        final Account account = conversation.getAccount();
        final int type = getItemViewType(position);
        ViewHolder viewHolder;
        if (view == null) {
            viewHolder = new ViewHolder();
            switch (type) {
                case DATE_SEPARATOR:
                    view =
                            activity.getLayoutInflater()
                                    .inflate(R.layout.item_message_date_bubble, parent, false);
                    viewHolder.status_message = view.findViewById(R.id.message_body);
                    viewHolder.message_box = view.findViewById(R.id.message_box);
                    break;
                case RTP_SESSION:
                    view =
                            activity.getLayoutInflater()
                                    .inflate(R.layout.item_message_rtp_session, parent, false);
                    viewHolder.status_message = view.findViewById(R.id.message_body);
                    viewHolder.message_box = view.findViewById(R.id.message_box);
                    viewHolder.indicatorReceived = view.findViewById(R.id.indicator_received);
                    break;
                case SENT:
                    view =
                            activity.getLayoutInflater()
                                    .inflate(R.layout.item_message_sent, parent, false);
                    viewHolder.message_box = view.findViewById(R.id.message_box);
                    viewHolder.contact_picture = view.findViewById(R.id.message_photo);
                    viewHolder.download_button = view.findViewById(R.id.download_button);
                    viewHolder.locationCard = view.findViewById(R.id.location_card);
                    viewHolder.locationLabel = view.findViewById(R.id.location_label);
                    viewHolder.locationAddress = view.findViewById(R.id.location_address);
                    viewHolder.locationMapThumbnail = view.findViewById(R.id.location_map_thumbnail);
                    viewHolder.indicator = view.findViewById(R.id.security_indicator);
                    viewHolder.edit_indicator = view.findViewById(R.id.edit_indicator);
                    viewHolder.image = view.findViewById(R.id.message_image);
                    viewHolder.messageBody = view.findViewById(R.id.message_body);
                    viewHolder.time = view.findViewById(R.id.message_time);
                    viewHolder.indicatorReceived = view.findViewById(R.id.indicator_received);
                    viewHolder.audioPlayer = view.findViewById(R.id.audio_player);
                    viewHolder.reactions = view.findViewById(R.id.reactions);
                    break;
                case RECEIVED:
                    view =
                            activity.getLayoutInflater()
                                    .inflate(R.layout.item_message_received, parent, false);
                    viewHolder.message_box = view.findViewById(R.id.message_box);
                    viewHolder.contact_picture = view.findViewById(R.id.message_photo);
                    viewHolder.download_button = view.findViewById(R.id.download_button);
                    viewHolder.locationCard = view.findViewById(R.id.location_card);
                    viewHolder.locationLabel = view.findViewById(R.id.location_label);
                    viewHolder.locationAddress = view.findViewById(R.id.location_address);
                    viewHolder.locationMapThumbnail = view.findViewById(R.id.location_map_thumbnail);
                    viewHolder.indicator = view.findViewById(R.id.security_indicator);
                    viewHolder.edit_indicator = view.findViewById(R.id.edit_indicator);
                    viewHolder.image = view.findViewById(R.id.message_image);
                    viewHolder.messageBody = view.findViewById(R.id.message_body);
                    viewHolder.time = view.findViewById(R.id.message_time);
                    viewHolder.indicatorReceived = view.findViewById(R.id.indicator_received);
                    viewHolder.encryption = view.findViewById(R.id.message_encryption);
                    viewHolder.audioPlayer = view.findViewById(R.id.audio_player);
                    viewHolder.reactions = view.findViewById(R.id.reactions);
                    break;
                case STATUS:
                    view =
                            activity.getLayoutInflater()
                                    .inflate(R.layout.item_message_status, parent, false);
                    viewHolder.contact_picture = view.findViewById(R.id.message_photo);
                    viewHolder.status_message = view.findViewById(R.id.status_message);
                    viewHolder.load_more_messages = view.findViewById(R.id.load_more_messages);
                    break;
                default:
                    throw new AssertionError("Unknown view type");
            }
            view.setTag(viewHolder);
        } else {
            viewHolder = (ViewHolder) view.getTag();
            if (viewHolder == null) {
                return view;
            }
        }

        if (view != highlightedItemView && (view.getAlpha() != 1f || view.getScaleX() != 1f)) {
            view.animate().cancel();
            view.setAlpha(1f);
            view.setScaleX(1f);
            view.setScaleY(1f);
            view.setTranslationZ(0f);
        }

        final boolean colorfulBackground = this.bubbleDesign.colorfulChatBubbles;
        final BubbleColor bubbleColor;
        if (type == RECEIVED) {
            if (isInValidSession) {
                bubbleColor = colorfulBackground ? BubbleColor.SECONDARY : BubbleColor.SURFACE;
            } else {
                bubbleColor = BubbleColor.WARNING;
            }
        } else if (type == SENT) {
            bubbleColor = BubbleColor.SENT;
        } else {
            bubbleColor = colorfulBackground ? BubbleColor.TERTIARY : BubbleColor.SURFACE_HIGH;
        }

        if (type == DATE_SEPARATOR) {
            if (UIHelper.today(message.getTimeSent())) {
                viewHolder.status_message.setText(R.string.today);
            } else if (UIHelper.yesterday(message.getTimeSent())) {
                viewHolder.status_message.setText(R.string.yesterday);
            } else {
                viewHolder.status_message.setText(
                        DateUtils.formatDateTime(
                                activity,
                                message.getTimeSent(),
                                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR));
            }
            if (colorfulBackground) {
                setBackgroundTint(viewHolder.message_box, BubbleColor.PRIMARY);
                setTextColor(viewHolder.status_message, BubbleColor.PRIMARY);
            } else {
                setBackgroundTint(viewHolder.message_box, BubbleColor.SURFACE_HIGH);
                setTextColor(viewHolder.status_message, BubbleColor.SURFACE_HIGH);
            }
            return view;
        } else if (type == RTP_SESSION) {
            final boolean received = message.getStatus() <= Message.STATUS_RECEIVED;
            final RtpSessionStatus rtpSessionStatus = RtpSessionStatus.of(message.getBody());
            final long duration = rtpSessionStatus.duration;
            if (received) {
                if (duration > 0) {
                    viewHolder.status_message.setText(
                            activity.getString(
                                    R.string.incoming_call_duration_timestamp,
                                    TimeFrameUtils.resolve(activity, duration),
                                    UIHelper.readableTimeDifferenceFull(
                                            activity, message.getTimeSent())));
                } else if (rtpSessionStatus.successful) {
                    viewHolder.status_message.setText(R.string.incoming_call);
                } else {
                    viewHolder.status_message.setText(
                            activity.getString(
                                    R.string.missed_call_timestamp,
                                    UIHelper.readableTimeDifferenceFull(
                                            activity, message.getTimeSent())));
                }
            } else {
                if (duration > 0) {
                    viewHolder.status_message.setText(
                            activity.getString(
                                    R.string.outgoing_call_duration_timestamp,
                                    TimeFrameUtils.resolve(activity, duration),
                                    UIHelper.readableTimeDifferenceFull(
                                            activity, message.getTimeSent())));
                } else {
                    viewHolder.status_message.setText(
                            activity.getString(
                                    R.string.outgoing_call_timestamp,
                                    UIHelper.readableTimeDifferenceFull(
                                            activity, message.getTimeSent())));
                }
            }
            if (colorfulBackground) {
                setBackgroundTint(viewHolder.message_box, BubbleColor.SECONDARY);
                setTextColor(viewHolder.status_message, BubbleColor.SECONDARY);
                setImageTint(viewHolder.indicatorReceived, BubbleColor.SECONDARY);
            } else {
                setBackgroundTint(viewHolder.message_box, BubbleColor.SURFACE_HIGH);
                setTextColor(viewHolder.status_message, BubbleColor.SURFACE_HIGH);
                setImageTint(viewHolder.indicatorReceived, BubbleColor.SURFACE_HIGH);
            }
            viewHolder.indicatorReceived.setImageResource(
                    RtpSessionStatus.getDrawable(received, rtpSessionStatus.successful));
            return view;
        } else if (type == STATUS) {
            if ("LOAD_MORE".equals(message.getBody())) {
                viewHolder.status_message.setVisibility(View.GONE);
                viewHolder.contact_picture.setVisibility(View.GONE);
                viewHolder.load_more_messages.setVisibility(View.VISIBLE);
                viewHolder.load_more_messages.setOnClickListener(
                        v -> loadMoreMessages((Conversation) message.getConversation()));
            } else {
                viewHolder.status_message.setVisibility(View.VISIBLE);
                viewHolder.load_more_messages.setVisibility(View.GONE);
                viewHolder.status_message.setText(message.getBody());
                boolean showAvatar;
                if (conversation.getMode() == Conversation.MODE_SINGLE) {
                    showAvatar = true;
                    if (message.getBody().startsWith("Disappearing message")) {
                        showAvatar = false;
                    } else {
                        showAvatar = true;
                        AvatarWorkerTask.loadAvatar(message, viewHolder.contact_picture, R.dimen.avatar_on_status_message);
                    }
                } else if (message.getCounterpart() != null
                        || message.getTrueCounterpart() != null
                        || (message.getCounterparts() != null
                                && message.getCounterparts().size() > 0)) {
                    showAvatar = true;
                    AvatarWorkerTask.loadAvatar(
                            message, viewHolder.contact_picture, R.dimen.avatar_on_status_message);
                } else {
                    showAvatar = false;
                }
                if (showAvatar) {
                    viewHolder.contact_picture.setAlpha(0.5f);
                    viewHolder.contact_picture.setVisibility(View.VISIBLE);
                } else {
                    viewHolder.contact_picture.setVisibility(View.GONE);
                }
            }
            return view;
        } else {
            viewHolder.message_box.setClipToOutline(true);
            AvatarWorkerTask.loadAvatar(message, viewHolder.contact_picture, R.dimen.avatar);
        }

        resetClickListener(viewHolder.message_box, viewHolder.messageBody);

        viewHolder.contact_picture.setOnClickListener(
                v -> {
                    if (MessageAdapter.this.mOnContactPictureClickedListener != null) {
                        MessageAdapter.this.mOnContactPictureClickedListener
                                .onContactPictureClicked(message);
                    }
                });
        viewHolder.contact_picture.setOnLongClickListener(
                v -> {
                    if (MessageAdapter.this.mOnContactPictureLongClickedListener != null) {
                        MessageAdapter.this.mOnContactPictureLongClickedListener
                                .onContactPictureLongClicked(v, message);
                        return true;
                    } else {
                        return false;
                    }
                });

        final Transferable transferable = message.getTransferable();
        final boolean unInitiatedButKnownSize = MessageUtils.unInitiatedButKnownSize(message);
        if (unInitiatedButKnownSize
                || message.isDeleted()
                || (transferable != null
                        && transferable.getStatus() != Transferable.STATUS_UPLOADING)) {
            if (unInitiatedButKnownSize
                    || transferable != null
                            && transferable.getStatus() == Transferable.STATUS_OFFER) {
                displayDownloadableMessage(
                        viewHolder,
                        message,
                        activity.getString(
                                R.string.download_x_file,
                                UIHelper.getFileDescriptionString(activity, message)),
                        bubbleColor);
            } else if (transferable != null
                    && transferable.getStatus() == Transferable.STATUS_OFFER_CHECK_FILESIZE) {
                displayDownloadableMessage(
                        viewHolder,
                        message,
                        activity.getString(
                                R.string.check_x_filesize,
                                UIHelper.getFileDescriptionString(activity, message)),
                        bubbleColor);
            } else {
                displayInfoMessage(
                        viewHolder,
                        UIHelper.getMessagePreview(activity, message).first,
                        bubbleColor);
            }
        } else if (message.isFileOrImage()
                && message.getEncryption() != Message.ENCRYPTION_PGP
                && message.getEncryption() != Message.ENCRYPTION_DECRYPTION_FAILED) {
            if (message.getFileParams().width > 0 && message.getFileParams().height > 0) {
                displayMediaPreviewMessage(viewHolder, message, bubbleColor);
            } else if (message.getFileParams().runtime > 0) {
                displayAudioMessage(viewHolder, message, bubbleColor);
            } else {
                displayOpenableMessage(viewHolder, message, bubbleColor);
            }
        } else if (message.getEncryption() == Message.ENCRYPTION_PGP) {
            if (account.isPgpDecryptionServiceConnected()) {
                if (conversation instanceof Conversation
                        && !account.hasPendingPgpIntent((Conversation) conversation)) {
                    displayInfoMessage(
                            viewHolder,
                            activity.getString(R.string.message_decrypting),
                            bubbleColor);
                } else {
                    displayInfoMessage(
                            viewHolder, activity.getString(R.string.pgp_message), bubbleColor);
                }
            } else {
                displayInfoMessage(
                        viewHolder, activity.getString(R.string.install_openkeychain), bubbleColor);
                viewHolder.message_box.setOnClickListener(this::promptOpenKeychainInstall);
                viewHolder.messageBody.setOnClickListener(this::promptOpenKeychainInstall);
            }
        } else if (message.getEncryption() == Message.ENCRYPTION_DECRYPTION_FAILED) {
            displayInfoMessage(
                    viewHolder, activity.getString(R.string.decryption_failed), bubbleColor);
        } else if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE) {
            displayInfoMessage(
                    viewHolder,
                    activity.getString(R.string.not_encrypted_for_this_device),
                    bubbleColor);
        } else if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL_FAILED) {
            displayInfoMessage(
                    viewHolder, activity.getString(R.string.omemo_decryption_failed), bubbleColor);
        } else {
            if (message.isGeoUri()) {
                displayLocationMessage(viewHolder, message, bubbleColor);
            } else if (message.bodyIsOnlyEmojis() && message.getType() != Message.TYPE_PRIVATE) {
                displayEmojiMessage(viewHolder, message.getBody().trim(), bubbleColor);
            } else if (message.treatAsDownloadable()) {
                try {
                    new URI(message.getBody());
                    displayDownloadableMessage(
                            viewHolder,
                            message,
                            activity.getString(
                                    R.string.check_x_filesize,
                                    UIHelper.getFileDescriptionString(activity, message)),
                            bubbleColor);
                } catch (Exception e) {
                    displayDownloadableMessage(
                            viewHolder,
                            message,
                            activity.getString(
                                    R.string.check_x_filesize,
                                    UIHelper.getFileDescriptionString(activity, message)),
                            bubbleColor);
                }
            } else {
                displayTextMessage(viewHolder, message, bubbleColor);
            }
        }

        setBackgroundTint(viewHolder.message_box, bubbleColor);
        setTextColor(viewHolder.messageBody, bubbleColor);

        if (type == RECEIVED) {
            setTextColor(viewHolder.encryption, bubbleColor);
            if (isInValidSession) {
                viewHolder.encryption.setVisibility(View.GONE);
            } else {
                viewHolder.encryption.setVisibility(View.VISIBLE);
                if (omemoEncryption && !message.isTrusted()) {
                    viewHolder.encryption.setText(R.string.not_trusted);
                } else {
                    viewHolder.encryption.setText(
                            CryptoHelper.encryptionTypeToText(message.getEncryption()));
                }
            }
            BindingAdapters.setReactionsOnReceived(
                    viewHolder.reactions,
                    message.getAggregatedReactions(),
                    reactions -> sendReactions(message, reactions),
                    () -> showDetailedReactions(message),
                    () -> addReaction(message));
        } else if (type == SENT) {
            BindingAdapters.setReactionsOnSent(
                    viewHolder.reactions,
                    message.getAggregatedReactions(),
                    reactions -> sendReactions(message, reactions),
                    () -> showDetailedReactions(message),
                    () -> addReaction(message));
        }

        if ((type == SENT || type == RECEIVED) && viewHolder.message_box != null) {
            final View anchor = viewHolder.message_box;
            setMessageOptionLongClick(anchor, message, anchor, view, type);
            setMessageOptionLongClick(viewHolder.messageBody, message, anchor, view, type);
            setMessageOptionLongClick(viewHolder.image, message, anchor, view, type);
            setMessageOptionLongClick(viewHolder.download_button, message, anchor, view, type);
            setMessageOptionLongClick(viewHolder.audioPlayer, message, anchor, view, type);
            setMessageOptionLongClick(viewHolder.locationCard, message, anchor, view, type);
        }

        displayStatus(viewHolder, message, type, bubbleColor);
        return view;
    }

    private boolean showDetailedReactions(final Message message) {
        final var c = message.getConversation();
        if (c instanceof Conversation conversation && c.getMode() == Conversational.MODE_MULTI) {
            final var mucOptions = conversation.getMucOptions();
            final var reactions = message.getAggregatedReactions();
            if (reactions.reactions == null || reactions.reactions.isEmpty()) {
                return true;
            }
            final StringBuilder messageBuilder = new StringBuilder();
            boolean hasContent = false;
            for (final var reactionEntry : reactions.reactions) {
                final var emoji = reactionEntry.getKey();
                final var users =
                        mucOptions.findUsers(
                                Collections2.filter(
                                        message.getReactions(), r -> r.reaction.equals(emoji)));
                if (users.isEmpty()) {
                    continue;
                }
                if (hasContent) {
                    messageBuilder.append("\n\n");
                }
                messageBuilder
                        .append(emoji)
                        .append(": ")
                        .append(UIHelper.concatNames(users));
                hasContent = true;
            }
            if (!hasContent) {
                return true;
            }
            final MaterialAlertDialogBuilder dialogBuilder =
                    new MaterialAlertDialogBuilder(activity);
            dialogBuilder.setMessage(messageBuilder.toString());
            dialogBuilder.create().show();
            return true;
        } else {
            return false;
        }
    }

    private void sendReactions(final Message message, final Collection<String> reactions) {
        activity.sendReactions(message, reactions);
    }

    private void addReaction(final Message message) {
        activity.addReaction(
                message,
                reactions -> activity.sendReactions(message, reactions));
    }
    private void setMessageOptionLongClick(
            @Nullable View target,
            final Message message,
            final View anchor,
            final View itemView,
            final int viewType) {
        if (target == null) {
            return;
        }
        target.setOnLongClickListener(
                v -> {
                    showMessageOptions(message, anchor, itemView, viewType);
                    return true;
                });
    }

    private void showMessageOptions(
            final Message message, final View anchor, final View itemView, final int viewType) {
        if (messageActionListener == null) {
            return;
        }
        final PopupMenu popupMenu =
                new PopupMenu(activity, anchor, viewType == SENT ? Gravity.END : Gravity.START);
        popupMenu.getMenuInflater().inflate(R.menu.message_context, popupMenu.getMenu());
        final Menu menu = popupMenu.getMenu();
        if (!prepareMessageMenu(menu, message)) {
            return;
        }

        final List<CharSequence> optionTitles = new ArrayList<>();
        final List<Integer> optionIds = new ArrayList<>();
        for (int i = 0; i < menu.size(); i++) {
            final MenuItem item = menu.getItem(i);
            if (item.isVisible()) {
                optionTitles.add(item.getTitle());
                optionIds.add(item.getItemId());
            }
        }

        final boolean showReactions = canAddReaction(message);
        if (!showReactions && optionTitles.isEmpty()) {
            return;
        }

        final ArrayAdapter<CharSequence> adapter =
                new ArrayAdapter<>(
                        activity,
                        R.layout.item_message_option,
                        android.R.id.text1,
                        optionTitles);

        final ListPopupWindow popupWindow =
                new ListPopupWindow(
                        activity, null, androidx.appcompat.R.attr.listPopupWindowStyle);
        popupWindow.setAnchorView(anchor);
        popupWindow.setModal(true);
        popupWindow.setAdapter(adapter);
        popupWindow.setDropDownGravity(viewType == SENT ? Gravity.END : Gravity.START);
        popupWindow.setInputMethodMode(ListPopupWindow.INPUT_METHOD_NOT_NEEDED);
        popupWindow.setBackgroundDrawable(
                ContextCompat.getDrawable(activity, R.drawable.bg_reaction_menu));
        popupWindow.setOnItemClickListener(
                (parent, view, position, id) -> {
                    popupWindow.dismiss();
                    messageActionListener.onMessageAction(message, optionIds.get(position));
                });
        popupWindow.setOnDismissListener(() -> clearLongPressHighlight(itemView));

        // Extra headroom beyond the measured content width: the reaction row is measured
        // detached from any window, and its final attached width has repeatedly come in
        // wider than that detached measurement (clipping the trailing emojis/"more"
        // button when the popup was sized exactly to the detached measurement).
        final int widthBuffer = (int) (32 * activity.getResources().getDisplayMetrics().density);

        int contentWidth = Math.max(anchor.getWidth(), measureContentWidth(adapter));
        if (showReactions) {
            final DialogAddReactionBinding reactionBinding =
                    DialogAddReactionBinding.inflate(activity.getLayoutInflater(), null, false);
            reactionBinding
                    .getRoot()
                    .setLayoutParams(
                            new AbsListView.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT));
            activity.bindReactionOptions(
                    reactionBinding,
                    message,
                    reactions -> sendReactions(message, reactions),
                    popupWindow::dismiss);
            final int widthSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            final int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            reactionBinding.getRoot().measure(widthSpec, heightSpec);
            contentWidth =
                    Math.max(
                            contentWidth,
                            reactionBinding.getRoot().getMeasuredWidth() + widthBuffer);
            popupWindow.setPromptPosition(ListPopupWindow.POSITION_PROMPT_ABOVE);
            popupWindow.setPromptView(reactionBinding.getRoot());
        }

        popupWindow.setContentWidth(contentWidth);
        applyLongPressHighlight(itemView);
        popupWindow.show();
    }

    private void applyLongPressHighlight(final View itemView) {
        final ViewParent parent = itemView.getParent();
        if (!(parent instanceof ListView listView)) {
            return;
        }
        highlightedItemView = itemView;
        final float elevation = 8 * activity.getResources().getDisplayMetrics().density;
        for (int i = 0; i < listView.getChildCount(); i++) {
            final View child = listView.getChildAt(i);
            child.animate().cancel();
            if (child == itemView) {
                child.animate()
                        .scaleX(LONG_PRESS_SCALE)
                        .scaleY(LONG_PRESS_SCALE)
                        .translationZ(elevation)
                        .setDuration(LONG_PRESS_ANIMATION_DURATION)
                        .start();
            } else {
                child.animate()
                        .alpha(LONG_PRESS_DIM_ALPHA)
                        .setDuration(LONG_PRESS_ANIMATION_DURATION)
                        .start();
            }
        }
    }

    private void clearLongPressHighlight(final View itemView) {
        if (highlightedItemView == itemView) {
            highlightedItemView = null;
        }
        final ViewParent parent = itemView.getParent();
        if (!(parent instanceof ListView listView)) {
            return;
        }
        for (int i = 0; i < listView.getChildCount(); i++) {
            final View child = listView.getChildAt(i);
            child.animate().cancel();
            if (child == itemView) {
                child.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationZ(0f)
                        .setDuration(LONG_PRESS_ANIMATION_DURATION)
                        .start();
            } else {
                child.animate().alpha(1f).setDuration(LONG_PRESS_ANIMATION_DURATION).start();
            }
        }
    }

    private int measureContentWidth(final ArrayAdapter<CharSequence> adapter) {
        final FrameLayout parent = new FrameLayout(activity);
        final int widthSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        final int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        View itemView = null;
        int itemType = 0;
        int maxWidth = 0;
        for (int i = 0; i < adapter.getCount(); i++) {
            final int type = adapter.getItemViewType(i);
            if (type != itemType) {
                itemType = type;
                itemView = null;
            }
            itemView = adapter.getView(i, itemView, parent);
            itemView.measure(widthSpec, heightSpec);
            maxWidth = Math.max(maxWidth, itemView.getMeasuredWidth());
        }
        return maxWidth;
    }

    private boolean canAddReaction(final Message message) {
        if (message.isDeleted()) {
            return false;
        }
        if (message.getType() == Message.TYPE_STATUS || message.getType() == Message.TYPE_RTP_SESSION) {
            return false;
        }
        if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE
                || message.getEncryption() == Message.ENCRYPTION_AXOLOTL_FAILED) {
            return false;
        }
        final Transferable transferable = message.getTransferable();
        if (message.getStatus() == Message.STATUS_RECEIVED
                && transferable != null
                && (transferable.getStatus() == Transferable.STATUS_CANCELLED
                        || transferable.getStatus() == Transferable.STATUS_FAILED)) {
            return false;
        }
        final boolean showError =
                message.getStatus() == Message.STATUS_SEND_FAILED
                        && message.getErrorMessage() != null
                        && !Message.ERROR_MESSAGE_CANCELLED.equals(message.getErrorMessage());
        if (showError) {
            return false;
        }
        final Conversational conversational = message.getConversation();
        if (conversational instanceof Conversation) {
            return true;
        }
        return false;
    }

    private boolean prepareMessageMenu(final Menu menu, final Message message) {
        for (int i = 0; i < menu.size(); i++) {
            menu.getItem(i).setVisible(false);
        }

        final Transferable transferable = message.getTransferable();
        Message relevantForCorrection = message;
        while (relevantForCorrection.mergeable(relevantForCorrection.next())) {
            relevantForCorrection = relevantForCorrection.next();
        }
        if (message.getType() == Message.TYPE_STATUS || message.getType() == Message.TYPE_RTP_SESSION) {
            return false;
        }

        if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE
                || message.getEncryption() == Message.ENCRYPTION_AXOLOTL_FAILED) {
            return false;
        }

        if (message.getStatus() == Message.STATUS_RECEIVED
                && transferable != null
                && (transferable.getStatus() == Transferable.STATUS_CANCELLED
                        || transferable.getStatus() == Transferable.STATUS_FAILED)) {
            return false;
        }

        final boolean deleted = message.isDeleted();
        final boolean encrypted =
                message.getEncryption() == Message.ENCRYPTION_DECRYPTION_FAILED
                        || message.getEncryption() == Message.ENCRYPTION_PGP;
        final boolean receiving =
                message.getStatus() == Message.STATUS_RECEIVED
                        && (transferable instanceof JingleFileTransferConnection
                                || transferable instanceof HttpDownloadConnection);

        final MenuItem reportAndBlock = menu.findItem(R.id.action_report_and_block);
        final MenuItem openWith = menu.findItem(R.id.open_with);
        final MenuItem copyMessage = menu.findItem(R.id.copy_message);
        final MenuItem copyLink = menu.findItem(R.id.copy_link);
        final MenuItem quoteMessage = menu.findItem(R.id.quote_message);
        final MenuItem retryDecryption = menu.findItem(R.id.retry_decryption);
        final MenuItem correctMessage = menu.findItem(R.id.correct_message);
        final MenuItem shareWith = menu.findItem(R.id.share_with);
        final MenuItem sendAgain = menu.findItem(R.id.send_again);
        final MenuItem downloadFile = menu.findItem(R.id.download_file);
        final MenuItem viewBackupAsText = menu.findItem(R.id.view_backup_as_text);
        final MenuItem saveBackupToDirectory = menu.findItem(R.id.save_backup_to_directory);
        final MenuItem downloadFileTo = menu.findItem(R.id.download_file_to);
        final MenuItem cancelTransmission = menu.findItem(R.id.cancel_transmission);
        final MenuItem deleteFile = menu.findItem(R.id.delete_file);
        final MenuItem sendBugReport = menu.findItem(R.id.upload_bug_report);
        final MenuItem sendToFileSafe = menu.findItem(R.id.upload_file_s3);
        final MenuItem showErrorMessage = menu.findItem(R.id.show_error_message);

        final Conversational conversational = message.getConversation();
        final boolean unInitiatedButKnownSize = MessageUtils.unInitiatedButKnownSize(message);
        final boolean showError =
                message.getStatus() == Message.STATUS_SEND_FAILED
                        && message.getErrorMessage() != null
                        && !Message.ERROR_MESSAGE_CANCELLED.equals(message.getErrorMessage());

        if (conversational instanceof Conversation conversation) {
            sendBugReport.setVisible(
                    conversation.getJid().asBareJid().equals(Config.BUG_REPORTS));
        }

        if (message.getStatus() == Message.STATUS_RECEIVED
                && conversational instanceof Conversation conversation) {
            final XmppConnection connection = conversation.getAccount().getXmppConnection();
            if (conversation.isWithStranger()
                    && message.getServerMsgId() != null
                    && !conversation.isBlocked()
                    && connection != null
                    && connection.getFeatures().spamReporting()) {
                reportAndBlock.setVisible(true);
            }
        }

        if (!message.isFileOrImage()
                && !encrypted
                && !message.isGeoUri()
                && !message.treatAsDownloadable()
                && !unInitiatedButKnownSize
                && transferable == null) {
            copyMessage.setVisible(true);
            quoteMessage.setVisible(!showError && MessageUtils.prepareQuote(message).length() > 0);
            final String scheme = ShareUtil.getLinkScheme(message.getMergedBody());
            if ("xmpp".equals(scheme)) {
                copyLink.setTitle(R.string.copy_jabber_id);
                copyLink.setVisible(true);
            } else if (scheme != null) {
                copyLink.setVisible(true);
            }
        }

        if (message.getEncryption() == Message.ENCRYPTION_DECRYPTION_FAILED && !deleted) {
            retryDecryption.setVisible(true);
        }

        if (!showError
                && relevantForCorrection.getType() == Message.TYPE_TEXT
                && !message.isGeoUri()
                && relevantForCorrection.isLastCorrectableMessage()
                && conversational instanceof Conversation) {
            correctMessage.setVisible(true);
        }

        if ((message.isFileOrImage() && !deleted && !receiving)
                || (message.getType() == Message.TYPE_TEXT && !message.treatAsDownloadable())
                        && !unInitiatedButKnownSize
                        && transferable == null) {
            shareWith.setVisible(true);
        }

        if (message.getStatus() == Message.STATUS_SEND_FAILED) {
            sendAgain.setVisible(true);
        }

        if (message.hasFileOnRemoteHost()
                || message.isGeoUri()
                || message.treatAsDownloadable()
                || unInitiatedButKnownSize
                || transferable instanceof HttpDownloadConnection) {
        }

        if (message.isFileOrImage() && deleted && message.hasFileOnRemoteHost()) {
            downloadFile.setVisible(true);
            downloadFile.setTitle(
                    activity.getString(
                            R.string.download_x_file,
                            UIHelper.getFileDescriptionString(activity, message)));
        }

        final boolean canExportAttachment =
                message.isFileOrImage()
                        && !deleted
                        && transferable == null
                        && activity.xmppConnectionService != null
                        && activity.xmppConnectionService
                                .getFileBackend()
                                .getFile(message)
                                .exists();

        if (canExportAttachment) {
            downloadFileTo.setVisible(true);
            downloadFileTo.setTitle(
                    activity.getString(
                            R.string.download_x_file_to));
        }

        final boolean waitingOfferedSending =
                message.getStatus() == Message.STATUS_WAITING
                        || message.getStatus() == Message.STATUS_UNSEND
                        || message.getStatus() == Message.STATUS_OFFERED;
        final boolean cancelable =
                (transferable != null && !deleted)
                        || waitingOfferedSending && message.needsUploading();
        if (cancelable) {
            cancelTransmission.setVisible(true);
        }

        if (message.isFileOrImage() && !deleted && !cancelable) {
            final String path = message.getRelativeFilePath();
            if (path == null
                    || !path.startsWith("/")
                    || FileBackend.inConversationsDirectory(activity, path)) {
                deleteFile.setVisible(true);
                deleteFile.setTitle(
                        activity.getString(
                                R.string.delete_x_file,
                                UIHelper.getFileDescriptionString(activity, message)));
                sendToFileSafe.setVisible(isFileSafeEnabled());
            }
        }

        final boolean isBackupAttachment =
                message.isFileOrImage()
                        && ExportBackupWorker.MIME_TYPE.equals(message.getMimeType());
        if (isBackupAttachment && !deleted) {
            viewBackupAsText.setVisible(true);
            saveBackupToDirectory.setVisible(true);
        }

        if (showError) {
            showErrorMessage.setVisible(true);
        }

        final String mime = message.isFileOrImage() ? message.getMimeType() : null;
        if ((message.isGeoUri() && GeoHelper.openInOsmAnd(activity, message))
                || (mime != null && mime.startsWith("audio/"))) {
            openWith.setVisible(true);
        }

        for (int i = 0; i < menu.size(); i++) {
            if (menu.getItem(i).isVisible()) {
                return true;
            }
        }
        return false;
    }

    private boolean isFileSafeEnabled() {
        return getPreferences(activity).getBoolean("upload_enabled", false);
    }

    private void promptOpenKeychainInstall(View view) {
        activity.showInstallPgpDialog();
    }

    public FileBackend getFileBackend() {
        return activity.xmppConnectionService.getFileBackend();
    }

    public void stopAudioPlayer() {
        audioPlayer.stop();
    }

    public void unregisterListenerInAudioPlayer() {
        audioPlayer.unregisterListener();
    }

    public void startStopPending() {
        audioPlayer.startStopPending();
    }

    public void openDownloadable(Message message) {
        final FileBackend fileBackend = activity.xmppConnectionService.getFileBackend();
        final DownloadableFile file = fileBackend.getFile(message);
        if (ViewUtil.isGalleryMime(message.getMimeType())) {
            final List<File> files = new ArrayList<>();
            final List<String> mimes = new ArrayList<>();
            for (int i = 0; i < getCount(); ++i) {
                final Message current = getItem(i);
                if (current == null || !current.isFileOrImage()) {
                    continue;
                }
                final String mime = current.getMimeType();
                if (!ViewUtil.isGalleryMime(mime)) {
                    continue;
                }
                files.add(fileBackend.getFile(current));
                mimes.add(mime);
            }
            if (!files.isEmpty()) {
                ViewUtil.viewInGallery(activity, files, mimes, file);
                return;
            }
        }
        ViewUtil.view(activity, file);
    }

    private void showLocation(Message message) {
        for (Intent intent : GeoHelper.createGeoIntentsFromMessage(activity, message)) {
            if (intent.resolveActivity(getContext().getPackageManager()) != null) {
                getContext().startActivity(intent);
                return;
            }
        }
        Toast.makeText(
                        activity,
                        R.string.no_application_found_to_display_location,
                        Toast.LENGTH_SHORT)
                .show();
    }

    public void updatePreferences() {
        final AppSettings appSettings = new AppSettings(activity);
        this.bubbleDesign =
                new BubbleDesign(appSettings.isColorfulChatBubbles(), appSettings.isLargeFont());
    }

    public void setHighlightedTerm(List<String> terms) {
        this.highlightedTerm = terms == null ? null : StylingHelper.filterHighlightedWords(terms);
    }

    public interface OnContactPictureClicked {
        void onContactPictureClicked(Message message);
    }

    public interface OnContactPictureLongClicked {
        void onContactPictureLongClicked(View v, Message message);
    }

    public interface OnMessageActionListener {
        boolean onMessageAction(@NonNull Message message, @IdRes int actionId);
    }

    private static void setBackgroundTint(final View view, final BubbleColor bubbleColor) {
        view.setBackgroundTintList(bubbleToColorStateList(view, bubbleColor));
    }

    private static ColorStateList bubbleToColorStateList(
            final View view, final BubbleColor bubbleColor) {
        if (bubbleColor == BubbleColor.SENT) {
            return ColorStateList.valueOf(
                    ContextCompat.getColor(view.getContext(), R.color.glacier_button));
        }

        final @AttrRes int colorAttributeResId =
                switch (bubbleColor) {
                    case SURFACE -> Activities.isNightMode(view.getContext())
                            ? com.google.android.material.R.attr.colorSurfaceContainerHigh
                            : com.google.android.material.R.attr.colorSurfaceContainerLow;
                    case SURFACE_HIGH -> Activities.isNightMode(view.getContext())
                            ? com.google.android.material.R.attr.colorSurfaceContainerHighest
                            : com.google.android.material.R.attr.colorSurfaceContainerHigh;
                    case PRIMARY -> com.google.android.material.R.attr.colorPrimaryContainer;
                    case SECONDARY -> com.google.android.material.R.attr.colorSecondaryContainer;
                    case TERTIARY -> com.google.android.material.R.attr.colorTertiaryContainer;
                    case WARNING -> com.google.android.material.R.attr.colorErrorContainer;
                    case SENT -> throw new IllegalStateException("Handled earlier");
                };
        return ColorStateList.valueOf(MaterialColors.getColor(view, colorAttributeResId));
    }

    public static void setImageTint(final ImageView imageView, final BubbleColor bubbleColor) {
        ImageViewCompat.setImageTintList(
                imageView, bubbleToOnSurfaceColorStateList(imageView, bubbleColor));
    }

    public static void setImageTintError(final ImageView imageView) {
        ImageViewCompat.setImageTintList(
                imageView,
                ColorStateList.valueOf(
                        MaterialColors.getColor(
                                imageView, android.R.attr.colorError)));
    }

    public static void setTextColor(final TextView textView, final BubbleColor bubbleColor) {
        final var color = bubbleToOnSurfaceColor(textView, bubbleColor);
        textView.setTextColor(color);
        if (BubbleColor.SURFACES.contains(bubbleColor)) {
            textView.setLinkTextColor(
                    MaterialColors.getColor(
                            textView, android.R.attr.colorPrimary));
        } else {
            textView.setLinkTextColor(color);
        }
    }

    private static void setTextSize(final TextView textView, final boolean largeFont) {
        if (largeFont) {
            textView.setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        } else {
            textView.setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        }
    }

    private static @ColorInt int bubbleToOnSurfaceVariant(
            final View view, final BubbleColor bubbleColor) {
        if (bubbleColor == BubbleColor.SENT) {
            return bubbleToOnSurfaceColor(view, bubbleColor);
        }

        final @AttrRes int colorAttributeResId;
        if (BubbleColor.SURFACES.contains(bubbleColor)) {
            colorAttributeResId = com.google.android.material.R.attr.colorOnSurfaceVariant;
        } else {
            colorAttributeResId = bubbleToOnSurface(bubbleColor);
        }
        return MaterialColors.getColor(view, colorAttributeResId);
    }

    private static @ColorInt int bubbleToOnSurfaceColor(
            final View view, final BubbleColor bubbleColor) {
        if (bubbleColor == BubbleColor.SENT) {
            return ContextCompat.getColor(view.getContext(), android.R.color.white);
        }
        return MaterialColors.getColor(view, bubbleToOnSurface(bubbleColor));
    }

    public static ColorStateList bubbleToOnSurfaceColorStateList(
            final View view, final BubbleColor bubbleColor) {
        return ColorStateList.valueOf(bubbleToOnSurfaceColor(view, bubbleColor));
    }

    private static @AttrRes int bubbleToOnSurface(final BubbleColor bubbleColor) {
        return switch (bubbleColor) {
            case SURFACE, SURFACE_HIGH -> com.google.android.material.R.attr.colorOnSurface;
            case PRIMARY -> com.google.android.material.R.attr.colorOnPrimaryContainer;
            case SECONDARY -> com.google.android.material.R.attr.colorOnSecondaryContainer;
            case TERTIARY -> com.google.android.material.R.attr.colorOnTertiaryContainer;
            case SENT -> com.google.android.material.R.attr.colorOnPrimary;
            case WARNING -> com.google.android.material.R.attr.colorOnErrorContainer;
        };
    }

    public enum BubbleColor {
        SURFACE,
        SURFACE_HIGH,
        PRIMARY,
        SECONDARY,
        TERTIARY,
        SENT,
        WARNING;

        private static final Collection<BubbleColor> SURFACES =
                Arrays.asList(BubbleColor.SURFACE, BubbleColor.SURFACE_HIGH);
    }

    private static class BubbleDesign {
        public final boolean colorfulChatBubbles;
        public final boolean largeFont;

        private BubbleDesign(final boolean colorfulChatBubbles, final boolean largeFont) {
            this.colorfulChatBubbles = colorfulChatBubbles;
            this.largeFont = largeFont;
        }
    }

    private static class ViewHolder {

        public MaterialButton load_more_messages;
        public ImageView edit_indicator;
        public RelativeLayout audioPlayer;
        protected LinearLayout message_box;
        protected MaterialButton download_button;
        protected MaterialCardView locationCard;
        protected TextView locationLabel;
        protected TextView locationAddress;
        protected ImageView locationMapThumbnail;
        protected ImageView image;
        protected ImageView indicator;
        protected ImageView indicatorReceived;
        protected TextView time;
        protected TextView messageBody;
        protected ImageView contact_picture;
        protected TextView status_message;
        protected TextView encryption;
        protected ChipGroup reactions;
    }
}
