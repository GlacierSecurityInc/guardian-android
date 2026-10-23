package com.glaciersecurity.glaciermessenger.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.utils.XmppUri;

import java.util.List;
import java.util.Locale;

public class ExternalXmppLinkActivity extends BaseActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        routeExternalIntent(getIntent());
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        routeExternalIntent(intent);
        finish();
    }

    private void routeExternalIntent(@Nullable final Intent sourceIntent) {
        final Uri uri = sourceIntent == null ? null : sourceIntent.getData();
        final String action = sourceIntent == null ? null : sourceIntent.getAction();
        if (!isAllowedExternalInvite(action, uri)) {
            Toast.makeText(this, R.string.invalid_jid, Toast.LENGTH_SHORT).show();
            return;
        }
        final Intent target = new Intent(this, StartConversationActivity.class);
        target.setAction(StartConversationActivity.ACTION_INTERNAL_PROCESS_INVITE);
        target.setData(uri.normalizeScheme());
        target.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(target);
    }

    static boolean isAllowedExternalInvite(@Nullable final String action, @Nullable final Uri uri) {
        if (uri == null) {
            return false;
        }
        if (!(Intent.ACTION_VIEW.equals(action) || Intent.ACTION_SENDTO.equals(action))) {
            return false;
        }
        final String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        switch (scheme.toLowerCase(Locale.US)) {
            case "xmpp":
                return uri.getSchemeSpecificPart() != null;
            case "imto":
                final String host = uri.getHost();
                return Intent.ACTION_SENDTO.equals(action)
                        && ("xmpp".equalsIgnoreCase(host) || "jabber".equalsIgnoreCase(host))
                        && hasPathContent(uri);
            case "https":
                if (!XmppUri.INVITE_DOMAIN.equalsIgnoreCase(uri.getHost())) {
                    return false;
                }
                final List<String> segments = uri.getPathSegments();
                if (segments.size() < 2) {
                    return false;
                }
                if (!("i".equalsIgnoreCase(segments.get(0))
                        || "j".equalsIgnoreCase(segments.get(0)))) {
                    return false;
                }
                final XmppUri xmppUri = new XmppUri(uri);
                return xmppUri.isValidJid();
            default:
                return false;
        }
    }

    private static boolean hasPathContent(@NonNull final Uri uri) {
        final String encodedPath = uri.getEncodedPath();
        return encodedPath != null && encodedPath.length() > 1;
    }
}
