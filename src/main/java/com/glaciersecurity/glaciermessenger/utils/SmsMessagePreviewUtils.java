package com.glaciersecurity.glaciermessenger.utils;

import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.StyleSpan;

import androidx.annotation.Nullable;

import com.twilio.conversations.Media;
import com.twilio.conversations.Message;

import java.util.List;
import java.util.Locale;

public final class SmsMessagePreviewUtils {
    private static final String LEGACY_MEDIA_PREFIX = "__MEDIA__:";

    private SmsMessagePreviewUtils() {
    }

    public static String getPreviewText(Message message) {
        return buildPreview(message).getText();
    }

    public static SmsPreview buildPreview(Message message) {
        if (message == null) {
            return new SmsPreview("", false, null);
        }
        String body = message.getBody();
        if (body != null && !body.trim().isEmpty()) {
            return new SmsPreview(body, false, null);
        }
        List<Media> media = message.getAttachedMedia();
        if (media != null && !media.isEmpty()) {
            String label = describeMediaType(media.get(0));
            return new SmsPreview(label, true, label);
        }
        return new SmsPreview("", false, null);
    }

    public static SmsPreview fromStoredPreview(@Nullable String text, @Nullable Boolean isMediaFlag) {
        String safeText = text == null ? "" : text;
        if (isMediaFlag != null) {
            return new SmsPreview(safeText, isMediaFlag, isMediaFlag ? safeText : null);
        }
        if (isLegacyMediaPreview(safeText)) {
            String label = stripLegacyMediaPrefix(safeText);
            return new SmsPreview(label, true, label);
        }
        return new SmsPreview(safeText, false, null);
    }

    public static CharSequence formatPreviewText(SmsPreview preview) {
        if (preview == null || preview.getText().isEmpty()) {
            return "";
        }
        if (preview.isMedia()) {
            String label = preview.getText();
            SpannableString styled = new SpannableString(label);
            styled.setSpan(new StyleSpan(Typeface.ITALIC), 0, label.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            return styled;
        }
        return preview.getText();
    }

    private static boolean isLegacyMediaPreview(String text) {
        return text != null && text.startsWith(LEGACY_MEDIA_PREFIX);
    }

    private static String stripLegacyMediaPrefix(String text) {
        if (!isLegacyMediaPreview(text)) {
            return text;
        }
        return text.substring(LEGACY_MEDIA_PREFIX.length());
    }

    private static String describeMediaType(Media media) {
        if (media == null) {
            return "Media";
        }
        String contentType = null;
        try {
            contentType = media.getContentType();
        } catch (Exception ignore) {
        }
        if (contentType != null) {
            String normalized = contentType.toLowerCase(Locale.US);
            if (normalized.startsWith("image/")) {
                return "Image";
            }
            if (normalized.startsWith("video/")) {
                return "Video";
            }
            if (normalized.startsWith("audio/")) {
                return "Audio";
            }
        }
        return "Media";
    }

    public static final class SmsPreview {
        private final String text;
        private final boolean isMedia;
        @Nullable
        private final String mediaTypeLabel;

        public SmsPreview(String text, boolean isMedia, @Nullable String mediaTypeLabel) {
            this.text = text == null ? "" : text;
            this.isMedia = isMedia;
            this.mediaTypeLabel = mediaTypeLabel;
        }

        public String getText() {
            return text;
        }

        public boolean isMedia() {
            return isMedia;
        }

        @Nullable
        public String getMediaTypeLabel() {
            return mediaTypeLabel;
        }
    }
}
